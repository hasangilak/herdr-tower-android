package dev.herdertower.phone.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
    private val track = """{"space": "shop", "summary": "prod 14:20 · 1 on dev", "checked_ms": 1700,
      "repos": [{"repo": "eyzhub/shop-api", "dev": "dev", "prod": "main",
        "lines": ["On main since 14:20: #829, #830", "On dev, not on main: #831", "Open: #832"],
        "on_prod": [{"since_ms": 1500, "prs": [829, 830]}], "on_dev_not_prod": [831],
        "changes": [{"number": 829, "title": "Totals", "url": "https://github.com/eyzhub/shop-api/pull/829", "base": "dev", "stage": "released",
                     "merged_ms": 1000, "deployed_ms": 1100, "released_ms": 1500, "release": 840, "skipped_dev": false, "stage_line": "on main since 14:20 (release #840)"},
                    {"number": 832, "title": "Refunds", "url": "u", "base": "dev", "stage": "open"}],
        "releases": [{"number": 840, "title": "Release", "url": "r", "head": "dev", "base": "main", "state": "merged", "merged_ms": 1500, "carried": [829, 830]}],
        "deployed": {"sha": "abc", "at_ms": 1100, "environment": "dev"}}]}"""

    @Test fun eachSpacesTrackFromTheState() {
        val s = TowerJson.decodeFromString(TowerState.serializer(), """{"version": 3, "releases": {"shop": $track, "odd": {"repos": 7}, "none": null}}""")
        val tracks = ReleasesApi.parse(s.releases)
        // One the app can't read is left out; the others and the state still read.
        assertEquals(setOf("shop"), tracks.keys)
        val t = tracks["shop"]!!
        assertEquals("prod 14:20 · 1 on dev", t.summary)
        assertEquals(1700L, t.checkedMs)
        val r = t.repos[0]
        assertEquals("main", r.prod)
        assertEquals(listOf(OnProd(1500, listOf(829, 830))), r.onProd)
        assertEquals(listOf(831L), r.onDevNotProd)
        assertEquals(840L, r.changes[0].release)
        assertEquals("on main since 14:20 (release #840)", r.changes[0].stageLine)
        assertNull(r.changes[1].mergedMs)
        assertEquals(listOf(829L, 830L), r.releases[0].carried)
        assertEquals("dev", r.deployed!!.environment)
        // The space's name matched without regard to case; none for a space without a track, or a tower before releases.
        assertEquals(t, ReleasesApi.of(tracks, "Shop"))
        assertNull(ReleasesApi.of(tracks, "other"))
        assertTrue(ReleasesApi.parse(TowerJson.decodeFromString(TowerState.serializer(), """{"version": 3}""").releases).isEmpty())
    }
}
