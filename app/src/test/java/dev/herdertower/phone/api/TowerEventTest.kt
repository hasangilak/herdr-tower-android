package dev.herdertower.phone.api

import org.junit.Assert.assertEquals
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
}
