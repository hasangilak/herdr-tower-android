package dev.herdertower.phone.ui

import dev.herdertower.phone.api.Agent
import dev.herdertower.phone.api.AudioClip
import dev.herdertower.phone.api.Job
import dev.herdertower.phone.api.SpacesResponse
import dev.herdertower.phone.api.StateAgent

/** A card on Home: something worth doing now, from the tower's own data. */
sealed interface Suggestion {
    val key: String

    /** A session is blocked: open it to approve or refuse. */
    data class Answer(val address: String, val name: String, val detail: String) : Suggestion {
        override val key get() = "answer:$address"
    }

    /** Send this to the tower. */
    data class Ask(val title: String, val prompt: String, val detail: String? = null) : Suggestion {
        override val key get() = "ask:$prompt"
    }

    data class Focus(val space: String, val detail: String) : Suggestion {
        override val key get() = "focus:$space"
    }

    data class ClearFocus(val space: String) : Suggestion {
        override val key get() = "unfocus:$space"
    }
}

object HomeModel {
    /** The addresses of sessions waiting for a person (the state's agents and blocked jobs). */
    fun blocked(agents: List<StateAgent>, jobs: List<Job>): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        agents.filter { it.status == "blocked" }.forEach { out[it.address] = it.name.ifBlank { it.address } }
        jobs.filter { it.status == "blocked" }.forEach { j -> out.putIfAbsent(j.session, j.name.ifBlank { j.session }) }
        return out.toList()
    }

    fun suggestions(agents: List<StateAgent>, spaces: SpacesResponse, jobs: List<Job>, live: List<Agent> = emptyList()): List<Suggestion> = buildList {
        blocked(agents, jobs).take(3).forEach { (address, name) ->
            val detail = live.firstOrNull { it.address == address }?.doing?.takeIf { it.isNotBlank() }
                ?: jobs.firstOrNull { it.session == address && it.status == "blocked" }?.asked?.takeIf { it.isNotBlank() }
                ?: "Waiting for your answer"
            add(Suggestion.Answer(address, name, detail))
        }
        add(Suggestion.Ask("Status of all sessions", "What's the status of all sessions?", null))
        add(Suggestion.Ask("Catch me up", "Catch me up: what happened while I was away?", null))
        spaces.spaces.filter { it.unread > 0 }.sortedByDescending { it.unread }.take(2).forEach {
            add(Suggestion.Ask("News in ${it.space}", "What's new in ${it.space}?", "${it.unread} new"))
        }
        val focus = spaces.focus
        if (focus != null) {
            add(Suggestion.ClearFocus(focus))
        } else {
            spaces.spaces
                .filter { it.sessions.isNotEmpty() }
                .sortedWith(compareByDescending<dev.herdertower.phone.api.Space> { s -> s.sessions.count { it.status == "blocked" } }
                    .thenByDescending { it.runningJobs }
                    .thenByDescending { it.lastActivityMs })
                .firstOrNull()
                ?.let { add(Suggestion.Focus(it.space, "Hold other spaces' news")) }
        }
    }

    fun timeGreeting(hour: Int): String = when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        in 18..22 -> "Good evening"
        else -> "Hello"
    }

    /** "2 sessions need you", "3 at work", "all quiet". */
    fun needsLine(blocked: Int, busy: Int, total: Int): String = when {
        blocked > 0 -> "$blocked session${if (blocked == 1) " needs" else "s need"} you"
        busy > 0 -> "$busy at work"
        total > 0 -> "all quiet"
        else -> "no sessions"
    }

    /**
     * The phase the orb plays, as on the Mac, with what the phone itself is
     * doing on top: recording is listening, sending is transcribing, a clip
     * playing is speaking (an alert or its bell: announcing).
     */
    fun orbPhase(connected: Boolean, towerPhase: String?, recording: Boolean, sending: Boolean, playing: AudioClip?): String = when {
        recording -> "listening"
        sending -> "transcribing"
        playing != null -> if (playing.kind == "alert" || playing.kind == "cue") "announcing" else "speaking"
        !connected -> "off"
        else -> towerPhase?.takeIf { it.isNotBlank() } ?: "idle"
    }

    /** The red dot: the brain or any agent is waiting for a person. */
    fun needsYou(connected: Boolean, towerPhase: String?, blocked: Int) = connected && (towerPhase == "blocked" || blocked > 0)
}
