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

    /** Merges messages into the thread: by id (a later copy replaces an earlier), oldest first. */
    fun merge(thread: List<ChatMessage>, more: List<ChatMessage>): List<ChatMessage> {
        val byId = LinkedHashMap<String, ChatMessage>()
        (thread + more).forEach { m -> byId[m.id.ifBlank { "${m.at}:${m.role}:${m.text.hashCode()}" }] = m }
        return byId.values.sortedWith(ORDER).takeLast(1000)
    }
}
