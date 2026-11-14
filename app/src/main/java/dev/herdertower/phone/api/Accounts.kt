package dev.herdertower.phone.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Each Claude account's usage and what the account failover moved (a tower
 * with `accounts`: the state's `accounts`, herder-tower docs/mobile-api.md,
 * "Accounts and usage"). Null until the tower's first look.
 */
@Serializable
data class AccountsBoard(
    val list: List<AccountUsage> = emptyList(),
    /** The account a new session starts on. */
    @SerialName("starts_on") val startsOn: String? = null,
    /** The last week's moves, oldest first. */
    val moves: List<AccountMove> = emptyList(),
    /** Sessions being moved now (addresses). */
    val moving: List<String> = emptyList(),
    /** Sessions that wait because every account is out. */
    val waiting: List<AccountWait> = emptyList(),
)

/** One account: its 5-hour window (null: it has none, show no row) and its 7-day one, and whether it is out and why. */
@Serializable
data class AccountUsage(
    val name: String = "",
    val email: String? = null,
    val default: Boolean = false,
    @SerialName("five_hour") val fiveHour: UsageWindow? = null,
    @SerialName("seven_day") val sevenDay: UsageWindow? = null,
    @SerialName("out_until_ms") val outUntilMs: Long? = null,
    @SerialName("out_why") val outWhy: String? = null,
)

/** A usage window: [used] percent of its limit, when it resets (null: past its reset, or not said). */
@Serializable
data class UsageWindow(val used: Double = 0.0, @SerialName("resets_ms") val resetsMs: Long? = null)

/** A move the failover made: who, from which account to which, why; [how] `moved` or `failed`. */
@Serializable
data class AccountMove(
    @SerialName("at_ms") val atMs: Long = 0,
    val address: String = "",
    val name: String? = null,
    val space: String? = null,
    /** The tower it is (`tower`, `tower-<space>`), when it is one. */
    val tower: String? = null,
    val from: String = "",
    val to: String = "",
    val how: String = "",
    val why: String? = null,
)

/** A session that waits for an account to come back. */
@Serializable
data class AccountWait(
    val address: String = "",
    val account: String? = null,
    @SerialName("since_ms") val sinceMs: Long = 0,
    val why: String? = null,
)

/** A tower that runs (the main one, or a space's own), as the state's `brains` lists it. */
@Serializable
data class StateBrain(
    val name: String = "",
    val space: String? = null,
    val address: String? = null,
    val status: String = "",
    val model: String? = null,
    val effort: String? = null,
    val account: String? = null,
)

object AccountsApi {
    /** The state's `accounts`, read on its own: a board the app can't read never breaks the state. */
    fun parse(e: kotlinx.serialization.json.JsonElement?): AccountsBoard? =
        e?.takeIf { it is kotlinx.serialization.json.JsonObject }?.let { runCatching { TowerJson.decodeFromJsonElement(AccountsBoard.serializer(), it) }.getOrNull() }
    /** Amber from here. */
    const val HIGH = 80.0

    /**
     * The windows an account shows, as the Mac's: the 5-hour one only when
     * it has one, then the 7-day one (always: "–" when nothing is recorded).
     */
    fun rows(a: AccountUsage): List<Pair<String, UsageWindow?>> =
        listOfNotNull(a.fiveHour?.let { "5h" to it }, "7d" to a.sevenDay)

    /** The window nearest its limit (what a one-line strip draws): the 5-hour one unless the 7-day one is fuller or the only one. */
    fun fullest(a: AccountUsage): Pair<String, UsageWindow>? {
        val five = a.fiveHour
        val seven = a.sevenDay
        return when {
            five == null && seven == null -> null
            five == null -> "7d" to seven!!
            seven == null -> "5h" to five
            seven.used > five.used -> "7d" to seven
            else -> "5h" to five
        }
    }

    /** Out now (the failover's book, or a window at its limit), at [now]. */
    fun out(a: AccountUsage, now: Long): Boolean = a.outUntilMs?.let { it > now } == true

    /** "out until 15:00 (5-hour limit)", or null while it has usage. [clock] says a time. */
    fun outLine(a: AccountUsage, now: Long, clock: (Long) -> String): String? {
        val until = a.outUntilMs?.takeIf { it > now } ?: return null
        return "out until ${clock(until)}" + (a.outWhy?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
    }

    /** "42%". */
    fun percent(w: UsageWindow?): String = w?.let { "${it.used.toInt().coerceIn(0, 100)}%" } ?: "–"

    /** "↻15:00", when it resets; "" when not said. */
    fun resets(w: UsageWindow?, clock: (Long) -> String): String = w?.resetsMs?.let { "↻${clock(it)}" }.orEmpty()

    /**
     * A seat the failover moved: the last of [moves] for its [address] is a
     * move (not a failure) to the account it runs on now. Null otherwise.
     */
    fun movedTo(board: AccountsBoard?, address: String?, account: String?): AccountMove? {
        if (board == null || address.isNullOrBlank()) return null
        val last = board.moves.lastOrNull { it.address == address } ?: return null
        return last.takeIf { it.how == "moved" && (account == null || it.to == account) }
    }

    /** "moved from default at 15:02". */
    fun movedLine(m: AccountMove, clock: (Long) -> String): String =
        "moved from ${m.from}" + (if (m.atMs > 0) " at ${clock(m.atMs)}" else "")

    /** What the failover does with a seat now: "moving to another account", "waits: every account is out". */
    fun now(board: AccountsBoard?, address: String?): String? {
        if (board == null || address.isNullOrBlank()) return null
        if (address in board.moving) return "moving to another account"
        board.waiting.firstOrNull { it.address == address }?.let { return "waits: every account is out" }
        return null
    }

    /** A seat's account line: "orca · moved from default at 15:02", "default", or null when its account isn't known. */
    fun seat(board: AccountsBoard?, address: String?, account: String?, clock: (Long) -> String): String? {
        val acc = account?.takeIf { it.isNotBlank() } ?: return now(board, address)
        val extra = now(board, address) ?: movedTo(board, address, acc)?.let { movedLine(it, clock) }
        return listOfNotNull(acc, extra).joinToString(" · ")
    }
}
