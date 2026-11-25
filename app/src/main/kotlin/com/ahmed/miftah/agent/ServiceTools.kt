package com.ahmed.miftah.agent

import com.ahmed.miftah.core.ServiceCredential
import com.ahmed.miftah.core.ServiceStore
import com.ahmed.miftah.core.ToolDef
import com.ahmed.miftah.core.Vault
import com.ahmed.miftah.core.defaultHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

/**
 * Stage 2 write tools. Every tool here is non-read-only, so the registry
 * routes each call through the batched approval gate automatically — there
 * is no execution path that skips the bundle.
 *
 * Credential discipline: executors read secrets from the vault at execution
 * time and inject them into requests themselves. Tool arguments reference
 * services by id/name only; a key never appears in args, results, or
 * approval descriptions.
 */

private fun writeSchema(vararg props: Pair<String, String>, required: List<String> = emptyList()): JSONObject {
    val p = JSONObject()
    props.forEach { (name, desc) -> p.put(name, JSONObject().put("type", "string").put("description", desc)) }
    val o = JSONObject().put("type", "object").put("properties", p)
    if (required.isNotEmpty()) o.put("required", JSONArray(required))
    return o
}

/** Pure GitHub API helpers — JVM-testable, no I/O. */
object GitHubApi {
    private val REPO_RE = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    /** True for "owner/name" with GitHub-legal characters only. */
    fun isValidRepo(raw: String): Boolean = REPO_RE.matches(raw.trim())

    private fun encodePath(path: String): String =
        path.trim().trim('/').split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }

    fun contentsUrl(repo: String, path: String): String =
        "https://api.github.com/repos/${repo.trim()}/contents/${encodePath(path)}"

    fun issuesUrl(repo: String): String =
        "https://api.github.com/repos/${repo.trim()}/issues"

    /** Body for create-or-update-file; [sha] is required by the API when updating an existing file. */
    fun putFileBody(content: String, message: String, sha: String?): JSONObject {
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)))
        if (!sha.isNullOrBlank()) body.put("sha", sha)
        return body
    }

    fun issueBody(title: String, body: String): JSONObject =
        JSONObject().put("title", title).put("body", body)

    /** One-line success summary from a create/update-file response. */
    fun summarizeFileResponse(json: JSONObject): String {
        val content = json.optJSONObject("content")
        val commit = json.optJSONObject("commit")
        val path = content?.optString("path").orEmpty()
        val url = content?.optString("html_url").orEmpty()
        val sha = commit?.optString("sha")?.take(7).orEmpty()
        return buildString {
            append("File saved")
            if (path.isNotEmpty()) append(": ").append(path)
            if (sha.isNotEmpty()) append(" (commit ").append(sha).append(")")
            if (url.isNotEmpty()) append("\n").append(url)
        }
    }

    /** One-line success summary from a create-issue response. */
    fun summarizeIssueResponse(json: JSONObject): String {
        val number = json.optInt("number")
        val title = json.optString("title")
        val url = json.optString("html_url")
        return buildString {
            append("Issue created")
            if (number > 0) append(" #").append(number)
            if (title.isNotEmpty()) append(": ").append(title)
            if (url.isNotEmpty()) append("\n").append(url)
        }
    }
}

/** Pure request-resolution logic for the generic http_request tool. */
object HttpRequestSpec {
    val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")

    /** Upper-cased whitelisted method (blank/absent = GET), or null when the model asked for something else. */
    fun normalizeMethod(raw: String?): String? {
        val m = raw?.trim()?.uppercase()?.ifBlank { null } ?: "GET"
        return if (m in METHODS) m else null
    }

    /**
     * Resolves the final URL: absolute http(s) URLs pass through untouched;
     * anything else is treated as a path joined onto the service's base URL.
     * Returns null when a relative path has no service base to join onto.
     */
    fun resolveUrl(service: ServiceCredential?, urlArg: String): String? {
        val u = urlArg.trim()
        if (u.startsWith("https://") || u.startsWith("http://")) return u
        val base = service?.baseUrl?.let { ServiceStore.normalizeBaseUrl(it) } ?: return null
        if (u.isEmpty()) return base
        return base + "/" + u.trimStart('/')
    }

    /** Approval-card text: method + FULL resolved URL (+ service name). Never contains any key. */
    fun describe(service: ServiceCredential?, method: String?, urlArg: String): String {
        val resolved = resolveUrl(service, urlArg) ?: urlArg.trim()
        val m = method?.trim()?.uppercase()?.ifBlank { null } ?: "GET"
        return buildString {
            append("http_request · ").append(m).append(' ').append(resolved)
            if (service != null) append(" · service: ").append(service.name)
            append(" · has side effects")
        }
    }
}

fun registerServiceTools(
    registry: ToolRegistry,
    vault: Vault,
    http: OkHttpClient = defaultHttpClient()
) {
    // ---------- GitHub: create or update a file ----------
    registry.register(
        ToolDef(
            "github_create_or_update_file",
            "Create or update a file in a GitHub repository. SIDE EFFECT: writes to GitHub " +
                "using the user's stored token. Args: repo (\"owner/name\"), path, content, " +
                "message (commit message). Fails with a clear error when no GitHub token is configured.",
            writeSchema(
                "repo" to "Repository as owner/name",
                "path" to "File path inside the repository",
                "content" to "Full new file content",
                "message" to "Commit message",
                required = listOf("repo", "path", "content", "message")
            ),
            readOnly = false
        )
    ) { args ->
        val token = vault.githubToken()
            ?: return@register "Error: GitHub is not configured. Ask the user to add a GitHub personal access token in the Keys tab (Services section)."
        val repo = args.optString("repo").trim()
        if (!GitHubApi.isValidRepo(repo)) return@register "Error: 'repo' must look like \"owner/name\"."
        val path = args.optString("path")
        if (path.isBlank()) return@register "Error: 'path' is required."
        val url = GitHubApi.contentsUrl(repo, path)
        // The contents API requires the current blob sha when updating.
        var existingSha: String? = null
        http.newCall(githubRequest(url, token).get().build()).execute().use { resp ->
            if (resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                existingSha = try { JSONObject(body).optString("sha").ifBlank { null } } catch (e: Exception) { null }
            } else if (resp.code != 404) {
                return@register "Error: GitHub lookup failed (HTTP ${resp.code}): ${resp.body?.string()?.take(300)}"
            }
        }
        val putBody = GitHubApi.putFileBody(
            args.optString("content"),
            args.optString("message").ifBlank { "Update $path via Miftah" },
            existingSha
        )
        http.newCall(
            githubRequest(url, token)
                .put(putBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return@register "Error: GitHub write failed (HTTP ${resp.code}): ${text.take(300)}"
            GitHubApi.summarizeFileResponse(JSONObject(text))
        }
    }

    // ---------- GitHub: create an issue ----------
    registry.register(
        ToolDef(
            "github_create_issue",
            "Create an issue in a GitHub repository. SIDE EFFECT: posts to GitHub using the " +
                "user's stored token. Args: repo (\"owner/name\"), title, body.",
            writeSchema(
                "repo" to "Repository as owner/name",
                "title" to "Issue title",
                "body" to "Issue body (Markdown)",
                required = listOf("repo", "title")
            ),
            readOnly = false
        )
    ) { args ->
        val token = vault.githubToken()
            ?: return@register "Error: GitHub is not configured. Ask the user to add a GitHub personal access token in the Keys tab (Services section)."
        val repo = args.optString("repo").trim()
        if (!GitHubApi.isValidRepo(repo)) return@register "Error: 'repo' must look like \"owner/name\"."
        val title = args.optString("title")
        if (title.isBlank()) return@register "Error: 'title' is required."
        val body = GitHubApi.issueBody(title, args.optString("body"))
        http.newCall(
            githubRequest(GitHubApi.issuesUrl(repo), token)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return@register "Error: GitHub issue failed (HTTP ${resp.code}): ${text.take(300)}"
            GitHubApi.summarizeIssueResponse(JSONObject(text))
        }
    }

    // ---------- Generic HTTP request against any REST API ----------
    registry.register(
        ToolDef(
            "http_request",
            "Call an arbitrary REST API. SIDE EFFECT: performs a real HTTP request (any method), " +
                "shown to the user for approval with its full URL and method. Use 'service' with the " +
                "name/id of a saved service to target its base URL and have its stored API key injected " +
                "automatically (the key is never visible to you). Without a service, 'url' must be a " +
                "full http(s) URL and no key is sent. Args: service (optional), url (full URL or a path " +
                "relative to the service base URL), method (GET/POST/PUT/PATCH/DELETE, default GET), " +
                "body (optional JSON string).",
            writeSchema(
                "service" to "Saved service name or id (optional)",
                "url" to "Full URL, or a path relative to the service base URL",
                "method" to "HTTP method (default GET)",
                "body" to "Optional JSON request body"
            ),
            readOnly = false
        ),
        approvalDescriber = { args ->
            val service = args.optString("service").takeIf { it.isNotBlank() }
                ?.let { vault.serviceByIdOrName(it) }
            HttpRequestSpec.describe(service, args.optString("method"), args.optString("url"))
        }
    ) { args ->
        val serviceName = args.optString("service")
        val service = if (serviceName.isBlank()) null else vault.serviceByIdOrName(serviceName)
        if (serviceName.isNotBlank() && service == null) {
            return@register "Error: service '$serviceName' is not configured. Ask the user to add it in the Keys tab (Services section). Configured services: " +
                (vault.services().joinToString { it.name }.ifBlank { "(none)" })
        }
        val method = HttpRequestSpec.normalizeMethod(args.optString("method"))
            ?: return@register "Error: unsupported method '${args.optString("method")}'. Use one of ${HttpRequestSpec.METHODS.joinToString()}."
        val url = HttpRequestSpec.resolveUrl(service, args.optString("url"))
            ?: return@register "Error: 'url' must be a full http(s) URL when no service is given."
        val bodyText = args.optString("body")
        val builder = Request.Builder().url(url).header("User-Agent", "MiftahApp/1.0")
        if (service != null) builder.header(service.headerName, service.authHeaderValue())
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.method(
                method,
                bodyText.toRequestBody("application/json".toMediaType())
            )
        }
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            "HTTP ${resp.code}\n" + text.take(4000)
        }
    }
}

private fun githubRequest(url: String, token: String): Request.Builder =
    Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "MiftahApp/1.0")
