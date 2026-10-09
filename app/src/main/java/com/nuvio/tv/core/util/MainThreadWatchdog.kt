package com.nuvio.tv.core.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Debug-build aid for "the app froze" reports: when the main thread stops answering for
 * longer than [BLOCK_THRESHOLD_MS], logs what it is doing so the freeze can be traced to a
 * line of code from logcat alone.
 */
object MainThreadWatchdog {
    private const val TAG = "MainThreadWatchdog"
    private const val PING_INTERVAL_MS = 400L
    private const val BLOCK_THRESHOLD_MS = 1_200L
    private const val MAX_FRAMES = 28

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        val mainLooper = Looper.getMainLooper()
        val mainHandler = Handler(mainLooper)
        Thread({
            while (true) {
                val answered = java.util.concurrent.atomic.AtomicBoolean(false)
                val postedAt = SystemClock.uptimeMillis()
                mainHandler.post { answered.set(true) }
                var reported = false
                while (!answered.get()) {
                    Thread.sleep(PING_INTERVAL_MS)
                    val blockedFor = SystemClock.uptimeMillis() - postedAt
                    if (!answered.get() && !reported && blockedFor >= BLOCK_THRESHOLD_MS) {
                        reported = true
                        val stack = mainLooper.thread.stackTrace.take(MAX_FRAMES)
                            .joinToString("\n") { "    at $it" }
                        Log.w(TAG, "Main thread blocked for ${blockedFor}ms:\n$stack")
                    }
                }
                if (reported) {
                    Log.w(TAG, "Main thread responsive again after ${SystemClock.uptimeMillis() - postedAt}ms")
                }
                Thread.sleep(PING_INTERVAL_MS)
            }
        }, "main-thread-watchdog").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }
}
