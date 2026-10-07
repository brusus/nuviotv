package com.nuvio.tv.core.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.nuvio.tv.R
import com.nuvio.tv.core.util.ForegroundActivityTracker
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "CfChallengeSolver"
private const val SOLVE_TIMEOUT_MS = 20_000L
private const val INTERACTIVE_TIMEOUT_MS = 45_000L
private const val REVEAL_AFTER_MS = 6_000L
private const val COOKIE_POLL_INTERVAL_MS = 500L
private const val FAILURE_BACKOFF_MS = 10 * 60_000L
private const val CLEARANCE_TTL_MS = 25 * 60_000L
private const val RECHALLENGE_LOOP_WINDOW_MS = 30_000L

/**
 * Clears Cloudflare's "Just a moment..." browser check for plugin requests. Scrapers fetch
 * over plain HTTP and can't run the challenge's JavaScript; an offscreen WebView can. Once
 * it obtains `cf_clearance`, that cookie - which Cloudflare binds to the IP *and* the
 * User-Agent that solved it - is replayed on the scraper's requests together with the
 * WebView's own User-Agent.
 *
 * Interactive challenges (Turnstile "verify you are human") can't be solved headlessly:
 * after [SOLVE_TIMEOUT_MS] the host is given up on for [FAILURE_BACKOFF_MS], so searches
 * don't stall on every request to it.
 */
class CloudflareChallengeSolver(private val context: Context) {

    data class Clearance(val cookieHeader: String, val userAgent: String, val obtainedAtMs: Long)

    private val clearances = ConcurrentHashMap<String, Clearance>()
    private val failedUntil = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Clearance?>>()

    private val webViewUserAgent: String by lazy {
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrElse { FALLBACK_USER_AGENT }
    }

    /** Stored clearance for [url]'s host, if one was obtained recently. */
    fun clearanceFor(url: String): Clearance? {
        val host = hostOf(url) ?: return null
        val clearance = clearances[host] ?: return null
        if (System.currentTimeMillis() - clearance.obtainedAtMs > CLEARANCE_TTL_MS) {
            clearances.remove(host)
            return null
        }
        return clearance
    }

    /**
     * All cookies currently held for [url] (cf_clearance plus anything the site set since),
     * for hosts with a clearance. Sites behind Cloudflare often also need their own session
     * cookie (set by a page, required by their API), so the WebView's cookie store doubles
     * as the cookie jar for scraper requests to those hosts.
     */
    fun cookieHeaderFor(url: String): String? {
        if (clearanceFor(url) == null) return null
        return runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Keeps cookies a cleared host sets on scraper responses (e.g. its session cookie). */
    fun storeResponseCookies(url: String, setCookieHeaders: List<String>) {
        if (setCookieHeaders.isEmpty() || clearanceFor(url) == null) return
        runCatching {
            val cookieManager = CookieManager.getInstance()
            setCookieHeaders.forEach { cookieManager.setCookie(url, it) }
            cookieManager.flush()
        }
    }

    /**
     * Drops a clearance that no longer works (the site challenged us again despite it).
     * If it was obtained moments ago, the challenge just keeps coming back (e.g. bound to a
     * different exit IP): back off instead of looping solve -> challenge -> solve.
     */
    fun invalidate(url: String) {
        val host = hostOf(url) ?: return
        val dropped = clearances.remove(host) ?: return
        if (System.currentTimeMillis() - dropped.obtainedAtMs < RECHALLENGE_LOOP_WINDOW_MS) {
            failedUntil[host] = System.currentTimeMillis() + FAILURE_BACKOFF_MS
            Log.w(TAG, "$host challenged again right after clearing: backing off")
        }
    }

    /** Expires the stored cf_clearance so a new solve can't mistake a stale one for success. */
    private fun clearStaleClearanceCookie(cookieManager: CookieManager, url: String) {
        val existing = cookieManager.getCookie(url).orEmpty()
        if (!existing.contains("cf_clearance=")) return
        val host = hostOf(url) ?: return
        listOf(host, ".$host", ".${host.substringAfter('.')}").forEach { domain ->
            cookieManager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/; Domain=$domain")
        }
        cookieManager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/")
        cookieManager.flush()
    }

    /**
     * Solves the challenge for [url] and returns the clearance, or null if it could not be
     * obtained (interactive challenge, timeout, recent failure). Blocks the calling thread,
     * which must not be the main thread: the WebView itself runs on the main thread.
     * Concurrent calls for the same host share one WebView.
     */
    fun solveBlocking(url: String): Clearance? {
        val host = hostOf(url) ?: return null
        clearanceFor(url)?.let { return it }
        val blockedUntil = failedUntil[host]
        if (blockedUntil != null && System.currentTimeMillis() < blockedUntil) return null

        val deferred = CompletableDeferred<Clearance?>()
        val existing = inFlight.putIfAbsent(host, deferred)
        if (existing != null) return runBlocking { existing.await() }

        val result = try {
            runBlocking { withTimeoutOrNull(INTERACTIVE_TIMEOUT_MS + 5_000L) { solveOnMainThread(url, host) } }
        } catch (e: Exception) {
            Log.w(TAG, "Challenge solve failed for $host: ${e.message}")
            null
        }
        if (result != null) {
            clearances[host] = result
            failedUntil.remove(host)
            Log.i(TAG, "Cloudflare challenge cleared for $host")
        } else {
            failedUntil[host] = System.currentTimeMillis() + FAILURE_BACKOFF_MS
            Log.w(TAG, "Could not clear Cloudflare challenge for $host (interactive or timed out)")
        }
        inFlight.remove(host)
        deferred.complete(result)
        return result
    }

    /**
     * Runs the challenge in a WebView attached to the foreground activity: a detached
     * WebView throttles timers/animation frames and Cloudflare's check may never finish.
     * It starts invisible; if the check hasn't passed after [REVEAL_AFTER_MS] it is likely
     * interactive, so the WebView is shown for the user to tick the box with the remote.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solveOnMainThread(url: String, host: String): Clearance? = withContext(Dispatchers.Main) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        // We only get here because the site challenged us: any cf_clearance still stored is
        // rejected, and seeing it would end the poll below at once without a real solve.
        clearStaleClearanceCookie(cookieManager, url)
        val activity = ForegroundActivityTracker.current
        val container = activity?.findViewById<ViewGroup>(android.R.id.content)
        val webView = WebView(activity ?: context)
        val overlay = container?.let { buildOverlay(it.context, host, webView) }
        try {
            cookieManager.setAcceptThirdPartyCookies(webView, true)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = webViewUserAgent
            }
            webView.webViewClient = WebViewClient()
            if (overlay != null) container.addView(overlay)
            webView.loadUrl(url)

            val startedAt = System.currentTimeMillis()
            val timeoutMs = if (overlay != null) INTERACTIVE_TIMEOUT_MS else SOLVE_TIMEOUT_MS
            var revealed = false
            while (System.currentTimeMillis() - startedAt < timeoutMs) {
                delay(COOKIE_POLL_INTERVAL_MS)
                val cookies = cookieManager.getCookie(url).orEmpty()
                if (cookies.contains("cf_clearance=")) {
                    cookieManager.flush()
                    return@withContext Clearance(
                        cookieHeader = cookies,
                        userAgent = webViewUserAgent,
                        obtainedAtMs = System.currentTimeMillis()
                    )
                }
                if (overlay != null && !revealed && System.currentTimeMillis() - startedAt > REVEAL_AFTER_MS) {
                    revealed = true
                    Log.i(TAG, "Challenge for $host not passed automatically: showing it to the user")
                    overlay.alpha = 1f
                    overlay.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    webView.requestFocus()
                }
            }
            null
        } finally {
            if (overlay != null) container.removeView(overlay)
            webView.stopLoading()
            webView.destroy()
        }
    }

    /** Full-screen layer: invisible and unfocusable until revealed, then a dimmed card. */
    private fun buildOverlay(context: Context, host: String, webView: WebView): FrameLayout {
        val density = context.resources.displayMetrics.density
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF15161A.toInt())
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            addView(TextView(context).apply {
                text = context.getString(R.string.cf_challenge_title, host)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 18f
                setPadding(0, 0, 0, (10 * density).toInt())
            })
            addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return FrameLayout(context).apply {
            setBackgroundColor(0xCC000000.toInt())
            alpha = 0f
            // Until revealed it must not steal D-pad focus from the screen underneath.
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(card, FrameLayout.LayoutParams(
                (context.resources.displayMetrics.widthPixels * 0.7f).toInt(),
                (context.resources.displayMetrics.heightPixels * 0.75f).toInt(),
                Gravity.CENTER
            ))
        }
    }

    companion object {
        private const val FALLBACK_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

        /** Whether a response is Cloudflare's JavaScript challenge rather than real content. */
        fun isChallengeResponse(statusCode: Int, body: String): Boolean {
            if (statusCode != 403 && statusCode != 503) return false
            val head = body.take(8_000)
            return head.contains("Just a moment", ignoreCase = true) ||
                head.contains("cf_chl_opt") ||
                head.contains("challenge-platform")
        }
    }
}
