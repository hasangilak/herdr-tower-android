package dev.herdertower.phone.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** One rule of how the tower works (docs/behaviors.md). [source]: `builtin`, `user` or `learned`. */
@Serializable
data class Behavior(
    val id: String = "",
    val title: String = "",
    val category: String = "Misc",
    val enabled: Boolean = true,
    val source: String = "user",
    val summary: String = "",
    @SerialName("created_ms") val createdMs: Long = 0,
    @SerialName("updated_ms") val updatedMs: Long = 0,
    val text: String = "",
)

/** The fixed part of the tower's instructions: shown, never changed. */
@Serializable
data class BehaviorsCore(val title: String = "", val text: String = "", @SerialName("read_only") val readOnly: Boolean = true)

@Serializable
data class BehaviorsSummary(val count: Int = 0, val enabled: Int = 0, @SerialName("updated_ms") val updatedMs: Long = 0)

@Serializable
data class BehaviorsResponse(
    val categories: List<String> = emptyList(),
    val behaviors: List<Behavior> = emptyList(),
    val core: BehaviorsCore? = null,
    val summary: BehaviorsSummary? = null,
)

/** A change to the behaviors: `add`, `edit`, `enable`, `disable`, `remove` or `undo`, by `user` or `brain`. */
@Serializable
data class BehaviorChange(
    val at: Long = 0,
    val op: String = "",
    val id: String = "",
    val title: String = "",
    val by: String = "",
    val undid: Long? = null,
    val diff: String = "",
)

@Serializable
data class BehaviorResult(val behavior: Behavior? = null, val change: BehaviorChange? = null)

@Serializable
data class BehaviorPreview(val id: String = "", val diff: String = "")

@Serializable
data class BehaviorHistory(val changes: List<BehaviorChange> = emptyList())

/** What may change in an edit; null fields stay as they are. */
data class BehaviorPatch(
    val title: String? = null,
    val category: String? = null,
    val summary: String? = null,
    val text: String? = null,
    val enabled: Boolean? = null,
) {
    fun json(): String = buildJsonObject {
        title?.let { put("title", JsonPrimitive(it)) }
        category?.let { put("category", JsonPrimitive(it)) }
        summary?.let { put("summary", JsonPrimitive(it)) }
        text?.let { put("text", JsonPrimitive(it)) }
        enabled?.let { put("enabled", JsonPrimitive(it)) }
    }.toString()
}

/** One line of a diff: the same, added or removed. */
data class DiffLine(val kind: Kind, val text: String) {
    enum class Kind { Same, Added, Removed }
}

object BehaviorsApi {
    /** The tower's categories, in its order (used when it doesn't send them). */
    val CATEGORIES = listOf("Talking", "Sessions", "Spaces and focus", "Accounts", "Releases and safety", "Questions 90/10", "Screenshots", "Browsers", "Misc")

    /** A diff as the tower writes it (`+ `, `- `, `  ` before each line). */
    fun diff(text: String): List<DiffLine> = text.lines().filter { it.isNotEmpty() }.map { l ->
        when {
            l.startsWith("+ ") || l == "+" -> DiffLine(DiffLine.Kind.Added, l.drop(2))
            l.startsWith("- ") || l == "-" -> DiffLine(DiffLine.Kind.Removed, l.drop(2))
            else -> DiffLine(DiffLine.Kind.Same, l.removePrefix("  "))
        }
    }

    /** Only the lines that changed, with [context] unchanged lines around each. */
    fun changedOnly(lines: List<DiffLine>, context: Int = 1): List<DiffLine?> {
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { i, l ->
            if (l.kind != DiffLine.Kind.Same) for (k in (i - context)..(i + context)) if (k in lines.indices) keep[k] = true
        }
        val out = mutableListOf<DiffLine?>()
        var gap = false
        lines.forEachIndexed { i, l ->
            if (keep[i]) {
                if (gap) out += null // "…"
                out += l
                gap = false
            } else gap = true
        }
        return out
    }

    /** Changes the tower made setting itself up (seeding, migrating notes): not shown, never offered for undo. */
    private val SETUP = setOf("seed", "migrate")

    /** The history worth showing: changes by the user or the brain. */
    fun visible(history: List<BehaviorChange>): List<BehaviorChange> = history.filter { it.op !in SETUP }

    /** Made by the tower setting itself up (seeding, migrating): `by: tower`, or a setup op. */
    fun isSetup(c: BehaviorChange): Boolean = c.op in SETUP || c.by == "tower"

    /** Who made a change, in words. */
    fun byWord(c: BehaviorChange): String = when (c.by) {
        "brain" -> "by the tower"
        "tower" -> "set up by the tower"
        else -> "by you"
    }

    /** What the tower's undo would undo: the newest change not undone yet (history newest first). */
    fun undoTarget(history: List<BehaviorChange>): BehaviorChange? {
        val undone = history.mapNotNull { it.undid }.toSet()
        return history.firstOrNull { it.op != "undo" && it.at !in undone }
    }

    /** Whether Undo makes sense: what it would undo is a change the user or the brain made (never the tower's setup). */
    fun canUndo(history: List<BehaviorChange>): Boolean = undoTarget(history)?.let { !isSetup(it) } ?: false

    /** The change in words: "Added", "Changed", "Turned off", … */
    fun verb(op: String): String = when (op) {
        "add" -> "Added"
        "edit" -> "Changed"
        "enable" -> "Turned on"
        "disable" -> "Turned off"
        "remove" -> "Removed"
        "undo" -> "Undid a change to"
        else -> op.replaceFirstChar { it.uppercase() }
    }

    /** Who a source is, for its badge. */
    fun sourceWord(source: String): String = when (source) {
        "builtin" -> "Built in"
        "learned" -> "Learned"
        else -> "You"
    }

    /** Behaviors matching [query] (title, summary or text) and [filter] (`all`, `on`, `off`, `builtin`, `user`, `learned`). */
    fun filter(all: List<Behavior>, query: String, filter: String): List<Behavior> {
        val q = query.trim().lowercase()
        return all.filter { b ->
            (q.isEmpty() || listOf(b.title, b.summary, b.text, b.category).any { it.lowercase().contains(q) }) && when (filter) {
                "on" -> b.enabled
                "off" -> !b.enabled
                "builtin", "user", "learned" -> b.source == filter
                else -> true
            }
        }
    }

    /** The behaviors under each category, in the tower's order (unknown categories at the end). */
    fun byCategory(list: List<Behavior>, categories: List<String>): List<Pair<String, List<Behavior>>> {
        val order = categories.ifEmpty { CATEGORIES }
        val groups = list.groupBy { it.category }
        return (order + (groups.keys - order.toSet()).sorted()).mapNotNull { c -> groups[c]?.sortedBy { it.title.lowercase() }?.let { c to it } }
    }

    /** The behavior a chat line ("Added a behavior: Release dev then prod") is about, by its title. */
    fun fromChatLine(text: String, all: List<Behavior>): Behavior? {
        val title = text.substringAfter("behavior: ", "").trim()
        return all.firstOrNull { it.title.equals(title, ignoreCase = true) }
    }
}
