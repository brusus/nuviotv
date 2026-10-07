package com.nuvio.tv.core.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

class ConfigServerAccessTest {

    private lateinit var server: DebridFormatterConfigServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        val port = ServerSocket(0).use { it.localPort }
        server = DebridFormatterConfigServer(
            currentSettingsProvider = { DebridFormatterSettings(nameTemplate = "", descriptionTemplate = "") },
            onSettingsChanged = {},
            port = port
        )
        server.start(5_000, false)
        baseUrl = "http://127.0.0.1:$port"
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun get(url: String, cookie: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            cookie?.let { setRequestProperty("Cookie", it) }
            connect()
        }

    @Test
    fun `requests without the token are refused`() {
        assertEquals(403, get("$baseUrl/api/settings").responseCode)
        assertEquals(403, get("$baseUrl/?t=wrongtoken").responseCode)
    }

    @Test
    fun `qr url grants access and sets a cookie that authorizes follow-up calls`() {
        val page = get(server.accessUrl(baseUrl))
        assertEquals(200, page.responseCode)
        val setCookie = page.getHeaderField("Set-Cookie")
        assertNotNull(setCookie)

        val cookie = setCookie.substringBefore(';')
        assertEquals(200, get("$baseUrl/api/settings", cookie).responseCode)
    }
}
