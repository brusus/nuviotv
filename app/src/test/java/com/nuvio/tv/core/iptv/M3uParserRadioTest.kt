package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserRadioTest {

    @Test
    fun `radio attribute marks audio-only entries and leaves the others alone`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="r1" radio="true" group-title="Radio",Radio Uno
            http://example.test/radio1
            #EXTINF:-1 tvg-id="t1" group-title="TV",Canale Uno
            http://example.test/tv1
            #EXTINF:-1 tvg-id="t2" radio="false" group-title="TV",Canale Due
            http://example.test/tv2
        """.trimIndent()

        val channels = M3uParser.parse(playlist)

        assertEquals(listOf("Radio Uno", "Canale Uno", "Canale Due"), channels.map { it.name })
        assertTrue(channels[0].isRadio)
        assertFalse(channels[1].isRadio)
        assertFalse(channels[2].isRadio)
    }
}
