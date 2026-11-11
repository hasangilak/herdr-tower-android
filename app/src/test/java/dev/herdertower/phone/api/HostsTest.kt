package dev.herdertower.phone.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tower's addresses over the LAN and Tailscale (herder-tower docs/mobile-api.md, "Pairing flow"). */
class HostsTest {
    private val fp = "5d8e798d".repeat(8)
    private val lan = TowerHost("lan", "192.168.1.20", 7843)
    private val ts = TowerHost("tailscale", "100.68.110.33", 7843, "mac.tailnet.ts.net")

    // The QR

    @Test fun theNewLinkCarriesEveryAddressInOrderAndTheMagicDnsName() {
        val l = PairLink.parse(
            "herdertower://pair?h=192.168.1.20&p=7843&c=12345678&fp=$fp&hosts=lan:192.168.1.20:7843,tailscale:100.68.110.33:7843&dns=mac.tailnet.ts.net",
        )
        assertEquals("192.168.1.20", l.host)
        assertEquals(7843, l.port)
        assertEquals(listOf(lan, ts), l.hosts)
        assertEquals("mac.tailnet.ts.net", l.magicDns)
    }

    @Test fun anOldLinkIsItsOneAddress() {
        val l = PairLink.parse("herdertower://pair?h=192.168.1.20&p=7843&c=12345678&fp=$fp")
        assertEquals(listOf(lan.copy(dnsName = null)), l.hosts)
        assertNull(l.magicDns)
        // And equal to what an older app made of it.
        assertEquals(PairLink("192.168.1.20", 7843, "12345678", fp), l)
    }

    @Test fun aLinkWithOnlyTheTailnetAddress() {
        // Wi-Fi off on the Mac, Tailscale up: one address, the tailnet's.
        val l = PairLink.parse("herdertower://pair?h=100.68.110.33&p=7843&c=12345678&fp=$fp&hosts=tailscale:100.68.110.33:7843&dns=mac.tailnet.ts.net")
        assertEquals(listOf(ts), l.hosts)
        // An old-style link to a tailnet IP: still known as Tailscale.
        assertEquals("tailscale", PairLink.parse("herdertower://pair?h=100.68.110.33&c=12345678&fp=$fp").hosts.single().kind)
    }

    @Test fun unknownKindsAndBrokenEntriesAreSkippedAndAnUnreadableListFallsBack() {
        val l = PairLink.parse(
            "herdertower://pair?h=192.168.1.20&c=12345678&fp=$fp&hosts=" +
                java.net.URLEncoder.encode("wireguard:10.0.0.1:7843,lan:192.168.1.20:7843,lan:bad host:1,tailscale:100.68.110.33:99999,tailscale:100.68.110.33:7843", "UTF-8"),
        )
        assertEquals(listOf("192.168.1.20", "100.68.110.33"), l.hosts.map { it.host })
        val nothing = PairLink.parse("herdertower://pair?h=192.168.1.20&p=7843&c=12345678&fp=$fp&hosts=junk")
        assertEquals(listOf(TowerHost("lan", "192.168.1.20", 7843)), nothing.hosts)
    }

    @Test fun theLinkSurvivesARoundTrip() {
        val l = PairLink("192.168.1.20", 7843, "12345678", fp, listOf(lan, ts), "mac.tailnet.ts.net")
        assertEquals(l, PairLink.parse(l.toLink()))
        // An old one stays an old one.
        val old = PairLink("192.168.1.20", 7843, "12345678", fp)
        assertFalse("hosts" in old.toLink())
        assertEquals(old, PairLink.parse(old.toLink()))
    }

    // Which addresses are Tailscale's

    @Test fun theTailnetRange() {
        assertTrue(Hosts.isTailnet("100.64.0.1"))
        assertTrue(Hosts.isTailnet("100.127.255.254"))
        assertTrue(Hosts.isTailnet("mac.tailnet.ts.net"))
        assertTrue(Hosts.isTailnet("MAC.TAILNET.TS.NET."))
        assertFalse(Hosts.isTailnet("100.63.255.255"))
        assertFalse(Hosts.isTailnet("100.128.0.1"))
        assertFalse(Hosts.isTailnet("192.168.1.20"))
        assertFalse(Hosts.isTailnet("mac.local"))
    }

    // The order to try them

    @Test fun theLanFirstThenTailscaleThenItsName() {
        assertEquals(
            listOf("192.168.1.20", "100.68.110.33", "mac.tailnet.ts.net"),
            Hosts.candidates(listOf(lan, ts), "mac.tailnet.ts.net", null).map { it.host },
        )
    }

    @Test fun theRouteThatWorkedLastComesRightAfterTheLan() {
        // Last time only the MagicDNS name answered (the tailnet IP had changed): it's tried before the old IP.
        assertEquals(
            listOf("192.168.1.20", "mac.tailnet.ts.net", "100.68.110.33"),
            Hosts.candidates(listOf(lan, ts), "mac.tailnet.ts.net", "mac.tailnet.ts.net:7843").map { it.host },
        )
        // The LAN worked last: it's first anyway.
        assertEquals("192.168.1.20", Hosts.candidates(listOf(lan, ts), null, "192.168.1.20:7843").first().host)
        // A route that's no longer listed is ignored.
        assertEquals(
            listOf("192.168.1.20", "100.68.110.33", "mac.tailnet.ts.net"),
            Hosts.candidates(listOf(lan, ts), null, "10.9.9.9:7843").map { it.host },
        )
    }

    @Test fun theMagicDnsNameStandsInWhenTheEntryHasNone() {
        val bare = ts.copy(dnsName = null)
        assertEquals(listOf("100.68.110.33", "mac.tailnet.ts.net"), Hosts.candidates(listOf(bare), "mac.tailnet.ts.net", null).map { it.host })
        assertEquals(listOf("100.68.110.33"), Hosts.candidates(listOf(bare), null, null).map { it.host })
        // Paired over the LAN only, but the Mac's name is known: it's tried after the LAN.
        assertEquals(listOf("192.168.1.20", "mac.tailnet.ts.net"), Hosts.candidates(listOf(lan), "mac.tailnet.ts.net", null).map { it.host })
        assertTrue(Hosts.candidates(listOf(lan), "mac.tailnet.ts.net", null).last().tailscale)
    }

    @Test fun anOldPairingIsOneAddress() {
        assertEquals(listOf(lan), Hosts.candidates(listOf(lan), null, null))
    }

    // Keeping the list current

    private fun ids(list: List<TowerHost>) = list.map { it.id }

    @Test fun theTowersCurrentListIsMergedIn() {
        val moved = TowerHost("lan", "192.168.1.44", 7843)
        // The Mac moved to another address on the same network: that network's old one goes.
        assertEquals(ids(listOf(moved, ts)), ids(Hosts.refresh(listOf(lan, ts), listOf(moved, ts))))
        // Tailscale started on the Mac later: learnt.
        assertEquals(ids(listOf(lan, ts)), ids(Hosts.refresh(listOf(lan), listOf(lan, ts))))
        // Tailscale stopped there: the tailnet address is kept (after the LAN) for when it's back.
        assertEquals(ids(listOf(lan, ts)), ids(Hosts.refresh(listOf(lan, ts), listOf(lan))))
        // An older tower (no lan_hosts), or the API's list empty: what's stored stays.
        assertEquals(listOf(lan, ts), Hosts.refresh(listOf(lan, ts), emptyList()))
        // The Mac lists only its tailnet address (Wi-Fi off there, or an exit node): the LAN address is kept,
        // or the phone could never find the Mac at home again without pairing anew.
        assertEquals(ids(listOf(lan, ts)), ids(Hosts.refresh(listOf(lan, ts), listOf(ts))))
        // Kinds this app doesn't know are left out.
        assertEquals(ids(listOf(lan)), ids(Hosts.refresh(listOf(lan), listOf(lan, TowerHost("wireguard", "10.0.0.1", 7843)))))
    }

    @Test fun eachNetworksAddressIsRememberedAndNamed() {
        val home = TowerHost("lan", "192.168.2.211", 7843)
        val office = TowerHost("lan", "10.10.30.116", 7843)
        // Over Tailscale at the office, the hosts event says the Mac's new LAN address: learnt before it's needed,
        // the home one kept (the Mac goes home again).
        val merged = Hosts.refresh(listOf(home, ts), listOf(office, ts), TowerNetwork("10.10.30", "Office", NetworkLan("10.10.30.116", 7843)))
        assertEquals(listOf("10.10.30.116:7843", "192.168.2.211:7843", "100.68.110.33:7843"), ids(merged))
        assertEquals("Office", merged[0].seenName)
        assertEquals("10.10.30", merged[0].range)
        assertEquals("192.168.2", merged[1].range)
        assertTrue(Hosts.describe(merged).startsWith("Office 10.10.30.116 · Wi-Fi 192.168.2.211"))
        // Home again: its address first, the office's kept, the name it was given there too.
        val back = Hosts.refresh(merged, listOf(home, ts), TowerNetwork("192.168.2", "Home", null))
        assertEquals(listOf("192.168.2.211:7843", "10.10.30.116:7843", "100.68.110.33:7843"), ids(back))
        assertEquals("Office", back[1].seenName)
        assertEquals("Home", back[0].seenName)
        // At most eight networks, the latest first.
        val many = (1..10).fold(listOf(ts)) { acc, i -> Hosts.refresh(acc, listOf(TowerHost("lan", "10.0.$i.5", 7843), ts)) }
        assertEquals((10 downTo 3).map { "10.0.$it.5" }, many.filter { !it.tailscale }.map { it.host })
        assertTrue("Tailscale is never dropped", many.any { it.tailscale })
    }

    @Test fun whenEachLastAnsweredSurvivesAFreshList() {
        val stored = listOf(TowerHost("lan", "10.10.30.116", 7843, lastOkMs = 5), ts.copy(lastOkMs = 7))
        val merged = Hosts.refresh(stored, listOf(TowerHost("lan", "10.10.30.116", 7843), ts))
        assertEquals(listOf(5L, 7L), merged.map { it.lastOkMs })
    }

    @Test fun thisNetworksAddressIsTriedFirstAndAnothersAfterTailscale() {
        val home = TowerHost("lan", "192.168.2.211", 7843, seenRange = "192.168.2")
        val office = TowerHost("lan", "10.10.30.116", 7843, seenRange = "10.10.30")
        val stored = listOf(home, office, ts)
        assertEquals(listOf("10.10.30.116", "100.68.110.33", "mac.tailnet.ts.net", "192.168.2.211"), Hosts.candidates(stored, null, null, setOf("10.10.30")).map { it.host })
        // On cellular (no Wi-Fi range known): every LAN address first, as before.
        assertEquals(listOf("192.168.2.211", "10.10.30.116"), Hosts.candidates(stored, null, null, emptySet()).take(2).map { it.host })
        assertEquals("10.10.30", Hosts.rangeOf("10.10.30.116"))
        assertEquals(null, Hosts.rangeOf("mac.tailnet.ts.net"))
    }

    @Test fun theStatesListReads() {
        val s = TowerJson.decodeFromString(
            TowerState.serializer(),
            """{"lan":"https://192.168.1.20:7843","lan_hosts":[{"kind":"lan","host":"192.168.1.20","port":7843},{"kind":"tailscale","host":"100.68.110.33","port":7843,"dns_name":"mac.tailnet.ts.net"}],
               "tailscale":{"installed":true,"running":true,"state":"running","ip":"100.68.110.33","dns_name":"mac.tailnet.ts.net"}}""",
        )
        assertEquals(listOf(lan, ts), s.lanHosts)
        assertEquals("mac.tailnet.ts.net", s.tailscale?.dnsName)
        assertTrue(s.tailscale!!.running)
        // An older tower: neither.
        val old = TowerJson.decodeFromString(TowerState.serializer(), """{"lan":"https://192.168.1.20:7843"}""")
        assertTrue(old.lanHosts.isEmpty())
        assertNull(old.tailscale)
    }

    @Test fun theAddressesInWords() {
        assertEquals("Wi-Fi 192.168.1.20 · Tailscale 100.68.110.33 (mac.tailnet.ts.net)", Hosts.describe(listOf(lan, ts)))
        assertEquals("Wi-Fi 192.168.1.20:9000", Hosts.describe(listOf(lan.copy(port = 9000))))
        assertEquals("Tailscale", ActiveRoute(ts).label)
        assertEquals("Wi-Fi", ActiveRoute(lan).label)
    }
}
