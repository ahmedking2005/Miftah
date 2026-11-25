package com.ahmed.miftah.skills

data class ParsedSkill(
    val name: String,
    val description: String,
    val allowedTools: List<String> = emptyList(),
    val body: String
)

/**
 * Parser for SKILL.md files (Claude Code / Hermes / open agent-skills format).
 * Handles the YAML frontmatter subset those files actually use:
 *  - scalar keys: name, description (incl. quoted and folded/plain multi-word)
 *  - allowed-tools as inline list [a, b], comma string, or block list (- item)
 * Unknown frontmatter fields are ignored by design, so both ecosystems parse.
 * Pure Kotlin — unit-tested on the JVM.
 */
object SkillParser {

    fun parse(markdown: String, fallbackName: String): ParsedSkill {
        val text = markdown.replace("\r\n", "\n")
        if (!text.startsWith("---")) {
            return ParsedSkill(fallbackName, "", emptyList(), text.trim())
        }
        val end = text.indexOf("\n---", startIndex = 3)
        if (end < 0) return ParsedSkill(fallbackName, "", emptyList(), text.trim())
        val front = text.substring(3, end).trim('\n')
        val body = text.substring(end + 4).trim()

        var name = fallbackName
        var description = ""
        val allowed = ArrayList<String>()

        val lines = front.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon <= 0 || line.startsWith(" ") || line.startsWith("-")) {
                i++
                continue
            }
            val key = line.substring(0, colon).trim().lowercase()
            var value = line.substring(colon + 1).trim()
            when (key) {
                "name" -> if (value.isNotEmpty()) name = unquote(value)
                "description" -> {
                    // Support block scalars (| or >) and plain continuation lines.
                    if (value == "|" || value == ">" || value == "|-" || value == ">-") {
                        val sb = StringBuilder()
                        i++
                        while (i < lines.size && (lines[i].startsWith(" ") || lines[i].isBlank())) {
                            sb.append(lines[i].trim()).append(' ')
                            i++
                        }
                        description = sb.toString().trim()
                        continue
                    }
                    description = unquote(value)
                }
                "allowed-tools", "allowed_tools", "allowedtools" -> {
                    when {
                        value.startsWith("[") -> {
                            value.trim('[', ']').split(',')
                                .map { unquote(it.trim()) }.filter { it.isNotEmpty() }
                                .forEach { allowed.add(it) }
                        }
                        value.isNotEmpty() -> {
                            value.split(',').map { unquote(it.trim()) }
                                .filter { it.isNotEmpty() }.forEach { allowed.add(it) }
                        }
                        else -> {
                            i++
                            while (i < lines.size && lines[i].trimStart().startsWith("-")) {
                                allowed.add(unquote(lines[i].trimStart().removePrefix("-").trim()))
                                i++
                            }
                            continue
                        }
                    }
                }
            }
            i++
        }
        return ParsedSkill(name, description, allowed, body)
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        return if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '\'' && t.last() == '\''))) {
            t.substring(1, t.length - 1)
        } else t
    }

    /** True for files that must be flagged as scripts in the review screen. */
    fun isScriptFile(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".py") || lower.endsWith(".sh") || lower.endsWith(".bash") ||
            lower.endsWith(".js") || lower.endsWith(".rb") || lower.endsWith(".ps1")
    }
}
