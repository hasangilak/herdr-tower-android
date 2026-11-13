package dev.herdertower.phone.ui

import dev.herdertower.phone.api.TrackChange
import dev.herdertower.phone.api.TrackRelease
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReleaseModelTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(day: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private val now = at(13, 15, 0)

    @Test fun timesInThePhonesOwnLocalTime() {
        assertEquals("14:20", ReleaseModel.whenText(at(13, 14, 20), now, zone))
        assertEquals("Nov 12 09:05", ReleaseModel.whenText(at(12, 9, 5), now, zone))
        assertEquals("00:01", ReleaseModel.whenText(at(13, 0, 1), now, zone))
        assertEquals("from GitHub, 14:58", ReleaseModel.checked(at(13, 14, 58), now, zone))
    }

    @Test fun eachChangesChipAndWhatItSaysBesideIt() {
        assertEquals(listOf("open", "merged into dev", "on dev", "on main", "closed"),
            listOf("open", "merged", "deployed", "released", "closed").map { ReleaseModel.stageWord(it, "dev", "main") })
        assertEquals(listOf(ReleaseModel.Tone.Quiet, ReleaseModel.Tone.Dev, ReleaseModel.Tone.Dev, ReleaseModel.Tone.Prod, ReleaseModel.Tone.Quiet),
            listOf("open", "merged", "deployed", "released", "closed").map { ReleaseModel.stageTone(it) })
        val c = TrackChange(number = 829, base = "dev")
        assertEquals("into dev", ReleaseModel.detail(c.copy(stage = "open"), now, zone))
        assertEquals("merged 14:20, not deployed yet", ReleaseModel.detail(c.copy(stage = "merged", mergedMs = at(13, 14, 20)), now, zone))
        assertEquals("merged, not deployed yet", ReleaseModel.detail(c.copy(stage = "merged"), now, zone))
        assertEquals("deployed Nov 12 16:40", ReleaseModel.detail(c.copy(stage = "deployed", deployedMs = at(12, 16, 40)), now, zone))
        assertEquals("since 14:20 · release #840", ReleaseModel.detail(c.copy(stage = "released", releasedMs = at(13, 14, 20), release = 840), now, zone))
        assertEquals("since 14:20", ReleaseModel.detail(c.copy(stage = "released", releasedMs = at(13, 14, 20)), now, zone))
        assertEquals("since 14:20 · skipped dev", ReleaseModel.detail(c.copy(stage = "released", releasedMs = at(13, 14, 20), skippedDev = true, release = 840), now, zone))
        assertEquals("not merged", ReleaseModel.detail(c.copy(stage = "closed"), now, zone))
    }

    @Test fun theLinesAndTheReleases() {
        assertEquals(ReleaseModel.Tone.Prod, ReleaseModel.lineTone("On main since 14:20: #829, #830", "dev", "main"))
        assertEquals(ReleaseModel.Tone.Dev, ReleaseModel.lineTone("On dev, not on main: #831", "dev", "main"))
        assertEquals(ReleaseModel.Tone.Rule, ReleaseModel.lineTone("#850 went to main without dev first, against the dev-then-prod rule", "dev", "main"))
        assertEquals(ReleaseModel.Tone.Quiet, ReleaseModel.lineTone("Open: #832", "dev", "main"))
        assertEquals(ReleaseModel.Tone.Quiet, ReleaseModel.lineTone("Release under way: #841 dev → main, carries #830", "dev", "main"))
        // Its own branch names: a repo whose prod is "production".
        assertEquals(ReleaseModel.Tone.Prod, ReleaseModel.lineTone("On production since 09:00: #1", "develop", "production"))
        assertEquals(ReleaseModel.Tone.Quiet, ReleaseModel.lineTone("On main since 09:00: #1", "develop", "production"))

        val r = TrackRelease(number = 840, head = "dev", base = "main", state = "merged", mergedMs = at(13, 14, 20), carried = listOf(829, 830))
        assertEquals("landed on main 14:20" to true, ReleaseModel.landed(r, now, zone))
        assertEquals("landed on main" to true, ReleaseModel.landed(r.copy(mergedMs = null), now, zone))
        assertEquals("under way" to false, ReleaseModel.landed(r.copy(state = "open"), now, zone))
        assertEquals("carried #829, #830", ReleaseModel.carried(r))
        assertEquals("carried nothing of this space yet", ReleaseModel.carried(r.copy(carried = emptyList())))
    }
}
