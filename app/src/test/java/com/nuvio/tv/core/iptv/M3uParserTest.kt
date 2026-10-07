package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    @Test
    fun `display name keeps its own commas`() {
        val channels = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="rai1.it" group-title="Generalisti, HD",Rai 1, HD
            http://example.com/rai1.m3u8
            """.trimIndent()
        )
        assertEquals("Rai 1, HD", channels.single().name)
        assertEquals("Generalisti, HD", channels.single().groupTitle)
    }

    @Test
    fun `kodi style url options become headers and are stripped from the url`() {
        val channel = M3uParser.parse(
            """
            #EXTINF:-1 tvg-id="x",X
            http://example.com/x.m3u8|User-Agent=Mozilla%2F5.0&Referer=https://site.example/&verifypeer=false
            """.trimIndent()
        ).single()
        assertEquals("http://example.com/x.m3u8", channel.streamUrl)
        assertEquals(
            mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://site.example/"),
            channel.headers
        )
    }

    @Test
    fun `extinf attributes, vlcopt and exthttp headers are all read`() {
        val channel = M3uParser.parse(
            """
            #EXTINF:-1 http-user-agent="UA-attr" http-referrer="https://ref.example/",Y
            #EXTVLCOPT:http-origin=https://origin.example
            #EXTHTTP:{"Cookie":"a=1","User-Agent":"UA-json"}
            http://example.com/y.m3u8
            """.trimIndent()
        ).single()
        assertEquals(
            mapOf(
                "User-Agent" to "UA-json",
                "Referer" to "https://ref.example/",
                "Origin" to "https://origin.example",
                "Cookie" to "a=1"
            ),
            channel.headers
        )
    }

    @Test
    fun `headers do not leak into the next channel`() {
        val channels = M3uParser.parse(
            """
            #EXTINF:-1,A
            #EXTVLCOPT:http-user-agent=UA-a
            http://example.com/a.m3u8
            #EXTINF:-1,B
            http://example.com/b.m3u8
            """.trimIndent()
        )
        assertEquals(mapOf("User-Agent" to "UA-a"), channels[0].headers)
        assertNull(channels[1].headers)
    }
}
