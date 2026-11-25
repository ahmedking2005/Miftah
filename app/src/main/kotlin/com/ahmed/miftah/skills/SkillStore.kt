package com.ahmed.miftah.skills

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val allowedTools: List<String>,
    val source: String,
    val dirName: String,
    var enabled: Boolean,
    var reviewed: Boolean
)

/**
 * On-device skill library. Skills live under filesDir/skills/<dirName>/ with
 * their original files (SKILL.md + references + scripts). Metadata is kept in
 * SharedPreferences. A skill can only be enabled after its review screen was
 * opened (reviewed flag), per the spec's mandatory-review rule.
 */
class SkillStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("miftah_skills", Context.MODE_PRIVATE)
    private val root: File get() = File(context.filesDir, "skills").apply { mkdirs() }

    fun all(): List<Skill> {
        val raw = prefs.getString("skills", null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Skill(
                o.getString("id"), o.getString("name"), o.optString("desc"),
                o.optJSONArray("tools")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                o.optString("source"), o.getString("dir"),
                o.optBoolean("enabled"), o.optBoolean("reviewed")
            )
        }
    }

    private fun save(skills: List<Skill>) {
        val arr = JSONArray()
        skills.forEach { s ->
            arr.put(
                JSONObject().put("id", s.id).put("name", s.name).put("desc", s.description)
                    .put("tools", JSONArray(s.allowedTools)).put("source", s.source)
                    .put("dir", s.dirName).put("enabled", s.enabled).put("reviewed", s.reviewed)
            )
        }
        prefs.edit().putString("skills", arr.toString()).apply()
    }

    fun byId(id: String): Skill? = all().firstOrNull { it.id == id }
    fun enabledSkills(): List<Skill> = all().filter { it.enabled }

    fun setEnabled(id: String, enabled: Boolean) {
        val skills = all().toMutableList()
        val idx = skills.indexOfFirst { it.id == id }
        if (idx < 0) return
        // Enabling requires review first.
        if (enabled && !skills[idx].reviewed) return
        skills[idx] = skills[idx].copy(enabled = enabled)
        save(skills)
    }

    fun markReviewed(id: String) {
        val skills = all().toMutableList()
        val idx = skills.indexOfFirst { it.id == id }
        if (idx < 0) return
        skills[idx] = skills[idx].copy(reviewed = true)
        save(skills)
    }

    fun delete(id: String) {
        val skills = all().toMutableList()
        val s = skills.firstOrNull { it.id == id } ?: return
        File(root, s.dirName).deleteRecursively()
        skills.remove(s)
        save(skills)
    }

    fun skillDir(skill: Skill): File = File(root, skill.dirName)

    fun bodyOf(skill: Skill): String {
        val f = File(skillDir(skill), "SKILL.md")
        return if (f.exists()) SkillParser.parse(f.readText(), skill.name).body else ""
    }

    fun fullTextOf(skill: Skill): String {
        val f = File(skillDir(skill), "SKILL.md")
        return if (f.exists()) f.readText() else ""
    }

    fun filesOf(skill: Skill): List<String> {
        val dir = skillDir(skill)
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(dir).path }.sorted().toList()
    }

    fun readFile(skill: Skill, relativePath: String): String? {
        val f = File(skillDir(skill), relativePath)
        if (!f.exists() || !f.isFile) return null
        // Path traversal guard: must stay inside the skill dir.
        if (!f.canonicalPath.startsWith(skillDir(skill).canonicalPath)) return null
        return f.readText()
    }

    /** Imports every SKILL.md found in a ZIP stream. Returns imported skills. */
    fun importZip(input: InputStream, source: String): List<Skill> {
        val staging = File(root, ".staging_" + UUID.randomUUID().toString())
        staging.mkdirs()
        try {
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(staging, entry.name)
                        if (out.canonicalPath.startsWith(staging.canonicalPath)) {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
            return harvest(staging, source)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Imports skills from an already-extracted directory tree. */
    fun importDirectory(dir: File, source: String): List<Skill> = harvest(dir, source)

    private fun harvest(fromDir: File, source: String): List<Skill> {
        val skillFiles = fromDir.walkTopDown()
            .filter { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
            .toList()
        val imported = ArrayList<Skill>()
        val skills = all().toMutableList()
        for (sf in skillFiles) {
            val skillParent = sf.parentFile ?: continue
            val parsed = SkillParser.parse(sf.readText(), skillParent.name)
            val dirName = UUID.randomUUID().toString()
            val dest = File(root, dirName)
            skillParent.copyRecursively(dest, overwrite = true)
            val skill = Skill(
                id = UUID.randomUUID().toString(),
                name = parsed.name,
                description = parsed.description,
                allowedTools = parsed.allowedTools,
                source = source,
                dirName = dirName,
                enabled = false,
                reviewed = false
            )
            skills.add(skill)
            imported.add(skill)
        }
        save(skills)
        return imported
    }

    /** Downloads a GitHub repo ZIP (codeload) and imports its skills. */
    suspend fun importFromGitHub(repoUrl: String): List<Skill> = withContext(Dispatchers.IO) {
        val cleaned = repoUrl.trim().trimEnd('/').removeSuffix(".git")
        val match = Regex("github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)").find(cleaned)
            ?: throw IllegalArgumentException("Not a GitHub repository URL")
        val owner = match.groupValues[1]
        val repo = match.groupValues[2]
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        // Try main, then master.
        var lastError: Exception? = null
        for (branch in listOf("main", "master")) {
            try {
                val req = Request.Builder()
                    .url("https://codeload.github.com/$owner/$repo/zip/refs/heads/$branch")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    return@withContext importZip(resp.body!!.byteStream(), repoUrl)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Download failed")
    }
}
