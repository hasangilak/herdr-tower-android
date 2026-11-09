package dev.herdertower.phone.compose

/** What a draft's file is, which decides its token. */
enum class Kind { Image, Video, File }

/**
 * Files in a draft are tokens in its text, as in the Mac panel (and Claude
 * Code): `[Image #1]`, `[Video #2]`, `[File #3: notes.pdf]` where you
 * attached them. Deleting the token drops the file; removing the file
 * deletes its token. The tower keeps the tokens and puts the files' paths
 * after the text, in token order (a `[File #N:` token counts by its number).
 */
object DraftTokens {
    fun token(n: Int, kind: Kind = Kind.Image, name: String = ""): String = when (kind) {
        Kind.Image -> "[Image #$n]"
        Kind.Video -> "[Video #$n]"
        Kind.File -> "[File #$n: ${name.replace("]", ")")}]"
    }

    private val TOKEN = Regex("""\[(?:Image|Video) #(\d+)]|\[File #(\d+): [^\]]*]""")

    private fun number(m: MatchResult) = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt()

    /** The token numbers in [text], in order. */
    fun numbers(text: String): List<Int> = TOKEN.findAll(text).map(::number).toList()

    /** Inserts [token] at [at] with a space on each side where needed; returns the text and where the cursor goes. */
    fun insert(text: String, at: Int, n: Int, kind: Kind = Kind.Image, name: String = ""): Pair<String, Int> {
        val i = at.coerceIn(0, text.length)
        val before = text.substring(0, i)
        val after = text.substring(i)
        val lead = if (before.isEmpty() || before.last().isWhitespace()) "" else " "
        val trail = if (after.isNotEmpty() && after.first().isWhitespace()) "" else " "
        val piece = lead + token(n, kind, name) + trail
        return before + piece + after to (before.length + piece.length)
    }

    /** [text] without the token numbered [n], and the space it leaves doubled there (the rest of the text keeps its spacing). */
    fun remove(text: String, n: Int): String {
        val out = StringBuilder()
        var i = 0
        for (m in TOKEN.findAll(text)) {
            if (number(m) != n) continue
            out.append(text, i, m.range.first)
            var j = m.range.last + 1
            val before = out.lastOrNull()
            val after = text.getOrNull(j)
            // One space stays between words; none at the start or end of a line.
            if (after == ' ' && (before == null || before == ' ' || before == '\n')) j++
            else if (before == ' ' && (after == null || after == '\n')) out.setLength(out.length - 1)
            i = j
        }
        out.append(text, i, text.length)
        return out.toString().let { if (it.isBlank()) "" else it }
    }

    /**
     * What stays in the box after [sent] went: the draft as it is [now], less
     * the [sent] text it started with and the tokens of the files that went
     * with it ([sentNumbers]). What was typed or attached meanwhile stays.
     */
    fun afterSend(sent: String, now: String, sentNumbers: Collection<Int>): String {
        var rest = if (now.startsWith(sent)) now.substring(sent.length) else now
        for (n in sentNumbers) rest = remove(rest, n)
        return rest.trim()
    }

    /** The next number for a new attachment: one past every number used in this message. */
    fun next(text: String, used: Collection<Int>): Int = ((numbers(text) + used).maxOrNull() ?: 0) + 1

    /** Renumbers tokens 1..n in order (the tower counts files in order), returning the text and old -> new numbers. */
    fun renumber(text: String): Pair<String, Map<Int, Int>> {
        val map = LinkedHashMap<Int, Int>()
        numbers(text).forEach { if (it !in map) map[it] = map.size + 1 }
        val out = TOKEN.replace(text) { m ->
            val old = number(m)
            m.value.replaceFirst("#$old", "#${map[old] ?: old}")
        }
        return out to map
    }
}
