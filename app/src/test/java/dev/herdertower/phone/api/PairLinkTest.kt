package dev.herdertower.phone.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PairLinkTest {
    private val fp = "3fa9c2e1".repeat(8)

    @Test fun parsesTheQrLink() {
        val l = PairLink.parse("herdertower://pair?h=192.168.1.20&p=7843&c=48213907&fp=$fp")
        assertEquals(PairLink("192.168.1.20", 7843, "48213907", fp), l)
    }

    @Test fun toleratesOrderCaseWhitespaceAndColonFingerprints() {
        val colons = fp.uppercase().chunked(2).joinToString(":")
        val l = PairLink.parse("  HerderTower://pair/?fp=$colons&c=48213907&h=10.0.0.5&p=9000\n")
        assertEquals(PairLink("10.0.0.5", 9000, "48213907", fp), l)
    }

    @Test fun portDefaultsTo7843() {
        assertEquals(7843, PairLink.parse("herdertower://pair?h=10.0.0.5&c=12345678&fp=$fp").port)
    }

    @Test fun decodesPercentEncodingAndIpv6() {
        val l = PairLink.parse("herdertower://pair?h=%5Bfe80%3A%3A1%5D&p=7843&c=12345678&fp=$fp")
        assertEquals("fe80::1", l.host)
    }

    @Test fun rejectsWhatIsntAPairingLink() {
        fun bad(s: String) = assertThrows(PairLinkException::class.java) { PairLink.parse(s) }
        bad("https://example.com/?h=1&c=12345678&fp=$fp")
        bad("herdertower://other?h=1.2.3.4&c=12345678&fp=$fp")
        bad("herdertower://pair")
        bad("herdertower://pair?c=12345678&fp=$fp") // no host
        bad("herdertower://pair?h=1.2.3.4&c=1234567&fp=$fp") // 7 digits
        bad("herdertower://pair?h=1.2.3.4&c=1234567a&fp=$fp")
        bad("herdertower://pair?h=1.2.3.4&c=12345678&fp=abc") // short fingerprint
        bad("herdertower://pair?h=1.2.3.4&c=12345678&fp=${"z".repeat(64)}")
        bad("herdertower://pair?h=1.2.3.4&p=70000&c=12345678&fp=$fp")
        bad("herdertower://pair?h=1.2.3.4/evil&c=12345678&fp=$fp")
    }

    @Test fun manualCodeEntryTakesSpacesAndDashes() {
        assertEquals("48213907", PairLink.validCode("4821 3907"))
        assertEquals("48213907", PairLink.validCode("4821-3907"))
        assertNull(PairLink.validCode("4821x3907"))
        assertNull(PairLink.validCode("482139"))
    }

    @Test fun fingerprintsGroupAndCompare() {
        assertEquals("3fa9 c2e1 3fa9", Fingerprint.grouped("3fa9c2e13fa9"))
        assertTrue(Fingerprint.matches(fp, fp.uppercase()))
        assertEquals(fp, Fingerprint.normalize(Fingerprint.grouped(fp)))
        // SHA-256 of "abc"
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Fingerprint.of("abc".toByteArray()))
    }

    @Test fun aLinkSurvivesBeingWrittenBack() {
        // Kept across a recreate as a link: IPv6, odd ports and the code come back the same.
        val fp = "3fa9c2e1".repeat(8)
        listOf(PairLink("192.168.1.20", 7843, "48213907", fp), PairLink("fe80::1%wlan0", 8443, "00012345", fp)).forEach {
            assertEquals(it, PairLink.parse(it.toLink()))
        }
    }
}
