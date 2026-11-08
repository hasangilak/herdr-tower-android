package dev.herdertower.phone.compose

/**
 * Attachments in a draft are tokens in its text, as in the Mac panel (and
 * Claude Code): `[Image #1]` where you attached it. Deleting the token drops
 * the picture; removing the picture deletes its token. The tower keeps the
 * markers and puts the files' paths after the text, in token order.
 */
object DraftTokens {
    fun token(n: Int) = "[Image #$n]"

    private val TOKEN = Regex("""\[Image #(\d+)]""")

    /** The token numbers in [text], in order. */
    fun numbers(text: String): List<Int> = TOKEN.findAll(text).map { it.groupValues[1].toInt() }.toList()

    /** Inserts [token] at [at] with a space on each side where needed; returns the text and where the cursor goes. */
    fun insert(text: String, at: Int, n: Int): Pair<String, Int> {
        val i = at.coerceIn(0, text.length)
        val before = text.substring(0, i)
        val after = text.substring(i)
        val lead = if (before.isEmpty() || before.last().isWhitespace()) "" else " "
        val trail = if (after.isNotEmpty() && after.first().isWhitespace()) "" else " "
        val piece = lead + token(n) + trail
        return before + piece + after to (before.length + piece.length)
    }

    /** [text] without token [n] (and the space it leaves doubled). */
    fun remove(text: String, n: Int): String =
        text.replace(token(n), "").replace(Regex("""[ \t]{2,}"""), " ").replace(Regex("""^ | $"""), "").let { if (it.isBlank()) "" else it }

    /** The next number for a new attachment: one past every number used in this message. */
    fun next(text: String, used: Collection<Int>): Int = ((numbers(text) + used).maxOrNull() ?: 0) + 1

    /** Renumbers tokens 1..n in order (the tower's markers count files in order), returning the text and old -> new numbers. */
    fun renumber(text: String): Pair<String, Map<Int, Int>> {
        val map = LinkedHashMap<Int, Int>()
        numbers(text).forEach { if (it !in map) map[it] = map.size + 1 }
        val out = TOKEN.replace(text) { m -> token(map[m.groupValues[1].toInt()] ?: m.groupValues[1].toInt()) }
        return out to map
    }
}
