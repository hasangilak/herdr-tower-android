package dev.herdertower.phone.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountHueTest {
    // Reference values from the orb panel's `account_hue` (FNV-1a, Rust).
    @Test fun matchesTheMacPanel() {
        assertEquals(0x6ea8bf73u, AccountHue.fnv1a("eyz"))
        assertEquals(0xc8f06eL, AccountHue.of("eyz"))
        assertEquals(0xff9fb2L, AccountHue.of("Personal"))
        assertEquals(0x6eeaa8L, AccountHue.of("work"))
        assertEquals(0x7fe8e0L, AccountHue.of("Ölaf"))
    }

    @Test fun caseDoesNotChangeTheColour() {
        assertEquals(AccountHue.of("side"), AccountHue.of("Side"))
    }
}
