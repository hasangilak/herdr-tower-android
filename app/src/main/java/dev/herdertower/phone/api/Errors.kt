package dev.herdertower.phone.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

/**
 * The tower answered with an error (`{"error": {"code", "message", …}}`).
 * [extra] is the whole `error` object: some calls say more beside the code
 * (`state`, `sessions`, `action`, `prs`).
 */
open class TowerApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val triesLeft: Int? = null,
    val extra: JsonObject? = null,
) : IOException(message) {
    /** A word the error carries beside its code (`state`, `action`). */
    fun word(key: String): String? = (extra?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A list the error carries (`sessions`, `prs`), decoded with [read]; empty when it has none. */
    fun <T> list(key: String, read: (kotlinx.serialization.json.JsonElement) -> T?): List<T> =
        (extra?.get(key) as? JsonArray)?.mapNotNull { runCatching { read(it) }.getOrNull() }.orEmpty()
}

/** 401: the token is missing, unknown or revoked. The phone must pair again. */
class UnauthorizedException(code: String, message: String) : TowerApiException(401, code, message)

/** The tower couldn't be reached (no route, refused, timed out). */
class UnreachableException(message: String, cause: Throwable?) : IOException(message, cause)
