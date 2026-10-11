package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IptvStreamSourceTest {

    @Test
    fun `provider names and catalogue titles normalise to the same text`() {
        assertEquals("matchbox il film", IptvStreamSource.normalizeTitle("Matchbox: Il film (2026)"))
        assertEquals("matchbox il film", IptvStreamSource.normalizeTitle("Matchbox - Il Film"))
        assertEquals("fuori di cresta", IptvStreamSource.normalizeTitle("Fuori di cresta (1998) [FHD]"))
        assertEquals("l era glaciale 2 il disgelo", IptvStreamSource.normalizeTitle("L'era glaciale 2 – Il disgelo 4K HDR"))
        assertEquals("amelie", IptvStreamSource.normalizeTitle("Amélie"))
    }

    @Test
    fun `quality is read from the tags a provider puts in names and categories`() {
        assertEquals("4K", IptvStreamSource.qualityHint("The Host (2006) 4K"))
        assertEquals("4K", IptvStreamSource.qualityHint("Film UHD 2160p"))
        assertEquals("1080p", IptvStreamSource.qualityHint("Sky PrimaFila FHD"))
        assertEquals("720p", IptvStreamSource.qualityHint("Serie 720p"))
        assertNull(IptvStreamSource.qualityHint("Disney+"))
        // "HD" alone is too vague to promise a resolution.
        assertNull(IptvStreamSource.qualityHint("Top Italia HD"))
    }

    @Test
    fun `quality falls back to the reported video height`() {
        assertEquals("4K", IptvStreamSource.qualityFromHeight(2160))
        assertEquals("1080p", IptvStreamSource.qualityFromHeight(1080))
        assertEquals("720p", IptvStreamSource.qualityFromHeight(720))
        assertEquals("SD", IptvStreamSource.qualityFromHeight(480))
        assertNull(IptvStreamSource.qualityFromHeight(0))
    }
}
