package com.nuvio.tv.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogSanitizerTest {

    @Test
    fun `addon config path segment with a debrid key is redacted`() {
        val url = "https://torrentio.strem.fun/realdebrid=SECRETKEY123|sort=quality/stream/movie/tt0133093.json"
        val redacted = LogSanitizer.redact(url)
        assertFalse(redacted.contains("SECRETKEY123"))
        assertEquals("https://torrentio.strem.fun/REDACTED/stream/movie/tt0133093.json", redacted)
    }

    @Test
    fun `long opaque config blob in the path is redacted`() {
        val blob = "eyJkZWJyaWQiOiJyZWFsZGVicmlkIiwia2V5IjoiQUJDREVGIn0"
        val redacted = LogSanitizer.redact("https://addon.example.com/$blob/manifest.json")
        assertEquals("https://addon.example.com/REDACTED/manifest.json", redacted)
    }

    @Test
    fun `plain urls keep host and path`() {
        val url = "https://v3-cinemeta.strem.io/meta/series/tt0944947.json"
        assertEquals(url, LogSanitizer.redact(url))
    }

    @Test
    fun `query parameters are still redacted`() {
        assertEquals(
            "https://api.example.com/v1/list?apikey=REDACTED&page=2",
            LogSanitizer.redact("https://api.example.com/v1/list?apikey=abcd1234&page=2")
        )
    }
}
