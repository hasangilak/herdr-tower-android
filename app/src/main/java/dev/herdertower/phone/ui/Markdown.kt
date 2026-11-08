package dev.herdertower.phone.ui

/**
 * The little markdown the tower's typed answers use: **bold**, *italic*,
 * `code`, [links](https://…) and bare URLs, `-`/`*` bullets, numbered
 * lists, headings and fenced code. Unclosed markers stay as text.
 */
object Markdown {
    data class Run(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val url: String? = null,
    )

    sealed interface Block {
        data class Paragraph(val runs: List<Run>) : Block
        data class Heading(val runs: List<Run>) : Block
        data class Bullet(val runs: List<Run>, val depth: Int) : Block
        data class Numbered(val number: String, val runs: List<Run>, val depth: Int) : Block
        data class Code(val text: String) : Block
    }

    private val BULLET = Regex("""^(\s*)[-*•+]\s+(.*)$""")
    private val NUMBER = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
    private val HEADING = Regex("""^#{1,6}\s+(.*)$""")

    fun parse(text: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val para = mutableListOf<String>()
        fun flush() {
            if (para.isNotEmpty()) blocks += Block.Paragraph(inline(para.joinToString("\n")))
            para.clear()
        }
        val lines = text.replace("\r\n", "\n").split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("```")) {
                flush()
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) body += lines[i++]
                blocks += Block.Code(body.joinToString("\n"))
                i++
                continue
            }
            val b = BULLET.matchEntire(line)
            val n = NUMBER.matchEntire(line)
            val h = HEADING.matchEntire(line)
            when {
                line.isBlank() -> flush()
                b != null && b.groupValues[2].isNotBlank() -> {
                    flush()
                    blocks += Block.Bullet(inline(b.groupValues[2]), b.groupValues[1].length / 2)
                }
                n != null -> {
                    flush()
                    blocks += Block.Numbered(n.groupValues[2], inline(n.groupValues[3]), n.groupValues[1].length / 2)
                }
                h != null -> {
                    flush()
                    blocks += Block.Heading(inline(h.groupValues[1]))
                }
                else -> para += line
            }
            i++
        }
        flush()
        return blocks
    }

    fun inline(s: String): List<Run> {
        val out = mutableListOf<Run>()
        inline(s, false, false, out)
        // Merge neighbours with the same style.
        val merged = mutableListOf<Run>()
        for (r in out) {
            val last = merged.lastOrNull()
            if (last != null && last.copy(text = "") == r.copy(text = "")) merged[merged.lastIndex] = last.copy(text = last.text + r.text) else merged += r
        }
        return merged
    }

    private fun inline(s: String, bold: Boolean, italic: Boolean, out: MutableList<Run>) {
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) out += Run(buf.toString(), bold, italic)
            buf.setLength(0)
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            // `code`
            if (c == '`') {
                val close = s.indexOf('`', i + 1)
                if (close > i + 1) {
                    flush()
                    out += Run(s.substring(i + 1, close), bold, italic, code = true)
                    i = close + 1
                    continue
                }
            }
            // **bold** / __bold__
            if ((s.startsWith("**", i) || s.startsWith("__", i)) && i + 2 < s.length && !s[i + 2].isWhitespace()) {
                val mark = s.substring(i, i + 2)
                val close = s.indexOf(mark, i + 2)
                if (close > i + 2 && !s[close - 1].isWhitespace()) {
                    flush()
                    inline(s.substring(i + 2, close), true, italic, out)
                    i = close + 2
                    continue
                }
            }
            // *italic* / _italic_ (an underscore only at word edges, so snake_case stays)
            if ((c == '*' || (c == '_' && (i == 0 || !s[i - 1].isLetterOrDigit()))) && i + 1 < s.length && !s[i + 1].isWhitespace() && s[i + 1] != c) {
                var close = s.indexOf(c, i + 1)
                while (close > 0 && (s[close - 1].isWhitespace() || (c == '_' && close + 1 < s.length && s[close + 1].isLetterOrDigit()))) {
                    close = s.indexOf(c, close + 1)
                }
                if (close > i + 1) {
                    flush()
                    inline(s.substring(i + 1, close), bold, true, out)
                    i = close + 1
                    continue
                }
            }
            // [text](url)
            if (c == '[') {
                val mid = s.indexOf("](", i + 1)
                val end = if (mid > 0) s.indexOf(')', mid + 2) else -1
                if (mid > i + 1 && end > mid + 2) {
                    flush()
                    out += Run(s.substring(i + 1, mid), bold, italic, url = s.substring(mid + 2, end).trim())
                    i = end + 1
                    continue
                }
            }
            // bare http(s) URLs
            if ((s.startsWith("https://", i) || s.startsWith("http://", i)) && (i == 0 || !s[i - 1].isLetterOrDigit())) {
                var end = i
                while (end < s.length && !s[end].isWhitespace()) end++
                while (end > i && s[end - 1] in ".,;:!?)'\"") end--
                flush()
                val url = s.substring(i, end)
                out += Run(url, bold, italic, url = url)
                i = end
                continue
            }
            buf.append(c)
            i++
        }
        flush()
    }
}
