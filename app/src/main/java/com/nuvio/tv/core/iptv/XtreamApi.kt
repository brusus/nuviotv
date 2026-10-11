package com.nuvio.tv.core.iptv

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.domain.model.IptvChannel
import com.nuvio.tv.domain.model.IptvPlaylist
import com.nuvio.tv.domain.model.IptvSeriesEpisode
import com.nuvio.tv.domain.model.IptvVodCategory
import com.nuvio.tv.domain.model.IptvVodDetails
import com.nuvio.tv.domain.model.IptvVodItem
import com.nuvio.tv.domain.model.IptvVodKind
import java.net.URLEncoder

/**
 * Xtream Codes "player API": URL building and response parsing. Network access stays in
 * [IptvRepository]; everything here is pure so it can be unit tested with sample payloads.
 *
 * Providers are sloppy with types (ids as numbers or strings, missing fields, `null`
 * arrays), so every read is lenient and a malformed entry is skipped rather than fatal.
 */
object XtreamApi {

    /** Server root from whatever the user typed (with or without a path, query or slash). */
    fun baseUrl(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return value
        if (!value.startsWith("http://", ignoreCase = true) && !value.startsWith("https://", ignoreCase = true)) {
            value = "http://$value"
        }
        value = value.substringBefore('?')
        listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php").forEach { suffix ->
            val index = value.indexOf(suffix, ignoreCase = true)
            if (index >= 0) value = value.substring(0, index)
        }
        return value.trimEnd('/')
    }

    fun apiUrl(playlist: IptvPlaylist, action: String? = null, extra: Map<String, String> = emptyMap()): String =
        buildString {
            append(baseUrl(playlist.url)).append("/player_api.php?username=").append(enc(playlist.username))
            append("&password=").append(enc(playlist.password))
            if (action != null) append("&action=").append(action)
            extra.forEach { (key, value) -> append('&').append(key).append('=').append(enc(value)) }
        }

    private val ACCOUNT_STREAM_PATH = Regex("/(movie|series|live|timeshift)/[^/]+/[^/]+/")

    /** True for the stream addresses this API builds (they carry the account in the path). */
    fun isAccountStreamUrl(url: String): Boolean = ACCOUNT_STREAM_PATH.containsMatchIn(url)

    fun xmltvUrl(playlist: IptvPlaylist): String =
        "${baseUrl(playlist.url)}/xmltv.php?username=${enc(playlist.username)}&password=${enc(playlist.password)}"

    fun liveUrl(playlist: IptvPlaylist, streamId: String, extension: String = "ts"): String =
        "${baseUrl(playlist.url)}/live/${enc(playlist.username)}/${enc(playlist.password)}/$streamId.$extension"

    fun movieUrl(playlist: IptvPlaylist, item: IptvVodItem): String =
        "${baseUrl(playlist.url)}/movie/${enc(playlist.username)}/${enc(playlist.password)}/${item.id}." +
            (item.containerExtension?.ifBlank { null } ?: "mp4")

    fun episodeUrl(playlist: IptvPlaylist, episode: IptvSeriesEpisode): String =
        "${baseUrl(playlist.url)}/series/${enc(playlist.username)}/${enc(playlist.password)}/${episode.id}." +
            episode.containerExtension.ifBlank { "mp4" }

    /**
     * Outcome of the login call: [liveExtension] is the stream format the account allows
     * ("ts" unless the provider only lists m3u8). Null when the account is rejected.
     */
    data class Account(val liveExtension: String)

    fun parseAccount(json: String): Account? {
        val root = parse(json) as? JsonObject ?: return null
        val userInfo = root.obj("user_info") ?: return null
        if (userInfo.str("auth") == "0") return null
        val status = userInfo.str("status")
        if (status != null && !status.equals("Active", ignoreCase = true)) return null
        val formats = (userInfo.get("allowed_output_formats") as? JsonArray)
            ?.mapNotNull { it.asStringOrNull()?.lowercase() }
            .orEmpty()
        val extension = if (formats.isNotEmpty() && "ts" !in formats && "m3u8" in formats) "m3u8" else "ts"
        return Account(extension)
    }

    fun parseCategories(json: String): List<IptvVodCategory> =
        array(json).mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.str("category_id") ?: return@mapNotNull null
            IptvVodCategory(id = id, name = obj.str("category_name")?.trim().orEmpty().ifEmpty { id })
        }

    fun parseLiveStreams(
        json: String,
        playlist: IptvPlaylist,
        categories: List<IptvVodCategory>,
        extension: String
    ): List<IptvChannel> {
        val categoryNames = categories.associate { it.id to it.name }
        return array(json).mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val streamId = obj.str("stream_id") ?: return@mapNotNull null
            val name = obj.str("name")?.trim().orEmpty().ifEmpty { return@mapNotNull null }
            IptvChannel(
                id = streamId,
                name = name,
                logoUrl = obj.str("stream_icon")?.takeIf { it.startsWith("http", ignoreCase = true) },
                groupTitle = categoryNames[obj.str("category_id")].orEmpty(),
                streamUrl = liveUrl(playlist, streamId, extension),
                epgId = obj.str("epg_channel_id")?.takeIf { it.isNotBlank() },
                isRadio = obj.str("stream_type").orEmpty().contains("radio", ignoreCase = true),
                archiveDays = if (obj.str("tv_archive") == "1") {
                    obj.str("tv_archive_duration")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                } else {
                    0
                }
            )
        }
    }

    fun parseMovies(json: String): List<IptvVodItem> =
        array(json).mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.str("stream_id") ?: return@mapNotNull null
            IptvVodItem(
                id = id,
                name = obj.str("name")?.trim().orEmpty().ifEmpty { return@mapNotNull null },
                posterUrl = obj.str("stream_icon")?.takeIf { it.startsWith("http", ignoreCase = true) },
                categoryId = obj.str("category_id").orEmpty(),
                addedEpochSeconds = obj.str("added")?.toLongOrNull() ?: 0L,
                rating = obj.str("rating")?.takeIf { it.isNotBlank() && it != "0" },
                containerExtension = obj.str("container_extension"),
                kind = IptvVodKind.MOVIE,
                tmdbId = obj.tmdbId()
            )
        }

    fun parseSeries(json: String): List<IptvVodItem> =
        array(json).mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.str("series_id") ?: return@mapNotNull null
            IptvVodItem(
                id = id,
                name = obj.str("name")?.trim().orEmpty().ifEmpty { return@mapNotNull null },
                posterUrl = obj.str("cover")?.takeIf { it.startsWith("http", ignoreCase = true) },
                categoryId = obj.str("category_id").orEmpty(),
                addedEpochSeconds = obj.str("last_modified")?.toLongOrNull() ?: 0L,
                rating = obj.str("rating")?.takeIf { it.isNotBlank() && it != "0" },
                containerExtension = null,
                kind = IptvVodKind.SERIES,
                tmdbId = obj.tmdbId()
            )
        }

    /** Episodes of a series, ordered by season then episode number. */
    fun parseSeriesEpisodes(json: String): List<IptvSeriesEpisode> {
        val root = parse(json) as? JsonObject ?: return emptyList()
        // "episodes" is an object keyed by season number; some providers send an array of arrays.
        val bySeason: List<Pair<Int?, JsonArray>> = when (val episodes = root.get("episodes")) {
            is JsonObject -> episodes.entrySet().mapNotNull { (key, value) ->
                (value as? JsonArray)?.let { key.toIntOrNull() to it }
            }
            is JsonArray -> episodes.mapNotNull { (it as? JsonArray)?.let { list -> null to list } }
            else -> emptyList()
        }
        return bySeason.flatMap { (seasonKey, list) ->
            list.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj.str("id") ?: return@mapNotNull null
                val number = obj.str("episode_num")?.toIntOrNull() ?: 0
                IptvSeriesEpisode(
                    id = id,
                    season = obj.str("season")?.toIntOrNull() ?: seasonKey ?: 1,
                    episode = number,
                    title = obj.str("title")?.trim().orEmpty(),
                    containerExtension = obj.str("container_extension").orEmpty()
                )
            }
        }.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    /**
     * Plot and credits from `get_vod_info` or `get_series_info`: both keep them under
     * "info", with slightly different field names.
     */
    fun parseVodDetails(json: String): IptvVodDetails {
        val info = (parse(json) as? JsonObject)?.obj("info") ?: return IptvVodDetails()
        fun first(vararg keys: String): String =
            keys.firstNotNullOfOrNull { key -> info.str(key)?.trim()?.takeIf { it.isNotEmpty() } }.orEmpty()
        val backdrop = when (val value = info.get("backdrop_path")) {
            is JsonArray -> value.firstOrNull()?.asStringOrNull()
            else -> value?.asStringOrNull()
        }?.takeIf { it.startsWith("http", ignoreCase = true) }
        return IptvVodDetails(
            plot = first("plot", "description"),
            genre = first("genre"),
            year = first("releasedate", "releaseDate", "release_date", "year").take(4),
            rating = first("rating").takeIf { it != "0" }.orEmpty(),
            duration = first("duration"),
            cast = first("cast", "actors"),
            director = first("director"),
            backdropUrl = backdrop,
            videoHeight = (info.get("video") as? JsonObject)?.str("height")?.toIntOrNull() ?: 0
        )
    }

    /**
     * Catch-up URL for a programme that already aired, on channels the provider archives.
     * The start time is written in the device's time zone, which is what most panels expect.
     */
    fun timeshiftUrl(
        playlist: IptvPlaylist,
        streamId: String,
        startMillis: Long,
        durationMinutes: Int,
        timeZone: java.util.TimeZone = java.util.TimeZone.getDefault()
    ): String {
        val start = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US)
            .apply { this.timeZone = timeZone }
            .format(java.util.Date(startMillis))
        return "${baseUrl(playlist.url)}/timeshift/${enc(playlist.username)}/${enc(playlist.password)}/" +
            "${durationMinutes.coerceAtLeast(1)}/$start/$streamId.ts"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun parse(json: String): JsonElement? = runCatching { JsonParser.parseString(json) }.getOrNull()

    private fun array(json: String): JsonArray = parse(json) as? JsonArray ?: JsonArray()

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

    private fun JsonObject.tmdbId(): String? =
        (str("tmdb") ?: str("tmdb_id"))?.trim()?.takeIf { it.isNotEmpty() && it != "0" }

    /** A field as text whether the provider sent a string or a number; null when absent. */
    private fun JsonObject.str(key: String): String? = get(key)?.asStringOrNull()

    private fun JsonElement.asStringOrNull(): String? =
        if (isJsonPrimitive) asJsonPrimitive.asString else null
}
