package dev.herdertower.phone.ui

import dev.herdertower.phone.api.AudioClip
import dev.herdertower.phone.data.Link

enum class NoticeAction { Retry, Repair }

/** The connection card's words and its button. */
data class ConnectionNoticeUi(val message: String, val action: NoticeAction)

/** How the phone stands with the Mac, in words, and what the orb plays. */
object LinkModel {
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

    /**
     * The card that says what's wrong with the connection, or null when
     * there's nothing to say. A retry in flight ([Link.Connecting] after a
     * failure) keeps the same card, so it doesn't blink out on every attempt
     * while the tower is away.
     */
    fun connectionNotice(link: Link): ConnectionNoticeUi? = when (link) {
        is Link.Retrying -> ConnectionNoticeUi("${link.reason} Trying again in ${link.seconds} s.", NoticeAction.Retry)
        is Link.Connecting -> link.failing?.let { ConnectionNoticeUi("$it Trying again now.", NoticeAction.Retry) }
        is Link.Revoked -> ConnectionNoticeUi("This phone isn't paired any more. Run “tower phone pair” on the Mac and pair again.", NoticeAction.Repair)
        is Link.CertificateChanged -> ConnectionNoticeUi(
            "The tower's certificate isn't the one this phone paired with. If you remade it, pair again; otherwise someone may be in between.",
            NoticeAction.Repair,
        )
        is Link.Live, Link.Idle, Link.Unpaired -> null
    }
}
