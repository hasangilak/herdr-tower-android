package dev.herdertower.phone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The id this install pairs with: made once, kept, the form the tower takes. */
class InstallIdTest {
    @Test fun madeOnceAndKept() {
        val context = FakeContext()
        val id = Settings(context).installId
        assertEquals("16 random bytes, base64url without padding", 22, id.length)
        assertTrue(id, id.matches(Regex("[A-Za-z0-9_-]{8,64}")))
        // Read again (another start of the app): the same.
        assertEquals(id, Settings(context).installId)
        // Another install (cleared data): another.
        assertNotEquals(id, Settings(FakeContext()).installId)
    }
}
