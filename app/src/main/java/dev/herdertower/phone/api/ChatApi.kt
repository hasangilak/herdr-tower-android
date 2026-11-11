package dev.herdertower.phone.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Reading the main chat thread's JSON, tolerant of its exact wrapping: a
 * page may be `{"messages": [...]}` (or `entries`/`chat`/`items`, or a bare
 * array), an event's data a message or `{"message": {...}}`. Kept in one
 * place so following the tower's final shape is one change.
 */
object ChatApi {
    fun page(body: String): ChatPage {
        val e = TowerJson.parseToJsonElement(body.ifBlank { "{}" })
        val list: JsonElement? = when (e) {
            is JsonArray -> e
            is JsonObject -> listOf("messages", "entries", "chat", "items").firstNotNullOfOrNull { e[it] as? JsonArray }
            else -> null
        }
        val messages = (list as? JsonArray).orEmpty().mapNotNull { runCatching { TowerJson.decodeFromJsonElement(ChatMessage.serializer(), it) }.getOrNull() }
        val more = (e as? JsonObject)?.get("more")?.let { runCatching { it.toString().toBooleanStrict() }.getOrNull() }
        return ChatPage(messages.sortedWith(ORDER), more)
    }

    fun message(data: String): ChatMessage {
        val e = TowerJson.parseToJsonElement(data)
        val obj = (e as? JsonObject)?.get("message")?.jsonObject ?: e
        return TowerJson.decodeFromJsonElement(ChatMessage.serializer(), obj)
    }

    /** The tower's order: by `h<line>`, by time for messages without one. */
    private val ORDER = compareBy<ChatMessage>({ if (it.line == Long.MAX_VALUE) 1 else 0 }, { it.line }, { it.at })

    /**
     * Whose conversation a message is (docs/mobile-api.md, "Whose
     * conversation"): a space's own holds its requests, answers and receipts;
     * the main one holds everything except the requests and answers that
     * have a space. A receipt that names a space is in both.
     */
    fun inMain(m: ChatMessage): Boolean = m.space.isNullOrBlank() || !(asked(m) || answered(m))

    fun inSpace(m: ChatMessage, space: String): Boolean = m.space == space && (asked(m) || answered(m) || m.kind == "receipt")

    /** The act a message carries, as the tower last told it: an `act` event may be newer than the message. */
    fun actOf(m: ChatMessage, acts: Map<String, Act>): Act? = m.act?.let { acts[it.id] ?: it }

    // By kind; a message without one by its role (a request is the user's, an answer the tower's).
    private fun asked(m: ChatMessage) = m.kind == "request" || (m.kind == null && m.mine)
    // An act (what the tower opened for a request) belongs where the request was asked, as its answer does.
    private fun answered(m: ChatMessage) = m.kind == "answer" || m.kind == "act" || (m.kind == null && m.fromTower)

    /** A request handed to the main tower for a space reads "In <space>: …": the space's own conversation shows it without that. */
    fun bare(text: String, space: String): String = text.removePrefix("[voice] ").let { t ->
        listOf("In $space: ", "In the space $space: ").firstOrNull { t.startsWith(it) }?.let { t.removePrefix(it) } ?: t
    }

    /** Merges messages into the thread: by id (a later copy replaces an earlier), oldest first. */
    fun merge(thread: List<ChatMessage>, more: List<ChatMessage>): List<ChatMessage> {
        val byId = LinkedHashMap<String, ChatMessage>()
        (thread + more).forEach { m -> byId[m.id.ifBlank { "${m.at}:${m.role}:${m.text.hashCode()}" }] = m }
        return byId.values.sortedWith(ORDER).takeLast(1000)
    }
}
