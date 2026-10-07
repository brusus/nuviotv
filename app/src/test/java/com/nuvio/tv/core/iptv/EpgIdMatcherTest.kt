package com.nuvio.tv.core.iptv

import com.nuvio.tv.domain.model.EpgProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgIdMatcherTest {

    private val programmes = mapOf(
        "Canale.5.it" to listOf(EpgProgramme("Canale.5.it", "TG5", 0L, 1L)),
        "Rai1.it" to listOf(EpgProgramme("Rai1.it", "TG1", 0L, 1L))
    )
    private val index = EpgIdMatcher.normalizedIndex(programmes)

    @Test
    fun `exact id matches`() {
        assertEquals("TG1", EpgIdMatcher.lookup("Rai1.it", programmes, index)?.single()?.title)
    }

    @Test
    fun `iptv-org feed suffix and punctuation differences still match`() {
        assertEquals("TG5", EpgIdMatcher.lookup("Canale5.it@SD", programmes, index)?.single()?.title)
        assertEquals("TG5", EpgIdMatcher.lookup("canale5.IT@HD", programmes, index)?.single()?.title)
    }

    @Test
    fun `unknown or missing ids find nothing`() {
        assertNull(EpgIdMatcher.lookup("Italia1.it@SD", programmes, index))
        assertNull(EpgIdMatcher.lookup(null, programmes, index))
    }
}
