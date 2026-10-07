package com.nuvio.tv.core.iptv

import com.nuvio.tv.domain.model.EpgProgramme
import java.util.Locale

/**
 * Matches a playlist tvg-id to an XMLTV channel id. Playlists and guides rarely spell ids
 * the same way: iptv-org uses "Canale5.it@SD" (with a feed suffix) while epgshare01 uses
 * "Canale.5.it", so an exact lookup finds no programmes at all. Exact match first, then
 * the ids compared without the "@feed" suffix, case and punctuation.
 */
object EpgIdMatcher {

    fun normalize(id: String): String =
        id.substringBefore('@').lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** Index of [programmes] by normalized id, built once per loaded guide. */
    fun normalizedIndex(programmes: Map<String, List<EpgProgramme>>): Map<String, List<EpgProgramme>> {
        val index = HashMap<String, List<EpgProgramme>>(programmes.size)
        programmes.forEach { (id, list) -> index.putIfAbsent(normalize(id), list) }
        return index
    }

    fun lookup(
        epgId: String?,
        programmes: Map<String, List<EpgProgramme>>,
        normalizedIndex: Map<String, List<EpgProgramme>>
    ): List<EpgProgramme>? {
        if (epgId.isNullOrBlank()) return null
        return programmes[epgId] ?: normalizedIndex[normalize(epgId)]
    }
}
