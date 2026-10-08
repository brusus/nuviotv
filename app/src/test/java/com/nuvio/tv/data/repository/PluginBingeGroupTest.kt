package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PluginBingeGroupTest {

    private fun group(
        scraperId: String = "repo:streamingcommunity",
        quality: String? = "1080p",
        language: String? = null,
        name: String? = "📡 StreamingCommunity",
        title: String? = "📁 Il Trono di Spade S02E01 | 🇮🇹"
    ) = PluginBingeGroup.of(scraperId, quality, language, name, title)

    @Test
    fun `two episodes of the same provider quality and language share a group`() {
        assertEquals(
            group(title = "📁 Il Trono di Spade S02E01 | 🇮🇹"),
            group(title = "📁 Il Trono di Spade S02E02 | 🇮🇹")
        )
    }

    @Test
    fun `server names that change between episodes do not split the group`() {
        assertEquals(
            group(scraperId = "repo:animesaturn", name = "AnimeSaturn - Srv42", title = "Frieren 1 ITA"),
            group(scraperId = "repo:animesaturn", name = "AnimeSaturn - Srv29", title = "Frieren 2 ITA")
        )
    }

    @Test
    fun `a different quality is a different group`() {
        assertNotEquals(group(quality = "1080p"), group(quality = "720p"))
    }

    @Test
    fun `a different provider is a different group`() {
        assertNotEquals(group(scraperId = "repo:cb01"), group(scraperId = "repo:streamingcommunity"))
    }

    @Test
    fun `dubbed and subtitled variants are different groups`() {
        assertNotEquals(
            group(name = "AnimeWorld", title = "Frieren 1 ITA"),
            group(name = "AnimeWorld", title = "Frieren 1 SUB ITA")
        )
    }

    @Test
    fun `an explicit language wins over the text`() {
        assertEquals("plugin|x|720p|italian", PluginBingeGroup.of("x", "720p", "Italian", "ENG", null))
    }
}
