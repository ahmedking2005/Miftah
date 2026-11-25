package com.ahmed.miftah.agent

import com.ahmed.miftah.core.AppSettings
import com.ahmed.miftah.core.ToolDef
import com.ahmed.miftah.core.Vault
import com.ahmed.miftah.core.defaultHttpClient
import com.ahmed.miftah.skills.SkillContext
import com.ahmed.miftah.skills.SkillStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private fun schema(vararg props: Pair<String, String>, required: List<String> = emptyList()): JSONObject {
    val p = JSONObject()
    props.forEach { (name, desc) -> p.put(name, JSONObject().put("type", "string").put("description", desc)) }
    val o = JSONObject().put("type", "object").put("properties", p)
    if (required.isNotEmpty()) o.put("required", JSONArray(required))
    return o
}

private fun fetchText(http: OkHttpClient, url: String, headers: Map<String, String> = emptyMap()): String {
    val builder = Request.Builder().url(url).header("User-Agent", "MiftahApp/1.0")
    headers.forEach { (k, v) -> builder.header(k, v) }
    http.newCall(builder.build()).execute().use { resp ->
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
        return resp.body?.string() ?: ""
    }
}

/**
 * Registers the built-in tool batch: read-only tools, Stage 2 write tools
 * (service tools — GitHub + generic HTTP) and the Stage 3 skill-script
 * runner. All executors read credentials from the vault directly; write
 * tools flow through the batched-approval gate via the registry.
 * [scriptRunner] is injected so tests can substitute a fake Python bridge;
 * production passes a Chaquopy-backed runner.
 */
fun buildBuiltinRegistry(
    vault: Vault,
    skillStore: SkillStore,
    http: OkHttpClient = defaultHttpClient(),
    approvalGate: ApprovalGate? = null,
    audit: (com.ahmed.miftah.core.LogEntry) -> Unit = {},
    scriptRunner: ScriptRunner? = null,
    memoryStore: com.ahmed.miftah.core.MemoryStore? = null,
    sessionStore: com.ahmed.miftah.core.SessionStore? = null
): ToolRegistry {
    val registry = ToolRegistry(approvalGate = approvalGate, audit = audit)

    registry.register(
        ToolDef(
            "web_search",
            "Search the web (best-effort, keyless DuckDuckGo). Returns result titles, links and snippets.",
            schema("query" to "Search query", required = listOf("query"))
        )
    ) { args ->
        val q = args.getString("query")
        val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8")
        val html = fetchText(http, url)
        val results = parseDuckDuckGo(html)
        if (results.isEmpty()) "No results parsed (search endpoint may have changed or blocked the request). Query: $q"
        else results.take(6).joinToString("\n\n")
    }

    registry.register(
        ToolDef(
            "fetch_url",
            "Fetch a web page URL and return its readable text (truncated).",
            schema("url" to "Full URL to fetch", required = listOf("url"))
        )
    ) { args ->
        val html = fetchText(http, args.getString("url"))
        stripHtml(html).take(6000)
    }

    registry.register(
        ToolDef(
            "github_user_repos",
            "List the GitHub repositories of a user (uses the stored GitHub token if present; public data only otherwise).",
            schema("username" to "GitHub username", required = listOf("username"))
        )
    ) { args ->
        val user = args.getString("username")
        val headers = HashMap<String, String>()
        vault.getCredential(AppSettings.CRED_GITHUB_TOKEN)?.takeIf { it.isNotBlank() }
            ?.let { headers["Authorization"] = "Bearer $it" }
        val json = fetchText(http, "https://api.github.com/users/$user/repos?per_page=30&sort=updated", headers)
        val arr = JSONArray(json)
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            sb.append("- ").append(r.getString("full_name"))
                .append(" ★").append(r.optInt("stargazers_count"))
                .append(" — ").append(r.optString("description")).append('\n')
        }
        if (sb.isEmpty()) "No repositories found for $user" else sb.toString()
    }

    registry.register(
        ToolDef(
            "device_time",
            "Get the current date and time on this device.",
            schema()
        )
    ) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss (EEEE) z", Locale.getDefault())
        fmt.timeZone = TimeZone.getDefault()
        fmt.format(Date())
    }

    registry.register(
        ToolDef(
            "calculator",
            "Evaluate an arithmetic expression (+ - * / % and parentheses).",
            schema("expression" to "e.g. (12.5 * 3) / 2", required = listOf("expression"))
        )
    ) { args ->
        val v = Calculator.evaluate(args.getString("expression"))
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    }

    // Skill tools (progressive disclosure levels 2 and 3).
    registry.register(SkillContext.loadSkillTool) { args ->
        val name = args.getString("name")
        val skill = skillStore.enabledSkills().firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return@register "Error: no enabled skill named '$name'"
        skillStore.bodyOf(skill).ifBlank { "(skill has no body)" }
    }
    registry.register(SkillContext.readSkillFileTool) { args ->
        val name = args.getString("skill")
        val path = args.getString("path")
        val skill = skillStore.enabledSkills().firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return@register "Error: no enabled skill named '$name'"
        skillStore.readFile(skill, path)?.take(6000) ?: "Error: file not found in skill"
    }

    // Stage 3: run a skill's bundled Python script (write-class: every run
    // passes through the batched approval gate; the approval card states
    // that the script may use the network).
    registry.register(
        SkillContext.runSkillScriptTool,
        approvalDescriber = { args ->
            "run_skill_script · ${args.optString("skill")}/${args.optString("script")} · " +
                "script may use network · runs in the skill sandbox, no keys"
        }
    ) { args ->
        val runner = scriptRunner
            ?: return@register "Error: script execution is not available in this build."
        val name = args.optString("skill")
        val skill = skillStore.enabledSkills().firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return@register "Error: no enabled skill named '$name'"
        // allowed-tools enforcement: a skill that declares allowed-tools may
        // only run scripts when run_skill_script is among them.
        if (!com.ahmed.miftah.skills.SkillScripts.canRunScript(skill.allowedTools)) {
            return@register "Error: skill '${skill.name}' does not allow run_skill_script (its allowed-tools: ${skill.allowedTools.joinToString()})."
        }
        val scriptArg = args.optString("script")
        // A skill can only run its OWN scripts: resolution is rooted at this
        // skill's dir and rejects anything escaping its scripts/ folder.
        val resolved = com.ahmed.miftah.skills.SkillScripts.resolveScript(skillStore.skillDir(skill), scriptArg)
            ?: return@register "Error: '$scriptArg' is not a runnable script of skill '${skill.name}'. Runnable scripts: " +
                (skillStore.scriptsOf(skill).joinToString().ifBlank { "(none)" })
        val sandbox = skillStore.sandboxDir(skill)
        com.ahmed.miftah.skills.SkillScripts.prepareSandbox(skillStore.skillDir(skill), sandbox)
        val sandboxScript = java.io.File(sandbox, resolved.relativeTo(skillStore.skillDir(skill)).path)
        val result = runner.run(
            sandboxScript,
            com.ahmed.miftah.skills.SkillScripts.splitArgs(args.optString("args")),
            sandbox
        )
        ScriptRunCore.formatForModel(result)
    }

    // Stage 2: write tools against GitHub and arbitrary REST services.
    registerServiceTools(registry, vault, http)

    // Cross-session memory: save_memory (write — batched approval) and
    // search_history (read) over the persisted sessions.
    if (memoryStore != null && sessionStore != null) {
        registerMemoryTools(registry, memoryStore, sessionStore)
    }

    return registry
}

internal fun parseDuckDuckGo(html: String): List<String> {
    val results = ArrayList<String>()
    val linkRegex = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    val snippetRegex = Regex("""<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    val snippets = snippetRegex.findAll(html).map { stripHtml(it.groupValues[1]) }.toList()
    linkRegex.findAll(html).forEachIndexed { i, m ->
        val title = stripHtml(m.groupValues[2])
        var href = m.groupValues[1]
        // DuckDuckGo redirect links wrap the real URL in uddg=
        val uddg = Regex("""[?&]uddg=([^&]+)""").find(href)?.groupValues?.get(1)
        if (uddg != null) href = java.net.URLDecoder.decode(uddg, "UTF-8")
        val snippet = snippets.getOrNull(i) ?: ""
        results.add("$title\n$href\n$snippet")
    }
    return results
}

internal fun stripHtml(html: String): String {
    var t = html
    t = Regex("""<script.*?</script>""", RegexOption.DOT_MATCHES_ALL).replace(t, " ")
    t = Regex("""<style.*?</style>""", RegexOption.DOT_MATCHES_ALL).replace(t, " ")
    t = Regex("""<[^>]+>""").replace(t, " ")
    t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
    return t.replace(Regex("""\s+"""), " ").trim()
}
