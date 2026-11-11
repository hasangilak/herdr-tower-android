package dev.herdertower.phone.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which Bonjour advertisement is this phone's tower: v=1 and the pinned fingerprint, nothing else. */
class BonjourTxtTest {
    private val fp = "5d8e798db65c5ac4638e7c3bf688e84d1baffc5852a58ef5d227f6728da7a7b6"
    private fun txt(vararg kv: Pair<String, String?>) = kv.associate { (k, v) -> k to v?.toByteArray() }

    @Test fun theTowersOwnAdvertIsTaken() {
        // As the Mac advertises it: v=1, fp, name.
        assertTrue(BonjourTxt.accepts(txt("v" to "1", "fp" to fp, "name" to "Hassan’s MacBook Pro"), fp))
        // The pin as stored with colons and in capitals still matches.
        assertTrue(BonjourTxt.accepts(txt("v" to "1", "fp" to fp.uppercase()), fp.chunked(2).joinToString(":")))
    }

    @Test fun anythingElseIsNot() {
        // Another Mac's tower on the same network.
        assertFalse(BonjourTxt.accepts(txt("v" to "1", "fp" to "0".repeat(64)), fp))
        // Another version of the advert, or none.
        assertFalse(BonjourTxt.accepts(txt("v" to "2", "fp" to fp), fp))
        assertFalse(BonjourTxt.accepts(txt("fp" to fp), fp))
        // No fingerprint, or not one.
        assertFalse(BonjourTxt.accepts(txt("v" to "1"), fp))
        assertFalse(BonjourTxt.accepts(txt("v" to "1", "fp" to "abc"), fp))
        assertFalse(BonjourTxt.accepts(txt("v" to "1", "fp" to null), fp))
    }
}
