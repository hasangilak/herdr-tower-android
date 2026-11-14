package dev.herdertower.phone.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spaces as missions (docs/quiet-tower.md, "Step 11"), and the same made from an older tower's spaces and jobs. */
class MissionsTest {
    private val body = """{"spaces":[
        {"name":"sandbox","goal":"A plan for the storefront live editor","done_when":"the plan is written","standing":false,"state":"open","follow":true,"mute":false,"needs":1,"report":null,
         "sessions":[{"address":"s1","name":"eyzflow","status":"done","account":"orca","worktree":"eyzflow@sandbox","port":3010},{"address":"s2","name":"mediaroom","status":"done","account":"orca","worktree":null,"port":null}],
         "requests":[{"id":"a31","title":"Plan the storefront live editor","state":"done","done":3,"total":3}],"thread_count":33},
        {"name":"isa","goal":"Voice clips for the Ingest tour, live","state":"open","sessions":[{"address":"s5","name":"mediaroom","status":"working","account":"orca"}],
         "requests":[{"id":"a32","title":"Voice clips for the Ingest tour","state":"working","done":5,"total":6}]},
        {"name":"M5 Pro","goal":null,"standing":true,"state":"open","sessions":[{"address":"s8","name":"tower","status":"working"}],"requests":[{"id":"a34","title":"Rethink the Mac tower's UX","state":"working","done":0,"total":1}]},
        {"name":"Anna","goal":"Support pictures on cases","state":"ready","report":"Support pictures are live.","sessions":[{"address":"s9","name":"eyzflow","status":"done"}],"requests":[{"id":"a35","title":"Support pictures","state":"done","done":2,"total":2}]}
    ],"quiet":["b2c","uiux"],"closed_today":["old"]}"""

    private val all = TowerJson.decodeFromString(MissionsResponse.serializer(), body)

    @Test fun readsTheMissions() {
        assertEquals(listOf("sandbox", "isa", "M5 Pro", "Anna"), all.spaces.map { it.name })
        assertEquals(listOf("b2c", "uiux"), all.quiet)
        assertEquals(listOf("old"), all.closedToday)
        val s = all.spaces[0]
        assertEquals("the plan is written", s.doneWhen)
        assertEquals(true, s.follow)
        assertEquals(33, s.threadCount)
        assertEquals(MissionSession("s1", "eyzflow", "done", "orca", "eyzflow@sandbox", port = 3010), s.sessions[0])
        assertNull(s.sessions[1].port)
        assertEquals(MissionRequest("a31", "Plan the storefront live editor", "done", 3, 3), s.requests[0])
    }

    /** A session whose turn is done while a job it started in the background runs (feature `jobs`). */
    @Test fun aSessionWithAJobRunningIsAtWork() {
        val body = """{"spaces":[{"name":"search-llm","state":"open","requests":[{"id":"a1","title":"Train it","state":"done","done":1,"total":1}],"sessions":[
            {"address":"t12r","name":"eyz search llm","status":"done","account":"orca",
             "job":{"what":"Restart v2 training","since_ms":1794616860000,"progress_ms":1794618120000,"stuck":false,"more":1,"line":"running a job: Restart v2 training, since 01:21, last progress 01:42 (+1 more)"}},
            {"address":"t12t","name":"sooner","status":"done"}]}],"quiet":[]}"""
        val m = TowerJson.decodeFromString(MissionsResponse.serializer(), body).spaces[0]
        val (r, t) = m.sessions
        assertEquals("Restart v2 training", r.job?.what)
        assertEquals(1794618120000L, r.job?.progressMs)
        assertEquals(true, r.runsJob)
        assertEquals("running a job", r.statusShown)
        assertEquals("done", t.statusShown)
        assertEquals(Mood.Working, MissionsApi.mood(m)) // its requests are done, but a job still runs
        // Quiet and idle for long: shown as such, and the space isn't at work for it.
        val quiet = r.copy(job = r.job?.copy(stuck = true))
        assertEquals("job quiet", quiet.statusShown)
        assertEquals(Mood.Done, MissionsApi.mood(m.copy(sessions = listOf(quiet, t))))
        // A session still in its turn says so, job or not.
        assertEquals("working", r.copy(status = "working").statusShown)
    }

    @Test fun howEachStands() {
        val (sandbox, isa, m5, anna) = all.spaces
        assertEquals(Mood.Needs, MissionsApi.mood(sandbox)) // a decision waits there, whatever else
        assertEquals(Mood.Working, MissionsApi.mood(isa))
        assertEquals(Mood.Ready, MissionsApi.mood(anna))
        assertEquals(Mood.Done, MissionsApi.mood(sandbox.copy(needs = 0)))
        assertEquals(Mood.Quiet, MissionsApi.mood(Mission(name = "x", state = "quiet")))
        assertEquals(Mood.Idle, MissionsApi.mood(Mission(name = "x", sessions = listOf(MissionSession("a", "b", "idle")))))

        assertEquals("done", MissionsApi.progress(sandbox))
        assertEquals("5 of 6", MissionsApi.progress(isa))
        assertEquals("working", MissionsApi.progress(m5))
        assertEquals("ready to close", MissionsApi.progress(anna))
        assertEquals("quiet", MissionsApi.progress(Mission(name = "x", state = "quiet")))

        // Its goal; a standing space says what it works on now.
        assertEquals("A plan for the storefront live editor", MissionsApi.line(sandbox))
        assertEquals("Rethink the Mac tower's UX", MissionsApi.line(m5))
        assertEquals("No goal yet", MissionsApi.line(Mission(name = "x")))

        assertEquals("2 sessions on orca", MissionsApi.sessionsLine(sandbox))
        assertEquals("1 session", MissionsApi.sessionsLine(m5))
        assertEquals("No sessions", MissionsApi.sessionsLine(Mission(name = "x")))
        assertEquals("the spaces at work: isa and M5 Pro", 2, MissionsApi.working(all))
    }

    @Test fun aChangedMissionComesBareOrWrapped() {
        val bare = """{"name":"isa","goal":"g","mute":true}"""
        assertEquals(true, MissionsApi.mission(bare)!!.mute)
        assertEquals("isa", MissionsApi.mission("""{"mission":$bare}""")!!.name)
        assertEquals("isa", MissionsApi.mission("""{"space":$bare}""")!!.name)
        assertNull(MissionsApi.mission("{}"))
        assertNull(MissionsApi.mission("""{"ok":true}"""))
    }

    @Test fun anEventCarriesTheMissionsOrOnlySaysTheyChanged() {
        assertEquals(4, MissionsApi.event(body)!!.spaces.size)
        assertNull(MissionsApi.event("{}"))
        assertNull(MissionsApi.event("nope"))
    }

    @Test fun theMissionsOfAnOlderTowerComeFromItsSpacesAndJobs() {
        val spaces = SpacesResponse(
            focus = null,
            spaces = listOf(
                Space("Storefront", agenda = "The shop", sessions = listOf(SpaceSession("t3", "api", "blocked", "studio"), SpaceSession("t5", "web", "working", "main"))),
                Space("Infra", sessions = listOf(SpaceSession("vps/t2", "deploy", "idle"))),
                Space("Old", agenda = "done long ago"),
            ),
        )
        val jobs = listOf(
            Job("j1", "t5", "web", "Storefront", status = "working", asked = "Build the checkout page", updatedMs = 5),
            Job("j0", "vps/t2", "deploy", "Infra", status = "done", asked = "Deploy v2.3 to staging", updatedMs = 3),
        )
        val decisions = listOf(Decision(id = "a", space = "Storefront"), Decision(id = "b", space = "Storefront", state = "answered"))
        val m = MissionsApi.fromSpaces(spaces, jobs, decisions)
        assertEquals(listOf("Storefront", "Infra"), m.spaces.map { it.name })
        assertEquals("a space with no session is quiet", listOf("Old"), m.quiet)
        val shop = m.spaces[0]
        assertEquals("The shop", shop.goal)
        assertEquals(1, shop.needs)
        assertEquals(listOf("api", "web"), shop.sessions.map { it.name })
        assertEquals(listOf(MissionRequest("j1", "Build the checkout page", "working")), shop.requests)
        assertEquals(Mood.Needs, MissionsApi.mood(shop))
        assertEquals("done", MissionsApi.progress(m.spaces[1]))
        assertEquals("Deploy v2.3 to staging", MissionsApi.line(m.spaces[1]))
    }

    /** A space's active time, as the Mac words it, moving between reads. */
    @Test fun aSpaceSaysHowLongItHasBeenAtWork() {
        val at = 1_794_629_893_000L
        val min = 60_000L
        val a = MissionActive(atMs = at, now = true, sinceMs = at - 72 * min, todayMs = 185 * min, totalMs = 860 * min, waitingTodayMs = 25 * min, waitingTotalMs = 40 * min)
        assertEquals("Active now · 1h 12m this stretch · 3h 05m today · 14h 20m since it opened · waited on you 25m today", ActiveTime.line(a, at))
        // Two minutes on, without a read: at work the whole time.
        assertEquals("Active now · 1h 14m this stretch · 3h 07m today · 14h 22m since it opened · waited on you 25m today", ActiveTime.line(a, at + 2 * min))
        assertEquals("at work 1h 12m", ActiveTime.compact(a, at))
        val off = a.copy(now = false, sinceMs = null, waitingTodayMs = 30_000)
        assertEquals("Not at work now · 3h 05m today · 14h 20m since it opened", ActiveTime.line(off, at + 10 * min))
        assertEquals("3h 05m today", ActiveTime.compact(off, at))
        val waits = off.copy(waitingNow = true, waitingTodayMs = 18 * min)
        assertEquals("Waiting on you now · 3h 05m today · 14h 20m since it opened · waited on you 20m today", ActiveTime.line(waits, at + 2 * min))
        // Under a minute in all and not at work: nothing; nothing today: no word in the list.
        assertEquals(null, ActiveTime.line(MissionActive(atMs = at, totalMs = 30_000), at))
        assertEquals(null, ActiveTime.compact(MissionActive(atMs = at, totalMs = 90 * min, todayMs = 20_000), at))
        assertEquals("42m", ActiveTime.duration(42 * min))
        assertEquals("1h 05m", ActiveTime.duration(65 * min))
        // Read off the missions; absent from an older tower.
        val m = TowerJson.decodeFromString(MissionsResponse.serializer(), """{"spaces":[{"name":"a","active":{"at_ms":1,"now":true,"since_ms":1,"today_ms":60000,"total_ms":60000,"waiting_now":false,"waiting_today_ms":0,"waiting_total_ms":0}},{"name":"b"}]}""")
        assertEquals(true, m.spaces[0].active?.now)
        assertEquals(null, m.spaces[1].active)
    }
}
