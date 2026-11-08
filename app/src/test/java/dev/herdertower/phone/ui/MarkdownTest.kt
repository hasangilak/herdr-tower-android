package dev.herdertower.phone.ui

import dev.herdertower.phone.ui.Markdown.Block
import dev.herdertower.phone.ui.Markdown.Run
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test fun boldItalicCodeAndLinks() {
        assertEquals(
            listOf(Run("api is "), Run("blocked", bold = true), Run(", run "), Run("tower ls", code = true), Run(" or see "), Run("docs", url = "https://x.dev/a"), Run(".")),
            Markdown.inline("api is **blocked**, run `tower ls` or see [docs](https://x.dev/a)."),
        )
        assertEquals(listOf(Run("a "), Run("soft", italic = true), Run(" word")), Markdown.inline("a *soft* word"))
        assertEquals(listOf(Run("very ", bold = true), Run("much", bold = true, italic = true)), Markdown.inline("**very _much_**"))
    }

    @Test fun bareUrlsAndSnakeCaseAndStrayMarkers() {
        assertEquals(listOf(Run("see "), Run("https://example.com/x", url = "https://example.com/x"), Run(".")), Markdown.inline("see https://example.com/x."))
        assertEquals(listOf(Run("my_var_name stays")), Markdown.inline("my_var_name stays"))
        assertEquals(listOf(Run("2 * 3 and **open")), Markdown.inline("2 * 3 and **open"))
    }

    @Test fun listsHeadingsAndCode() {
        val md = """
            ## Status
            Three sessions:
            - **api**: blocked
            - web: working
              - building
            1. Approve api
            2) Wait

            ```
            npm run migrate
            ```
        """.trimIndent()
        assertEquals(
            listOf(
                Block.Heading(listOf(Run("Status"))),
                Block.Paragraph(listOf(Run("Three sessions:"))),
                Block.Bullet(listOf(Run("api", bold = true), Run(": blocked")), 0),
                Block.Bullet(listOf(Run("web: working")), 0),
                Block.Bullet(listOf(Run("building")), 1),
                Block.Numbered("1", listOf(Run("Approve api")), 0),
                Block.Numbered("2", listOf(Run("Wait")), 0),
                Block.Code("npm run migrate"),
            ),
            Markdown.parse(md),
        )
    }

    @Test fun plainTextIsOneParagraph() {
        assertEquals(listOf(Block.Paragraph(listOf(Run("Nothing is blocked.\nAll good.")))), Markdown.parse("Nothing is blocked.\nAll good."))
    }
}
