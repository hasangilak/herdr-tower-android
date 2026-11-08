package dev.herdertower.phone.api

import java.io.IOException

/** The tower answered with an error (`{"error": {"code", "message"}}`). */
open class TowerApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val triesLeft: Int? = null,
) : IOException(message)

/** 401: the token is missing, unknown or revoked. The phone must pair again. */
class UnauthorizedException(code: String, message: String) : TowerApiException(401, code, message)

/** The tower couldn't be reached (no route, refused, timed out). */
class UnreachableException(message: String, cause: Throwable?) : IOException(message, cause)
