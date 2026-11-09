package dev.herdertower.phone.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationFollowTest {
    @Test fun wordsGoAfterWhatWasThere() {
        val f = DictationFollow()
        val g = f.begin("Look at")
        assertEquals("Look at this", f.said(g, "this"))
        assertEquals("Look at this one", f.said(g, "this one"))
        assertEquals("only", f.said(f.begin(""), "only"))
    }

    @Test fun anotherEngineShowingNotRunningFirstDoesntEndIt() {
        // Switching engines (tower to phone) shows the new one as not running before it starts: the words still land.
        val f = DictationFollow()
        val g = f.begin("hi")
        f.idle()
        f.live()
        assertEquals("hi there", f.said(g, "there"))
        f.idle() // now it's done: the box is the user's
        assertNull(f.said(g, "late"))
    }

    @Test fun itsOwnWordsAreNotAnEdit() {
        val f = DictationFollow()
        val g = f.begin("a")
        assertFalse(f.edited("a"))
        val out = f.said(g, "b")!!
        assertFalse(f.edited(out))
    }

    @Test fun typingOrAttachingEndsIt() {
        val f = DictationFollow()
        val g = f.begin("")
        f.live()
        val out = f.said(g, "what is")!!
        assertTrue(f.edited("$out [Image #1] "))
        assertNull(f.said(g, "what is this")) // the picture's token stays
        assertFalse(f.edited("anything")) // nothing to follow any more
    }

    @Test fun sentOrClearedDropsLateWords() {
        val f = DictationFollow()
        val g = f.begin("")
        f.live()
        f.said(g, "send this")
        f.reset()
        assertNull(f.said(g, "send this now"))
        val g2 = f.begin("")
        assertNull(f.said(g, "old"))
        assertEquals("new", f.said(g2, "new"))
    }
}
