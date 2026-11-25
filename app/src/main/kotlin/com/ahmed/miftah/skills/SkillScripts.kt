package com.ahmed.miftah.skills

import java.io.File

/**
 * Stage 3 script model for skills. A skill may bundle Python scripts under
 * its `scripts/` folder; only `.py` files there are runnable, and only via
 * the approval-gated `run_skill_script` tool.
 *
 * All functions here are pure `java.io.File` logic (no Android APIs) so the
 * path rules are JVM-unit-tested: a script path can NEVER escape its own
 * skill's `scripts/` directory.
 */
object SkillScripts {
    const val SCRIPTS_DIR = "scripts"
    const val MAX_OUTPUT_CHARS = 8192

    fun isPythonScript(path: String): Boolean =
        path.replace('\\', '/').lowercase().endsWith(".py")

    /**
     * allowed-tools enforcement for script runs: a skill that declares no
     * allowed-tools may run its scripts; a skill that declares them may run
     * scripts only when `run_skill_script` is listed.
     */
    fun canRunScript(allowedTools: List<String>): Boolean =
        allowedTools.isEmpty() || "run_skill_script" in allowedTools

    /** Relative paths of runnable scripts (Python files under `scripts/`, incl. subfolders), sorted. */
    fun listScripts(skillDir: File): List<String> {
        val scriptsRoot = File(skillDir, SCRIPTS_DIR)
        if (!scriptsRoot.isDirectory) return emptyList()
        return scriptsRoot.walkTopDown().filter { it.isFile && isPythonScript(it.name) }
            .map { it.relativeTo(skillDir).path.replace(File.separatorChar, '/') }
            .sorted().toList()
    }

    /** Every bundled script-type file anywhere in the skill (review screen listing). */
    fun listAllScriptFiles(skillDir: File): List<String> {
        if (!skillDir.isDirectory) return emptyList()
        return skillDir.walkTopDown().filter { it.isFile && SkillParser.isScriptFile(it.name) }
            .map { it.relativeTo(skillDir).path.replace(File.separatorChar, '/') }
            .sorted().toList()
    }

    /**
     * Resolves a model-supplied script path to a real file, or null when the
     * path is not a runnable script of THIS skill. Rejects: absolute paths,
     * backslash tricks, `..` segments, paths outside `scripts/`, non-.py
     * files, missing files, and anything whose canonical path escapes the
     * skill's scripts root (symlink-safe final check).
     */
    fun resolveScript(skillDir: File, relativePath: String): File? {
        val normalized = relativePath.trim().replace('\\', '/')
        if (normalized.isEmpty() || normalized.startsWith("/")) return null
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." }) return null
        if (segments.first() != SCRIPTS_DIR) return null
        if (!isPythonScript(normalized)) return null
        val scriptsRoot = File(skillDir, SCRIPTS_DIR)
        val target = File(skillDir, segments.joinToString("/"))
        val rootCanonical = scriptsRoot.canonicalPath
        val targetCanonical = target.canonicalPath
        if (targetCanonical != rootCanonical &&
            !targetCanonical.startsWith(rootCanonical + File.separator)
        ) return null
        return if (target.isFile) target else null
    }

    /**
     * Copies the skill's own files into its per-skill sandbox directory
     * (overwriting same-path files; unrelated files already in the sandbox
     * are kept, so script outputs persist between runs). The ORIGINAL skill
     * files are only read — a script can never modify its own skill through
     * the sandbox copy. Returns the sandbox dir.
     */
    fun prepareSandbox(skillDir: File, sandboxDir: File): File {
        sandboxDir.mkdirs()
        if (!skillDir.isDirectory) return sandboxDir
        skillDir.walkTopDown().filter { it.isFile }.forEach { src ->
            val dest = File(sandboxDir, src.relativeTo(skillDir).path)
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
        }
        return sandboxDir
    }

    /** Splits the tool's single `args` string into argv entries (plain whitespace split). */
    fun splitArgs(args: String?): List<String> =
        args?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+")) ?: emptyList()

    /** Truncates captured script output before it is returned to the model. */
    fun truncate(text: String, maxChars: Int = MAX_OUTPUT_CHARS): String =
        if (text.length <= maxChars) text
        else text.substring(0, maxChars) + "\n…[truncated ${text.length - maxChars} chars]"
}
