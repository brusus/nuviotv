package com.nuvio.tv.domain.model

enum class IptvPlaylistType { M3U, XTREAM }

/**
 * One saved IPTV source. An M3U playlist is a URL (plus an optional XMLTV guide URL); an
 * Xtream Codes account is a server address with a username and password, and also offers
 * movies and series.
 */
data class IptvPlaylist(
    val id: String,
    val name: String,
    val type: IptvPlaylistType,
    val url: String,
    val username: String = "",
    val password: String = "",
    val epgUrl: String = ""
)

enum class IptvVodKind { MOVIE, SERIES }

data class IptvVodCategory(val id: String, val name: String)

/** A movie or a series of an Xtream Codes account. */
data class IptvVodItem(
    val id: String,
    val name: String,
    val posterUrl: String?,
    val categoryId: String,
    /** When the provider added it (Unix seconds), 0 if unknown. */
    val addedEpochSeconds: Long,
    val rating: String?,
    /** File extension of a movie ("mp4", "mkv"); null for a series. */
    val containerExtension: String?,
    val kind: IptvVodKind
)

data class IptvSeriesEpisode(
    val id: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val containerExtension: String
)
