package com.nuvio.tv.core.iptv

import com.nuvio.tv.domain.model.IptvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvZapListTest {

    private fun channel(n: Int) = IptvChannel(
        id = "c$n", name = "Ch $n", logoUrl = null, groupTitle = "G",
        streamUrl = "http://example.com/$n.m3u8", epgId = null
    )

    @Test
    fun `steps forward and backward and wraps around both ends`() {
        IptvZapList.set(listOf(channel(1), channel(2), channel(3)))
        assertEquals("Ch 2", IptvZapList.step("http://example.com/1.m3u8", 1)?.name)
        assertEquals("Ch 1", IptvZapList.step("http://example.com/3.m3u8", 1)?.name)
        assertEquals("Ch 3", IptvZapList.step("http://example.com/1.m3u8", -1)?.name)
    }

    @Test
    fun `no zapping for unknown streams or single-channel lists`() {
        IptvZapList.set(listOf(channel(1), channel(2)))
        assertFalse(IptvZapList.contains("http://example.com/other.m3u8"))
        assertNull(IptvZapList.step("http://example.com/other.m3u8", 1))

        IptvZapList.set(listOf(channel(1)))
        assertFalse(IptvZapList.contains("http://example.com/1.m3u8"))

        IptvZapList.set(listOf(channel(1), channel(2)))
        assertTrue(IptvZapList.contains("http://example.com/2.m3u8"))
    }
}
