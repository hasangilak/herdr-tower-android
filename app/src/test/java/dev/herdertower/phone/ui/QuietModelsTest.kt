package dev.herdertower.phone.ui

import dev.herdertower.phone.api.Assumed
import dev.herdertower.phone.api.ChatMessage
import dev.herdertower.phone.api.Decision
import dev.herdertower.phone.api.DecisionOption
import dev.herdertower.phone.api.DecisionPr
import dev.herdertower.phone.api.HistoryEntry
import dev.herdertower.phone.api.Job
import dev.herdertower.phone.api.MissionSession
import dev.herdertower.phone.api.MissionsResponse
import dev.herdertower.phone.api.Mission
import dev.herdertower.phone.api.PrGate
import dev.herdertower.phone.api.Screenshot
import dev.herdertower.phone.api.Setup
import dev.herdertower.phone.voice.TalkTarget
import dev.herdertower.phone.voice.VoiceController.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the new screens say, in words: the queue, a card, the reply, the talk button, the record, spaces, a session, a launch. */
class QuietModelsTest {
    // The queue

    /** 14 Nov: eyzflow's card had two drafts among four PRs; on the phone each still looked like a Merge answer. */
    @Test fun aMergesDraftIsShownAsLeftOut() {
        val ready = DecisionPr(repo = "o/app", number = 839, base = "dev", merge = PrGate(can = true))
        val draft = DecisionPr(repo = "o/app", number = 840, base = "dev", merge = PrGate(can = false, blocked = "It's a draft: mark it ready for review first."))
        val failing = DecisionPr(repo = "o/app", number = 841, base = "dev", merge = PrGate(can = true, needsOverride = "1 check failing: lint."))
        val old = DecisionPr(repo = "o/app", number = 842, base = "dev")
        val d = Decision(
            kind = "merge", title = "Merge PR #839 into dev, then PR #842 into dev (o/app)",
            options = listOf(
                DecisionOption(label = "Merge PR #839 into dev", pick = true),
                DecisionOption(label = "#840 isn't in this merge: it's a draft"),
                DecisionOption(label = "#841 isn't in this merge: its checks fail"),
                DecisionOption(label = "Merge PR #842 into dev"),
            ),
            prs = listOf(ready, draft, failing, old),
        )
        assertEquals(listOf(false, true, true, false), d.options.indices.map { DecisionModel.leftOut(d, it) })
        // Only a merge's PRs are left out of anything.
        assertFalse(DecisionModel.leftOut(d.copy(kind = "question"), 1))
    }

    /** A merge card PR's words: its summary, its opening while that is made, or that it has none (and its session was asked). */
    @Test fun aMergesPrSaysWhatItDoes() {
        val body = "## Summary\n\n**Support Studio** gets a `Knowledge gaps` column:\n- counts open gaps\n\n## Testing\nran it"
        val p = DecisionPr(repo = "o/app", number = 839, body = body)
        assertEquals(DescriptionModel.About.Opening("Support Studio gets a Knowledge gaps column: counts open gaps"), DescriptionModel.about(p))
        assertEquals(DescriptionModel.About.Summary("Support staff see open gaps."), DescriptionModel.about(p.copy(summary = "Support staff see open gaps.")))
        assertEquals(DescriptionModel.About.Missing(asked = true), DescriptionModel.about(p.copy(body = " ", askedBody = true)))
        assertNull("not looked at, or an older tower", DescriptionModel.about(p.copy(body = null)))
        assertEquals("No description on GitHub. The tower asked its session to write one.", DescriptionModel.missingLine(true))
        assertEquals("No description on GitHub.", DescriptionModel.missingLine(false))
        assertEquals(listOf("Summary", "Support Studio gets a Knowledge gaps column:", "counts open gaps", "Testing", "ran it"), DescriptionModel.lines(body))
        // As the tower sends it.
        val wire = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(DecisionPr.serializer(), """{"repo":"o/app","number":842,"body":"","asked_body":true,"summary":""}""")
        assertEquals(DescriptionModel.About.Missing(asked = true), DescriptionModel.about(wire))
    }

    @Test fun theQueuesLine() {
        assertEquals("3 need you" to "3 working", DecisionModel.status(3, 3, reach = true))
        assertEquals("1 needs you" to "", DecisionModel.status(1, 0, reach = true))
        assertEquals(null to "Nothing needs you · 2 working", DecisionModel.status(0, 2, reach = true))
        assertEquals(null to "Nothing needs you", DecisionModel.status(0, 0, reach = true))
        assertEquals("2 need you" to "Mac out of reach", DecisionModel.status(2, 0, reach = false))
    }

    @Test fun oneCardIsOpenTheFirstUnlessAnotherWasPicked() {
        val open = listOf(Decision(id = "a"), Decision(id = "b"), Decision(id = "c"))
        assertEquals("a" to "Next", DecisionModel.focus(open, null).let { it.first?.id to it.second })
        assertEquals("c" to "Picked", DecisionModel.focus(open, "c").let { it.first?.id to it.second })
        // The picked one was answered meanwhile: the head of the queue again.
        assertEquals("a" to "Next", DecisionModel.focus(open, "gone").let { it.first?.id to it.second })
        assertEquals("a" to "Next", DecisionModel.focus(open, "a").let { it.first?.id to it.second })
        assertNull(DecisionModel.focus(emptyList(), "a").first)
    }

    @Test fun aCardsLines() {
        val now = 100 * 60_000L
        val d = Decision(space = "sandbox", waiting = 3, firstMs = now - 2 * 60_000)
        assertEquals("3 sessions wait · 2 min", DecisionModel.stakeLine(d, now))
        assertEquals("sandbox · 3 sessions wait · 2 min", DecisionModel.rowLine(d, now))
        assertEquals("isa · nothing is blocked · 31 min", DecisionModel.rowLine(Decision(space = "isa", firstMs = now - 31 * 60_000), now))
        assertEquals("checks pass" to PrModel.Tone.Good, DecisionModel.checks(DecisionPr(checks = "pass")))
        assertEquals("checks fail" to PrModel.Tone.Bad, DecisionModel.checks(DecisionPr(checks = "fail")))
        assertEquals("checks running" to PrModel.Tone.Pending, DecisionModel.checks(DecisionPr(checks = "pending")))
        assertNull(DecisionModel.checks(DecisionPr()))
    }

    @Test fun whatACardWithoutAnswersOffers() {
        val options = listOf(DecisionOption("Yes"))
        assertEquals(DecisionModel.Fallback.None, DecisionModel.fallback(Decision(options = options)))
        assertEquals(DecisionModel.Fallback.Questions, DecisionModel.fallback(Decision(kind = "question", questionId = "q1")))
        assertEquals(DecisionModel.Fallback.Words, DecisionModel.fallback(Decision(kind = "raised")))
        assertEquals("pull requests are merged from their rows", DecisionModel.Fallback.None, DecisionModel.fallback(Decision(kind = "merge")))
        // "Other answer" beside the answers: for what words can answer, not for a permission or a merge.
        assertTrue(DecisionModel.takesWords(Decision(kind = "choice", options = options)))
        assertTrue(DecisionModel.takesWords(Decision(kind = "question", options = options)))
        assertFalse(DecisionModel.takesWords(Decision(kind = "permission", options = options)))
        assertFalse(DecisionModel.takesWords(Decision(kind = "merge", options = options)))
        assertFalse(DecisionModel.takesWords(Decision(kind = "choice")))
    }

    // The reply card

    private fun me(id: Int, text: String, at: Long, space: String? = null) = ChatMessage(id = "h$id", at = at, role = "user", text = text, space = space)
    private fun tower(id: Int, text: String, at: Long, space: String? = null) = ChatMessage(id = "h$id", at = at, role = "tower", text = text, space = space)

    @Test fun theReplyIsTheTowersFirstWordsAfterWhatYouSaid() {
        val thread = listOf(
            me(1, "[voice] what's left?", 100), tower(2, "3 need you.", 110),
            // Said by itself later (a tower that still tells everything): not the reply.
            tower(3, "Web finished the onboarding page.", 500),
        )
        assertEquals(ReplyModel.Exchange("what's left?", 100, "3 need you.", 110), ReplyModel.last(thread, null))
        // Asked, no answer yet.
        assertEquals(ReplyModel.Exchange("open a session", 600, null, 600), ReplyModel.last(thread + me(4, "open a session", 600), null))
        assertNull(ReplyModel.last(emptyList(), null))
        // What the tower says by itself isn't a reply: a tower that tells everything mustn't fill the queue with it.
        assertNull(ReplyModel.last(listOf(tower(1, "The plan is finished.", 50)), null))
    }

    @Test fun aReceiptShowsWhenTheTowerSendsThem() {
        val thread = listOf(me(1, "what's left?", 100), tower(2, "3 need you.", 110), tower(3, "The plan for the live editor is finished.", 500))
        // The level is "results" (or the space is followed): its latest line by itself is the receipt.
        assertEquals(ReplyModel.Exchange(null, 0, "The plan for the live editor is finished.", 500), ReplyModel.last(thread, null, receipts = true))
        assertEquals("a reply is still a reply", "3 need you.", ReplyModel.last(thread.take(2), null, receipts = true)!!.reply)
        assertEquals(ReplyModel.Exchange(null, 0, "Done.", 50), ReplyModel.last(listOf(tower(1, "Done.", 50)), null, receipts = true))
        assertNull(ReplyModel.last(listOf(tower(1, "Done.", 50, "isa")), null, receipts = true))
    }

    @Test fun theBoxUnderATitleSaysOnlyWhatTheTitleLeavesOut() {
        val text = "t7h has both corrections on the plan page.\n\nNothing is started."
        assertEquals("Nothing is started.", DecisionModel.detail(Decision(title = "t7h has both corrections on the plan page.", detail = text)))
        assertEquals(null, DecisionModel.detail(Decision(title = "Run it?", detail = "Run it?")))
        assertEquals("rm -rf build/", DecisionModel.detail(Decision(title = "Run a command", detail = "rm -rf build/")))
        assertEquals(null, DecisionModel.detail(Decision(title = "x")))
    }

    @Test fun aBlockedSessionOrAnUnknownKindIsShownNotAnswered() {
        val t3 = dev.herdertower.phone.api.DecisionSession("t3", "sandbox › mediaroom")
        val blocked = Decision(id = "d1", kind = "blocked", title = "mediaroom waits on a dialog the tower can't read", sessions = listOf(t3))
        assertFalse(DecisionModel.answerable(blocked))
        assertEquals(DecisionModel.Fallback.Show, DecisionModel.fallback(blocked))
        assertEquals("Show on Mac", DecisionModel.showLabel(blocked, t3))
        val two = blocked.copy(sessions = listOf(t3, dev.herdertower.phone.api.DecisionSession("t4", "eyzflow")))
        assertEquals("Show eyzflow on Mac", DecisionModel.showLabel(two, two.sessions[1]))
        // A newer tower's kind: a plain card, its options not offered (an answer from here would be refused).
        val later = Decision(id = "d2", kind = "survey", title = "Something new", options = listOf(DecisionOption("Yes")))
        assertFalse(DecisionModel.answerable(later))
        assertEquals(DecisionModel.Fallback.None, DecisionModel.fallback(later))
        assertEquals(DecisionModel.Fallback.Show, DecisionModel.fallback(later.copy(sessions = listOf(t3))))
        assertFalse(DecisionModel.takesWords(blocked))
        assertEquals(emptyList<Pair<Int, String>>(), dev.herdertower.phone.data.DecisionNotices.actions(later))
        // Still the user's: in the queue, counted.
        assertEquals(listOf("d1", "d2"), dev.herdertower.phone.api.DecisionsApi.main(listOf(blocked, later)).map { it.id })
        // The ones from before still answer as they did.
        assertTrue(DecisionModel.answerable(Decision(kind = "choice")) && DecisionModel.answerable(Decision()))
    }

    @Test fun thePairingListsEachNetworkAndHowItStands() {
        fun t(kind: String, host: String, port: Int, dns: String? = null, seenRange: String? = null, seenName: String? = null, lastOkMs: Long? = null) =
            dev.herdertower.phone.api.TowerHost(kind, host, port, dns, seenRange, seenName, lastOkMs)
        val now = 1_794_400_000_000L
        val office = t("lan", "10.10.30.116", 7843, seenRange = "10.10.30", seenName = "Office", lastOkMs = now - 6 * 3_600_000)
        val home = t("lan", "192.168.2.211", 7843, seenRange = "192.168.2", lastOkMs = now - 20_000)
        val old = t("lan", "192.168.1.9", 7843)
        val ts = t("tailscale", "100.68.110.33", 7843, "mac.tailnet.ts.net")
        val tries = listOf(dev.herdertower.phone.data.RouteTry(office, dev.herdertower.phone.data.RouteOutcome.TimedOut, now))
        val rows = SettingsModel.networks(listOf(home, office, old, ts), null, home, tries, now)
        assertEquals(listOf("The network 192.168.2.x", "Office", "The network 192.168.1.x", "Tailscale"), rows.map { it.title })
        assertEquals(listOf("connected now", "no answer here · last 6 h ago", "from the pairing", "from the pairing"), rows.map { it.status })
        assertEquals("100.68.110.33 · mac.tailnet.ts.net", rows[3].address)
        assertTrue(rows[0].now)
        // Out of reach: when each last answered.
        assertEquals("last answered just now", SettingsModel.networks(listOf(home), null, null, emptyList(), now).single().status)
    }

    @Test fun aCommandsReplyCarriesItsAct() {
        val act = dev.herdertower.phone.api.Act(id = "r1", line = "Opened notes fix in M5 Pro.", undo = dev.herdertower.phone.api.ActUndo(true))
        val thread = listOf(
            me(1, "open a session in notes", 100),
            ChatMessage(id = "h2", at = 105, role = "tower", kind = "act", text = act.line, act = act),
            ChatMessage(id = "h3", at = 110, role = "tower", kind = "answer", text = "Opened it on orca."),
        )
        val x = ReplyModel.last(thread, null)!!
        assertEquals("r1", x.act?.id)
        assertEquals("the tower's own words, not the act's line", "Opened it on orca.", x.reply)
        // The act alone so far: it is the reply.
        assertEquals("r1", ReplyModel.last(thread.take(2), null)!!.act?.id)
        assertNull(ReplyModel.last(thread.take(2), null)!!.reply)
        // In a space it belongs to that space's conversation.
        val inSpace = ChatMessage(id = "h4", role = "tower", kind = "act", space = "sandbox", act = act)
        assertFalse(dev.herdertower.phone.api.ChatApi.inMain(inSpace))
        assertTrue(dev.herdertower.phone.api.ChatApi.inSpace(inSpace, "sandbox"))
        assertEquals("default model", dev.herdertower.phone.api.ActsApi.chip(dev.herdertower.phone.api.Assumption("model", "default model")))
        assertEquals("account orca", dev.herdertower.phone.api.ActsApi.chip(dev.herdertower.phone.api.Assumption("account", "orca")))
        // Names come ready to show; inside the space its own name says nothing.
        assertEquals("mediaroom", dev.herdertower.phone.api.ActsApi.inSpace("sandbox › mediaroom", "sandbox"))
        assertEquals("sandbox › mediaroom", dev.herdertower.phone.api.ActsApi.inSpace("sandbox › mediaroom", null))
    }

    @Test fun whoseConversationAMessageIs() {
        val api = dev.herdertower.phone.api.ChatApi
        fun m(kind: String?, role: String, space: String?) = ChatMessage(id = "h1", role = role, kind = kind, space = space, text = "x")
        // Asked of a space's own tower, and its answer: the space's conversation only.
        listOf(m("request", "user", "fix-login"), m("answer", "tower", "fix-login")).forEach {
            assertFalse(api.inMain(it))
            assertTrue(api.inSpace(it, "fix-login"))
            assertFalse(api.inSpace(it, "isa"))
        }
        // A receipt that names a space is in both; news from a space's session is the main conversation's.
        assertTrue(api.inMain(m("receipt", "tower", "fix-login")) && api.inSpace(m("receipt", "tower", "fix-login"), "fix-login"))
        assertTrue(api.inMain(m("alert", "event", "fix-login")))
        assertFalse(api.inSpace(m("alert", "event", "fix-login"), "fix-login"))
        // Asked of the tower itself.
        assertTrue(api.inMain(m("request", "user", null)))
        // Handed to the main tower for a space: shown without the space in front.
        assertEquals("What is left here?", api.bare("In fix-login: What is left here?", "fix-login"))
        assertEquals("What is left here?", api.bare("[voice] What is left here?", "fix-login"))
        assertEquals("In isa: x", api.bare("In isa: x", "fix-login"))
    }

    @Test fun whatALaunchAndACloseSay() {
        val a = dev.herdertower.phone.api.Assumed(setup = "eyzflow + mediaroom on orca", setupWhy = "the first of 2", account = "orca", accountWhy = "the setup's", name = "fix-login", nameWhy = "the goal's first two words", worktrees = true, worktreesWhy = "one per repo")
        assertEquals(
            listOf("Named fix-login: the goal's first two words", "Setup eyzflow + mediaroom on orca: the first of 2", "Account orca: the setup's", "Worktrees: one per repo"),
            ActionModel.assumed(a),
        )
        val opened = dev.herdertower.phone.api.LaunchSession(name = "fix-login-eyzflow", repo = "/w/eyzflow", address = "t3", branch = "fix-login/eyzflow", port = 3001)
        assertEquals("fix-login-eyzflow" to "opened as t3 · fix-login/eyzflow · port 3001", ActionModel.session(opened, planned = false))
        assertEquals("couldn't open: no such folder", ActionModel.session(opened.copy(address = null, error = "no such folder", branch = null, port = null), planned = false).second)
        assertEquals("opening…", ActionModel.session(opened.copy(address = null, branch = null, port = null), planned = false).second)
        val r = dev.herdertower.phone.api.CloseReceipt(
            closed = "fix-login",
            sessionsEnded = listOf(dev.herdertower.phone.api.DecisionSession("t3", "eyzflow"), dev.herdertower.phone.api.DecisionSession("t4", "mediaroom")),
            worktreesRemoved = listOf("/w/a"),
            keptWorktrees = listOf(dev.herdertower.phone.api.KeptWorktree("/w/.tower-worktrees/fix-login/mediaroom", "fix-login/mediaroom", why = "2 commits on no remote")),
        )
        assertEquals(
            listOf("Ended 2 sessions: eyzflow, mediaroom", "Removed 1 worktree", "Kept mediaroom on fix-login/mediaroom: 2 commits on no remote"),
            ActionModel.closed(r),
        )
    }

    @Test fun aLongReplyIsCutAtAWord() {
        assertEquals("Short.", ReplyModel.brief("Short."))
        val long = "The sandbox project session found three more points after reading the conditions, " + "and queued them. ".repeat(40)
        val cut = ReplyModel.brief(long)
        assertTrue(cut.length <= ReplyModel.BRIEF + 1)
        assertTrue(cut.endsWith("…"))
        assertFalse("not in the middle of a word", cut.dropLast(1).endsWith("queue") && !cut.dropLast(1).endsWith("queued"))
        assertTrue(long.startsWith(cut.dropLast(1)))
    }

    @Test fun aSpacesTowerHasItsOwnExchange() {
        val thread = listOf(
            me(1, "what's left?", 100), tower(2, "3 need you.", 110),
            me(3, "open one more session here", 200, "sandbox"), tower(4, "Opened in sandbox.", 210, "sandbox"),
        )
        assertEquals("3 need you.", ReplyModel.last(thread, null)!!.reply)
        assertEquals(ReplyModel.Exchange("open one more session here", 200, "Opened in sandbox.", 210), ReplyModel.last(thread, "sandbox"))
        assertNull(ReplyModel.last(thread, "isa"))
        assertEquals("You, to the main tower", ReplyModel.who(null))
        assertEquals("You, to the sandbox tower", ReplyModel.who("sandbox"))
    }

    @Test fun anOlderTowersHistoryGivesTheSame() {
        val history = listOf(
            HistoryEntry(at = 100, kind = "request", text = "[voice] status?"), HistoryEntry(at = 105, kind = "job", text = "x"),
            HistoryEntry(at = 110, kind = "answer", text = "All quiet."), HistoryEntry(at = 300, kind = "answer", text = "api needs you."),
        )
        assertEquals(ReplyModel.Exchange("status?", 100, "All quiet.", 110), ReplyModel.lastOf(history))
        assertNull(ReplyModel.lastOf(listOf(HistoryEntry(at = 1, kind = "job", text = "x"))))
        assertNull("nothing asked: what it says by itself isn't a reply", ReplyModel.lastOf(listOf(HistoryEntry(at = 1, kind = "answer", text = "api needs you."))))
    }

    @Test fun theCardGoesWhenClosedOrOld() {
        val x = ReplyModel.Exchange("status?", 1000, "All quiet.", 2000)
        assertTrue(ReplyModel.shows(x, closedAt = 0, now = 3000))
        assertFalse("closed after it came", ReplyModel.shows(x, closedAt = 2500, now = 3000))
        assertTrue("a newer one shows again", ReplyModel.shows(x.copy(at = 2600), closedAt = 2500, now = 3000))
        assertFalse("old: it's in the conversation", ReplyModel.shows(x, closedAt = 0, now = 2000 + ReplyModel.FRESH_MS + 1))
        assertFalse(ReplyModel.shows(null, 0, 0))
    }

    // The talk button

    @Test fun theTalkButtonsWords() {
        assertEquals("Hold to talk", TalkModel.button(Phase.Idle))
        assertEquals("Let go to send", TalkModel.button(Phase.Recording(handsFree = false)))
        assertEquals("Tap to send", TalkModel.button(Phase.Recording(handsFree = true)))
        assertEquals("Sending…", TalkModel.button(Phase.Sending))
        assertEquals("Listening…" to "Let go to send", TalkModel.heading(Phase.Recording(false), waiting = false, target = TalkTarget.Main))
        assertEquals("Listening…" to "Pause, or tap the button to send", TalkModel.heading(Phase.Recording(true), false, TalkTarget.Main))
        assertEquals("Listening…" to "Tap the button when you're done", TalkModel.heading(Phase.Recording(true), true, TalkTarget.Main))
        assertEquals("Sending…" to "To the sandbox tower", TalkModel.heading(Phase.Sending, false, TalkTarget.Space("sandbox")))
        assertEquals("Writing it down…" to "Into the goal", TalkModel.heading(Phase.Sending, false, TalkTarget.Into {}))
        assertEquals("Heard" to "To the main tower", TalkModel.heading(Phase.Idle, false, TalkTarget.Main))
        assertEquals("Tell the tower…", TalkModel.hint(null))
        assertEquals("Tell the sandbox tower…", TalkModel.hint("sandbox"))
    }

    // The record

    @Test fun theRecordsLines() {
        val history = listOf(
            HistoryEntry(at = 10, kind = "request", text = "[voice] Open a space named sandbox."),
            HistoryEntry(at = 20, kind = "answer", text = "**Opened sandbox.** Three sessions."),
            HistoryEntry(at = 30, kind = "job", session = "s4", name = "eyzflow", space = "isa", status = "done", text = "Uploaded 12 clips."),
            HistoryEntry(at = 40, kind = "peer", session = "s1", space = "sandbox", text = "Four changes to the plan summary.", from = "s3", fromName = "project", toName = "eyzflow"),
            HistoryEntry(at = 50, kind = "peer", session = "s1", name = "eyzflow", space = "sandbox", text = "Answers on stale writes.", from = "s2"),
            HistoryEntry(at = 60, kind = "screenshot", session = "s4", name = "eyzflow", space = "isa", id = "shot1.png", caption = "The Ingest walk on dev"),
            HistoryEntry(at = 70, kind = "answered", session = "s5", name = "mediaroom", space = "isa", text = "answered for you: Where to start? -> the small fix"),
            HistoryEntry(at = 80, kind = "job", session = "s9", space = "Anna", status = "working", text = ""),
            HistoryEntry(at = 90, kind = "note", space = "sandbox", text = "The plan is finished and checked."),
        )
        val rows = RecordModel.rows(history) { mapOf("s2" to "mediaroom")[it] }
        // Newest first; a line with nothing to say is left out.
        assertEquals(listOf(90L, 70L, 60L, 50L, 40L, 30L, 20L, 10L), rows.map { it.at })
        assertEquals(listOf("Note", "Answered for you", "Screenshots", "Between sessions", "Between sessions", "Finished", "The tower", "You asked"), rows.map { it.tag })
        assertEquals("sandbox › tower", RecordModel.where(rows[0]))
        assertEquals("Where to start? -> the small fix", rows[1].text)
        assertEquals("shot1.png", rows[2].shot)
        assertEquals("The Ingest walk on dev", rows[2].text)
        // Who wrote whom: the names the tower gives, else the session's name from its address.
        assertEquals("sandbox › mediaroom → eyzflow", RecordModel.where(rows[3]))
        assertEquals("sandbox › project → eyzflow", RecordModel.where(rows[4]))
        assertEquals("isa › eyzflow", RecordModel.where(rows[5]))
        assertEquals("tower", RecordModel.where(rows[6]))
        assertEquals("Opened sandbox. Three sessions.", rows[6].text)
        assertEquals("you", RecordModel.where(rows[7]))
        assertEquals("Open a space named sandbox.", rows[7].text)
        assertEquals(listOf("sandbox", "isa"), RecordModel.spaces(rows))
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertEquals("Handed out", RecordModel.kind(HistoryEntry(kind = "job", status = "working")).first)
        assertEquals("Failed", RecordModel.kind(HistoryEntry(kind = "job", status = "error")).first)
        assertEquals("Some new kind", RecordModel.kind(HistoryEntry(kind = "some_new_kind")).first)
    }

    // Spaces, a session, a launch, the shots

    @Test fun theSpacesCount() {
        assertEquals("No spaces yet", MissionsModel.counts(MissionsResponse()))
        assertEquals("2 open", MissionsModel.counts(MissionsResponse(listOf(Mission("a"), Mission("b")))))
        assertEquals("1 open · 7 quiet", MissionsModel.counts(MissionsResponse(listOf(Mission("a")), List(7) { "q$it" })))
    }

    @Test fun whatASessionLastSaid() {
        val s = MissionSession("s2", "mediaroom", "done", "orca", "mediaroom@sandbox", port = 3011)
        val history = listOf(
            HistoryEntry(at = 1, kind = "summary", session = "s2", text = "Read the plan."),
            HistoryEntry(at = 2, kind = "watch", session = "s2", text = "s2 (mediaroom): Answered the planning questions. Tell me briefly."),
            HistoryEntry(at = 3, kind = "watch", session = "s9", text = "another session"),
        )
        assertEquals("the tower's own words win", "Wrote it", SessionModel.said(s.copy(said = "Wrote it"), history, emptyList()))
        assertEquals("Answered the planning questions.", SessionModel.said(s, history, emptyList()))
        assertEquals("Plan it", SessionModel.said(s, emptyList(), listOf(Job("j", "s2", asked = "Plan it", updatedMs = 5))))
        assertEquals("", SessionModel.said(s, emptyList(), emptyList()))
        assertEquals("mediaroom@sandbox · port 3011", SessionModel.place(s))
        assertEquals("done · worktree mediaroom@sandbox · port 3011", SessionModel.line(s))
        assertEquals("waits", SessionModel.line(MissionSession("t3", "api", "blocked")))
        assertEquals("", SessionModel.place(MissionSession("t3", "api", "working")))
    }

    @Test fun whatALaunchWillDo() {
        val plan = LaunchModel.plan(Setup("eyzflow + mediaroom on orca", "orca", listOf("~/Work/eyz/eyzflow", "~/Work/eyz/eyz-mediaroom-serverless/")))
        assertEquals("Open 2 sessions on orca: eyzflow, eyz-mediaroom-serverless", plan[1])
        assertEquals(4, plan.size)
        assertTrue(LaunchModel.plan(null).any { it.contains("setup") })
        assertEquals(listOf("case-exports", "eyzflow + mediaroom on orca", "orca"), LaunchModel.assumed(Assumed(setup = "eyzflow + mediaroom on orca", account = "orca", name = "case-exports")))
        assertEquals(emptyList<String>(), LaunchModel.assumed(Assumed()))
        assertTrue(LaunchModel.words("Case exports as PDF").endsWith("Case exports as PDF"))
    }

    @Test fun theShotsCount() {
        val now = System.currentTimeMillis()
        assertEquals("None yet", ShotsModel.count(emptyList(), now))
        assertEquals("2 today", ShotsModel.count(listOf(Screenshot("a", now - 1000), Screenshot("b", now - 2000), Screenshot("c", now - 3 * 86_400_000L)), now))
        assertEquals("1 shot", ShotsModel.count(listOf(Screenshot("c", now - 3 * 86_400_000L)), now))
    }

    @Test fun theSettingsWords() {
        assertTrue(SettingsModel.notifyWords("decisions").contains("no finishes"))
        assertTrue(SettingsModel.notifyWords("results").contains("finished"))
        assertTrue("a tower from before the setting says so", SettingsModel.notifyWords(null).contains("next update"))
        assertTrue(SettingsModel.routeWords("mac").startsWith("The Mac speaks"))
        assertTrue(SettingsModel.voiceWords(false).contains("every 15 minutes"))
    }
}
