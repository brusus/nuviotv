package com.nuvio.tv.core.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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
// Cloudflare usually grants clearance for hours; a rejected one is detected (re-challenge)
// and replaced anyway, so there is no point in asking the user again every half hour.
private const val CLEARANCE_TTL_MS = 6 * 60 * 60_000L
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
        // Whatever had D-pad focus before the overlay took it: handed back when it closes,
        // otherwise focus dies with the removed pointer layer and the remote does nothing
        // until Back is pressed.
        var focusBeforeReveal: View? = null
        var revealed = false
        try {
            cookieManager.setAcceptThirdPartyCookies(webView, true)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = webViewUserAgent
            }
            webView.webViewClient = WebViewClient()
            if (overlay != null) container.addView(overlay.root)
            webView.loadUrl(url)

            val startedAt = System.currentTimeMillis()
            val timeoutMs = if (overlay != null) INTERACTIVE_TIMEOUT_MS else SOLVE_TIMEOUT_MS
            while (System.currentTimeMillis() - startedAt < timeoutMs) {
                delay(COOKIE_POLL_INTERVAL_MS)
                if (overlay != null && overlay.cursorLayer.cancelled) {
                    Log.i(TAG, "Challenge for $host cancelled by the user")
                    return@withContext null
                }
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
                    focusBeforeReveal = activity?.currentFocus
                    overlay.root.alpha = 1f
                    overlay.root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    overlay.cursorLayer.requestFocus()
                }
            }
            null
        } finally {
            if (overlay != null) {
                container.removeView(overlay.root)
                if (revealed) {
                    val restored = focusBeforeReveal?.takeIf { it.isAttachedToWindow }?.requestFocus() == true
                    if (!restored) container.getChildAt(0)?.requestFocus()
                }
            }
            webView.stopLoading()
            webView.destroy()
        }
    }

    private class OverlayViews(val root: FrameLayout, val cursorLayer: CursorLayer)

    /** Full-screen layer: invisible and unfocusable until revealed, then a dimmed card. */
    private fun buildOverlay(context: Context, host: String, webView: WebView): OverlayViews {
        val density = context.resources.displayMetrics.density
        val cursorLayer = CursorLayer(context, webView)
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
            addView(cursorLayer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val root = FrameLayout(context).apply {
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
        return OverlayViews(root, cursorLayer)
    }

    /**
     * Holds the WebView plus an on-screen pointer driven by the remote, like TV browsers:
     * D-pad moves it, OK taps where it points. The check box lives in a cross-origin iframe
     * that D-pad focus navigation can't enter, so this is how a TV user ticks it - the tap
     * is still the user's own action. Back cancels the check.
     */
    private class CursorLayer(context: Context, private val webView: WebView) : FrameLayout(context) {
        private val density = context.resources.displayMetrics.density
        private val cursorSize = (22 * density).toInt()
        private val cursor = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x66FFFFFF)
                setStroke((3 * density).toInt(), 0xFFE53935.toInt())
            }
        }

        @Volatile
        var cancelled = false
            private set

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            webView.isFocusable = false
            addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(cursor, LayoutParams(cursorSize, cursorSize))
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (oldw == 0 && oldh == 0) placeCursor(w / 2f, h / 2f)
        }

        private fun placeCursor(x: Float, y: Float) {
            cursor.x = x.coerceIn(0f, (width - 1).toFloat()) - cursorSize / 2f
            cursor.y = y.coerceIn(0f, (height - 1).toFloat()) - cursorSize / 2f
        }

        private val cursorCenterX get() = cursor.x + cursorSize / 2f
        private val cursorCenterY get() = cursor.y + cursorSize / 2f

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val step = 14 * density * (1 + (event.repeatCount / 3).coerceAtMost(5))
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val dx = when (event.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> -step
                            KeyEvent.KEYCODE_DPAD_RIGHT -> step
                            else -> 0f
                        }
                        val dy = when (event.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> -step
                            KeyEvent.KEYCODE_DPAD_DOWN -> step
                            else -> 0f
                        }
                        // At the top/bottom edge the pointer stays put and the page scrolls
                        // instead, so content below the fold (the check box) is reachable.
                        if (dx != 0f) {
                            placeCursor(cursorCenterX + dx, cursorCenterY)
                        } else {
                            val targetY = cursorCenterY + dy
                            val edge = cursorSize.toFloat()
                            if ((dy > 0 && targetY > height - edge) || (dy < 0 && targetY < edge)) {
                                scrollPage(dy)
                            } else {
                                placeCursor(cursorCenterX, targetY)
                            }
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.action == KeyEvent.ACTION_UP) tapAtCursor()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_UP) cancelled = true
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        private fun scrollPage(dy: Float) {
            // Native scroll covers most pages; the JS one covers pages that scroll an inner
            // document the WebView's own scroll position doesn't track.
            webView.scrollBy(0, dy.toInt())
            val cssPixels = (dy / density).toInt()
            webView.evaluateJavascript("window.scrollBy(0, $cssPixels);", null)
        }

        private fun tapAtCursor() {
            val x = cursorCenterX
            val y = cursorCenterY
            val downTime = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { i, action ->
                val event = MotionEvent.obtain(downTime, downTime + i * 50L, action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                webView.dispatchTouchEvent(event)
                event.recycle()
            }
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
