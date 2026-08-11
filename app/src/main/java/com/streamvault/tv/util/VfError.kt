package com.streamvault.tv.util

/**
 * Stable error codes for easier Fire-TV debugging (show in Toast/UI).
 * Format: VF-XXX
 */
class VfException(
    val code: String,
    message: String,
    cause: Throwable? = null
) : Exception("[$code] $message", cause) {
    companion object {
        fun of(code: String, message: String, cause: Throwable? = null) =
            VfException(code, message, cause)
    }
}

object VfCodes {
    const val CATALOG_UNREACHABLE = "VF-101"
    const val CATALOG_EMPTY = "VF-102"
    const val CATALOG_PARSE = "VF-103"
    const val SERIES_NOT_FOUND = "VF-201"
    const val SEASONS_LOAD = "VF-202"
    const val EPISODE_NOT_FOUND = "VF-203"
    const val STREAM_MISSING = "VF-301"
    const val STREAM_RESOLVE = "VF-302"
    const val PLAYER_WEB = "VF-303"
    const val PLAYER_HLS = "VF-304"
    const val META_TVMAZE = "VF-401"
    const val UPDATE_CHECK = "VF-501"
    const val UPDATE_DOWNLOAD = "VF-502"
    const val NETWORK = "VF-901"
    const val UNKNOWN = "VF-999"
}

fun Throwable.isCancellation(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        val name = t::class.java.name
        if (name.contains("CancellationException") || name.contains("JobCancellationException")) {
            return true
        }
        val msg = t.message.orEmpty()
        if (msg.contains("Job was cancelled", ignoreCase = true) ||
            msg.contains("was cancelled", ignoreCase = true) && name.contains("Coroutine")
        ) {
            return true
        }
        t = t.cause
    }
    return false
}

/** UI-facing message; cancellations are silent (empty). */
fun Throwable.toVfMessage(): String {
    if (isCancellation()) return ""
    if (this is VfException) return message ?: "[${this.code}] Fehler"
    val msg = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
    return if (msg.startsWith("[VF-")) msg else "[${VfCodes.UNKNOWN}] $msg"
}
