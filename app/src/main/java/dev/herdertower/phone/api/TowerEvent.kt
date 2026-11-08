package dev.herdertower.phone.api

import kotlinx.serialization.SerializationException

/** What the tower's event stream (`GET /v1/events`) says, decoded. */
sealed interface TowerEvent {
    data class Hello(val hello: StreamHello) : TowerEvent
    data class State(val state: TowerState) : TowerEvent
    data class History(val entry: HistoryEntry) : TowerEvent
    data class Audio(val clip: AudioClip) : TowerEvent
    /** A message of the main chat thread, new or updated (same id: more text). */
    data class Chat(val message: ChatMessage) : TowerEvent
    /** A question set appeared, changed or closed. */
    data class QuestionChanged(val change: QuestionEvent) : TowerEvent
    /** The behaviors changed (from any screen, or the brain). */
    data class BehaviorsChanged(val change: BehaviorChange) : TowerEvent
    data object Hush : TowerEvent
    data object Pause : TowerEvent
    data object Resume : TowerEvent
    /** A `: ping` comment (every 10 s): the stream is alive. */
    data object Ping : TowerEvent
    /** An event this app doesn't know (newer tower): ignored. */
    data class Unknown(val event: String) : TowerEvent

    companion object {
        /** Decodes one raw event; malformed data of a known event is reported as [Unknown] rather than ending the stream. */
        fun decode(raw: RawEvent): TowerEvent = try {
            when (raw.event) {
                "hello" -> Hello(TowerJson.decodeFromString(StreamHello.serializer(), raw.data))
                "state" -> State(TowerJson.decodeFromString(TowerState.serializer(), raw.data))
                "history" -> History(TowerJson.decodeFromString(HistoryEntry.serializer(), raw.data))
                "audio" -> Audio(TowerJson.decodeFromString(AudioClip.serializer(), raw.data))
                "chat" -> Chat(ChatApi.message(raw.data))
                "question" -> QuestionChanged(QuestionsApi.event(raw.data))
                "behaviors" -> BehaviorsChanged(TowerJson.decodeFromString(BehaviorChange.serializer(), raw.data))
                "hush" -> Hush
                "pause" -> Pause
                "resume" -> Resume
                else -> Unknown(raw.event)
            }
        } catch (e: SerializationException) {
            Unknown(raw.event)
        } catch (e: IllegalArgumentException) {
            Unknown(raw.event)
        }
    }
}
