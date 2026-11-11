package dev.herdertower.phone.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TowerEventTest {
    @Test fun decodesTheContractsState() {
        val json = """{"version": 812, "updated_ms": 1794158000000, "pid": 28224, "running": true, "phase": "idle",
          "mode": "ptt", "mic": "closed", "armed": false, "transcribing": false, "speaking": false, "announcing": false,
          "brain": "done", "brain_app": "claude-code", "hearing": "", "heard": "what's everyone doing", "heard_ms": 0,
          "said": "Nothing is blocked.", "said_ms": 0,
          "alerts": [{"at_ms": 0, "terminal": "t22", "text": "Claude in eyzflow is done", "level": "done"}],
          "agents": [{"address": "t22", "name": "Claude in eyzflow", "status": "working", "app": "claude-code", "account": "default"}],
          "error": "", "talk_key": "ctrl", "voice": "af_bella", "stt_model": "x", "lan": "https://192.168.1.20:7843", "phone": "Pixel 9",
          "something_new": {"nested": [1, 2]}}"""
        val e = TowerEvent.decode(RawEvent("2", "state", json)) as TowerEvent.State
        assertEquals(812L, e.state.version)
        assertEquals("ptt", e.state.mode)
        assertEquals("what's everyone doing", e.state.heard)
        assertEquals("t22", e.state.alerts.single().terminal)
        assertEquals(StateAgent("t22", "Claude in eyzflow", "working", "claude-code", "default"), e.state.agents.single())
        assertEquals("Pixel 9", e.state.phone)
    }

    @Test fun nullsAndMissingKeysTakeDefaults() {
        val e = TowerEvent.decode(RawEvent(null, "state", """{"phase": null, "phone": null}""")) as TowerEvent.State
        assertEquals("idle", e.state.phase)
        assertEquals(null, e.state.phone)
    }

    @Test fun decodesHistoryAudioAndVoiceControls() {
        val h = TowerEvent.decode(RawEvent("3", "history", """{"at": 5, "kind": "job", "session": "vps/t3", "status": "blocked", "text": "needs approval"}"""))
        assertEquals(HistoryEntry(at = 5, kind = "job", session = "vps/t3", status = "blocked", text = "needs approval"), (h as TowerEvent.History).entry)
        val a = TowerEvent.decode(RawEvent("4", "audio", """{"id":"a17","kind":"reply","text":"Hi.","ms":1840,"url":"/v1/audio/a17"}"""))
        assertEquals(AudioClip("a17", "reply", "Hi.", 1840, "/v1/audio/a17"), (a as TowerEvent.Audio).clip)
        assertEquals(TowerEvent.Hush, TowerEvent.decode(RawEvent(null, "hush", "{}")))
        assertEquals(TowerEvent.Pause, TowerEvent.decode(RawEvent(null, "pause", "{}")))
        assertEquals(TowerEvent.Resume, TowerEvent.decode(RawEvent(null, "resume", "{}")))
        assertEquals(TowerEvent.Hello(StreamHello(1, "d_3fa9c2e1", true)), TowerEvent.decode(RawEvent("1", "hello", """{"api":1,"device":"d_3fa9c2e1","voice":true}""")))
    }

    @Test fun unknownOrBrokenEventsDontEndTheStream() {
        assertEquals(TowerEvent.Unknown("future"), TowerEvent.decode(RawEvent(null, "future", "{}")))
        assertTrue(TowerEvent.decode(RawEvent(null, "state", "not json")) is TowerEvent.Unknown)
    }

    @Test fun agentsToleratesOddWhereAndNulls() {
        val r = TowerJson.decodeFromString(AgentsResponse.serializer(), """{"agents":[{"address":"t3","account":null,"machine":"this mac","name":"api",
            "spoken":"api","agent":"claude-code","status":"blocked","doing":null,"title":"x","folder":"/w","where":{"space":"B2C"}}],
            "unreachable":[{"machine":"vps","error":"timeout"}]}""")
        assertEquals("blocked", r.agents.single().status)
        assertEquals("vps", r.unreachable.single().machine)
    }

    @Test fun theQueueAndTheMissionsChange() {
        val d = TowerEvent.decode(RawEvent(null, "decisions", """{"decisions":[{"id":"d1","title":"Allow one read","state":"open"}]}"""))
        assertEquals(listOf("d1"), (d as TowerEvent.DecisionsChanged).decisions!!.decisions.map { it.id })
        // Only "something changed": the app reads the queue again.
        assertEquals(TowerEvent.DecisionsChanged(null), TowerEvent.decode(RawEvent(null, "decisions", "{}")))
        val m = TowerEvent.decode(RawEvent(null, "missions", """{"spaces":[{"name":"sandbox","state":"ready"}],"quiet":["b2c"]}"""))
        assertEquals("ready", (m as TowerEvent.MissionsChanged).missions!!.spaces.single().state)
        assertEquals(TowerEvent.MissionsChanged(null), TowerEvent.decode(RawEvent(null, "missions", "{}")))
    }

    @Test fun theStateCarriesTheBadgeTheTellLevelAndTheModel() {
        val s = (TowerEvent.decode(RawEvent(null, "state", """{"phase":"idle","decisions_count":2,"tell_level":"results",
            "brain_model":{"program":"claude","model":"claude-opus-5-5","effort":"high","choices":{"models":[{"id":"claude-opus-5-5","label":"Opus 5.5"}],"efforts":["low","high"]},
            "running":[{"brain":"tower","space":null,"address":"t1","model":"claude-opus-5-5","effort":"high","restart":false}],"restart_needed":false,
            "locked_by_args":false,"locked":{"model":null,"effort":null},"says":"The tower's brain runs on Opus 5.5 at high effort."}}""")) as TowerEvent.State).state
        assertEquals(2, s.decisionsCount)
        assertEquals("results", s.tellLevel)
        assertEquals("Opus 5.5", s.brainModel?.modelLabel)
        assertEquals("tower", s.brainModel?.running?.single()?.brain)
        // A tower from before: none is there.
        val old = (TowerEvent.decode(RawEvent(null, "state", """{"phase":"idle"}""")) as TowerEvent.State).state
        assertEquals(null, old.decisionsCount)
        assertEquals(null, old.tellLevel)
        assertEquals(null, old.brainModel)
    }

    @Test fun theHelloSaysWhatTheTowerHas() {
        val quiet = (TowerEvent.decode(RawEvent(null, "hello", """{"api":1,"device":"d_7463a95d","voice":false,"features":["quiet"]}""")) as TowerEvent.Hello).hello
        assertTrue(quiet.quiet)
        // A tower from before features sends none: read as none.
        val old = (TowerEvent.decode(RawEvent(null, "hello", """{"api":1,"device":"d_1","voice":true}""")) as TowerEvent.Hello).hello
        assertFalse(old.quiet)
        assertFalse((TowerEvent.decode(RawEvent(null, "hello", """{"api":1,"features":["later"]}""")) as TowerEvent.Hello).hello.quiet)
    }

    @Test fun anActChangesAndTheHelloSaysActs() {
        val a = TowerEvent.decode(RawEvent(null, "act", """{"id":"r1","kind":"start","line":"Opened notes fix.","state":"undone","undo":{"possible":false,"why":null},"closing":"Closed it."}""")) as TowerEvent.ActChanged
        assertTrue(a.act.undone)
        assertEquals("Closed it.", a.act.closing)
        val h = (TowerEvent.decode(RawEvent(null, "hello", """{"api":1,"features":["quiet","acts"]}""")) as TowerEvent.Hello).hello
        assertTrue(h.quiet && h.acts)
        // An act message in the chat carries its act.
        val m = TowerEvent.decode(RawEvent(null, "chat", """{"op":"add","message":{"id":"h5","role":"tower","kind":"act","text":"Opened notes fix.","space":null,"act":{"id":"r1","line":"Opened notes fix."}}}""")) as TowerEvent.Chat
        assertEquals("r1", m.message.act?.id)
        // A decision's answer says whether it can become a rule.
        val r = TowerJson.decodeFromString(DecisionAnswered.serializer(), """{"ok":true,"id":"d1","state":"answered","answer":"A sidebar","rule":{"can":true,"text":"Make this a rule: …"}}""")
        assertEquals(true, r.rule?.can)
        val s = (TowerEvent.decode(RawEvent(null, "state", """{"tell_voice":"decisions"}""")) as TowerEvent.State).state
        assertEquals("decisions", s.tellVoice)
    }

    @Test fun aThreadMessageAndAnAction() {
        val t = TowerEvent.decode(RawEvent(null, "thread", """{"space":"fix-login","message":{"id":"h22","at":1794369749048,"from":"t3","from_name":"fix login eyzflow",
            "to":"t4","to_name":"fix login mediaroom","text":"Which port does the api listen on?","space":"fix-login","delivered":true}}""")) as TowerEvent.ThreadAdded
        assertEquals("fix-login", t.event.space)
        assertEquals("h22", t.event.message.id)
        assertEquals("fix login eyzflow → fix login mediaroom", t.event.message.who)
        val a = TowerEvent.decode(RawEvent(null, "action", """{"id":"act_3cbc0b4e","kind":"launch","space":"fix-login","state":"running","plan":{"space":"fix-login","goal":"Fix the login redirect",
            "made":true,"dry_run":true,"sessions":[{"name":"fix-login-eyzflow","repo":"/w/eyzflow","address":null}],"assumed":{"setup":"eyzflow"}},"receipt":null,"error":null,
            "by":"phone:d_1","started_ms":1,"finished_ms":null}""")) as TowerEvent.ActionChanged
        assertTrue(a.action.running)
        assertEquals("fix-login-eyzflow", a.action.launch?.sessions?.single()?.name)
        // An action without an id isn't one.
        assertTrue(TowerEvent.decode(RawEvent(null, "action", "{}")) is TowerEvent.Unknown)
    }
}
