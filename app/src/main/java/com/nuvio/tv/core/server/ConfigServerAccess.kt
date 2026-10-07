package com.nuvio.tv.core.server

import fi.iki.elonen.NanoHTTPD
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Access gate for the LAN config servers (addons, plugin repositories, stream badges,
 * debrid formatter). They listen on every interface while their QR screen is open, so
 * without this anyone on the same network could read the addon URLs (which often embed
 * debrid API keys) or change settings.
 *
 * Each server instance gets a random token that only travels inside the QR code. The page
 * opened from the QR carries it as a query parameter; the server then stores it in a cookie,
 * so the page's own API calls send it automatically and the web pages need no changes.
 * A new server - i.e. every time the QR screen opens - gets a new token.
 */
internal class ConfigServerAccess {

    val token: String = randomToken()

    /** URL to encode in the QR code: [baseUrl] plus the access token. */
    fun accessUrl(baseUrl: String): String = "${baseUrl.trimEnd('/')}/?$QUERY_PARAM=$token"

    /** A 403 response if [session] carries no valid token, or null when access is allowed. */
    fun rejectIfUnauthorized(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val fromQuery = session.parameters[QUERY_PARAM]?.firstOrNull()
        val fromCookie = session.cookies.read(COOKIE_NAME)
        if (matches(fromQuery) || matches(fromCookie)) return null
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.FORBIDDEN,
            NanoHTTPD.MIME_PLAINTEXT,
            "Access denied: open this page by scanning the QR code shown on the TV."
        )
    }

    /** Attaches the session cookie so follow-up requests from the page are authorized. */
    fun withSessionCookie(response: NanoHTTPD.Response): NanoHTTPD.Response {
        response.addHeader("Set-Cookie", "$COOKIE_NAME=$token; Path=/; HttpOnly; SameSite=Strict")
        return response
    }

    private fun matches(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(), token.toByteArray())

    private companion object {
        const val QUERY_PARAM = "t"
        const val COOKIE_NAME = "nuvio_cfg"
        const val TOKEN_LENGTH = 16
        const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"

        fun randomToken(): String {
            val random = SecureRandom()
            return buildString(TOKEN_LENGTH) {
                repeat(TOKEN_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
            }
        }
    }
}
