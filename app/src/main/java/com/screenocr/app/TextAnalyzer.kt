package com.screenocr.app

import java.text.Normalizer

/** Post-processing and analysis of raw OCR output. */
object TextAnalyzer {

    data class Stats(val words: Int, val chars: Int, val lines: Int, val script: String)

    private val MULTI_SPACE = Regex("[ \\t]{2,}")
    private val MANY_NEWLINES = Regex("\n{3,}")
    private val PARAGRAPH_BREAK = Regex("\n\\s*\n")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Cleans raw Tesseract output: unifies Unicode forms (Arabic presentation forms
     * become normal letters, so the text is searchable/copyable), strips junk
     * control characters and collapses stray whitespace. Zero-width non-joiner
     * (U+200C) is kept because Urdu spelling depends on it.
     */
    fun clean(raw: String): String {
        var t = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        t = t.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u000C', '\n')
            .replace(' ', ' ')
            .replace("​", "")
        t = t.lines().joinToString("\n") { it.trim().replace(MULTI_SPACE, " ") }
        t = t.replace(MANY_NEWLINES, "\n\n")
        return t.trim()
    }

    /** Joins hard-wrapped lines back into paragraphs. */
    fun reflow(text: String): String =
        text.split(PARAGRAPH_BREAK).joinToString("\n\n") { p ->
            p.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        }.trim()

    fun stats(text: String): Stats {
        var arabic = 0
        var latin = 0
        for (c in text) {
            val code = c.code
            if (code in 0x0600..0x06FF || code in 0x0750..0x077F ||
                code in 0xFB50..0xFDFF || code in 0xFE70..0xFEFF
            ) {
                arabic++
            } else if (c in 'A'..'Z' || c in 'a'..'z') {
                latin++
            }
        }
        val total = arabic + latin
        val script = when {
            total == 0 -> "-"
            arabic * 100 / total >= 85 -> "Urdu"
            latin * 100 / total >= 85 -> "English"
            else -> "Urdu + English"
        }
        val trimmed = text.trim()
        val words = if (trimmed.isEmpty()) 0 else trimmed.split(WHITESPACE).count { it.isNotEmpty() }
        val lines = text.lines().count { it.isNotBlank() }
        return Stats(words, text.length, lines, script)
    }
}
