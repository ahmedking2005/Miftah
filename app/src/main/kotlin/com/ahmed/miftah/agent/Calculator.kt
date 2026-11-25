package com.ahmed.miftah.agent

/**
 * Tiny safe arithmetic evaluator (recursive descent) for the calculator tool:
 * + - * / % parentheses, decimals, unary minus. No eval, no code execution.
 * Pure Kotlin — unit-tested on the JVM.
 */
object Calculator {
    fun evaluate(expression: String): Double {
        val p = Parser(expression)
        val v = p.parseExpression()
        p.skipSpaces()
        if (!p.atEnd()) throw IllegalArgumentException("Unexpected trailing input at position ${p.pos}")
        return v
    }

    private class Parser(private val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length
        fun skipSpaces() {
            while (pos < s.length && s[pos] == ' ') pos++
        }

        fun parseExpression(): Double {
            var v = parseTerm()
            while (true) {
                skipSpaces()
                if (atEnd()) return v
                v = when (s[pos]) {
                    '+' -> { pos++; v + parseTerm() }
                    '-' -> { pos++; v - parseTerm() }
                    else -> return v
                }
            }
        }

        private fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                skipSpaces()
                if (atEnd()) return v
                v = when (s[pos]) {
                    '*' -> { pos++; v * parseFactor() }
                    '/' -> {
                        pos++
                        val d = parseFactor()
                        if (d == 0.0) throw ArithmeticException("Division by zero")
                        v / d
                    }
                    '%' -> { pos++; v % parseFactor() }
                    else -> return v
                }
            }
        }

        private fun parseFactor(): Double {
            skipSpaces()
            if (atEnd()) throw IllegalArgumentException("Unexpected end of expression")
            return when (s[pos]) {
                '(' -> {
                    pos++
                    val v = parseExpression()
                    skipSpaces()
                    if (atEnd() || s[pos] != ')') throw IllegalArgumentException("Missing closing parenthesis")
                    pos++
                    v
                }
                '-' -> { pos++; -parseFactor() }
                '+' -> { pos++; parseFactor() }
                else -> parseNumber()
            }
        }

        private fun parseNumber(): Double {
            skipSpaces()
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("Expected a number at position $pos")
            return s.substring(start, pos).toDouble()
        }
    }
}
