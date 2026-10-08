package com.nuvio.tv.core.plugin

/**
 * Outcome of trying a scraper's saved account right after the user enters it.
 *
 * A scraper that supports login reports it by logging one line, `[NUVIO_LOGIN] ok`,
 * `[NUVIO_LOGIN] rejected` (wrong email/password) or `[NUVIO_LOGIN] error`; a scraper
 * that logs none of them leaves the result [UNKNOWN].
 */
enum class ScraperLoginCheck {
    OK,
    REJECTED,
    UNKNOWN;

    companion object {
        private const val MARKER = "[NUVIO_LOGIN]"

        /** Reads one scraper log line; null when it is not a login report. */
        fun fromLogLine(line: String): ScraperLoginCheck? {
            val index = line.indexOf(MARKER)
            if (index < 0) return null
            return when (line.substring(index + MARKER.length).trim().lowercase()) {
                "ok" -> OK
                "rejected" -> REJECTED
                else -> UNKNOWN
            }
        }
    }
}
