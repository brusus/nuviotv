package com.nuvio.tv.domain.model

/** A single channel entry parsed from a user-supplied M3U/M3U8 playlist. */
data class IptvChannel(
    val id: String,
    val name: String,
    val logoUrl: String?,
    val groupTitle: String,
    val streamUrl: String,
    val epgId: String?,
    val headers: Map<String, String>? = null,
    /** Audio-only stream (`radio="true"` in the playlist, or an Xtream radio stream). */
    val isRadio: Boolean = false,
    /** Days of past programmes the provider keeps for this channel (Xtream catch-up), 0 if none. */
    val archiveDays: Int = 0
)
