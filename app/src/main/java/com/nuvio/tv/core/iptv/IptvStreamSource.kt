package com.nuvio.tv.core.iptv

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.domain.model.IptvPlaylist
import com.nuvio.tv.domain.model.IptvVodItem
import com.nuvio.tv.domain.model.IptvVodKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/** One playable match of a title in an Xtream account's catalogue. */
data class IptvProviderStream(
    val playlistId: String,
    val playlistName: String,
    val title: String,
    val url: String,
    /** "4K", "1080p", "720p", or null when the provider gives no hint. */
    val quality: String?,
    val headers: Map<String, String>?
)

/**
 * Makes the saved Xtream accounts answer like any other stream provider: given a movie or
 * an episode opened from the app's own catalogue, looks it up in each account's movies and
 * series and returns what can be played from there.
 *
 * A title matches by TMDB id when the provider publishes it, otherwise by name: the Italian,
 * original and English titles are compared after stripping years, quality tags and
 * punctuation, and the year must agree when both sides have one.
 */
@Singleton
class IptvStreamSource @Inject constructor(
    private val repository: IptvRepository,
    private val tmdbService: TmdbService,
    private val httpClient: OkHttpClient
) {
    private class TitleInfo(val names: Set<String>, val year: Int?)

    private val titleCache = java.util.concurrent.ConcurrentHashMap<String, TitleInfo>()

    suspend fun findStreams(videoId: String, type: String, season: Int?, episode: Int?): List<IptvProviderStream> {
        val kind = when (type.lowercase()) {
            "movie" -> IptvVodKind.MOVIE
            "series", "tv" -> IptvVodKind.SERIES
            else -> return emptyList()
        }
        val playlists = repository.getXtreamPlaylists()
        if (playlists.isEmpty()) return emptyList()

        val tmdbId = tmdbService.ensureTmdbId(videoId, type) ?: return emptyList()
        val info = titleInfo(tmdbId, kind) ?: return emptyList()

        return playlists.flatMap { playlist ->
            withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                runCatching { streamsFrom(playlist, kind, tmdbId, info, season, episode) }
                    .onFailure { Log.w(TAG, "IPTV lookup failed for ${playlist.name}: ${it.message}") }
                    .getOrDefault(emptyList())
            }.orEmpty()
        }
    }

    private suspend fun streamsFrom(
        playlist: IptvPlaylist,
        kind: IptvVodKind,
        tmdbId: String,
        info: TitleInfo,
        season: Int?,
        episode: Int?
    ): List<IptvProviderStream> {
        val (categories, items) = repository.getVodOf(playlist, kind).getOrNull() ?: return emptyList()
        val categoryNames = categories.associate { it.id to it.name }
        val matches = items.filter { matches(it, tmdbId, info) }.take(MAX_MATCHES)
        val headers = playlist.userAgent.takeIf { it.isNotBlank() }?.let { mapOf("User-Agent" to it) }
        val label = playlist.name.ifBlank { "IPTV" }

        return matches.mapNotNull { item ->
            val hint = qualityHint(item.name) ?: qualityHint(categoryNames[item.categoryId].orEmpty())
            if (kind == IptvVodKind.MOVIE) {
                // The list rarely says the resolution; the details do, at the cost of a call.
                val quality = hint ?: repository.getVodInfoOf(playlist, item).getOrNull()?.first?.videoHeight
                    ?.let(::qualityFromHeight)
                IptvProviderStream(playlist.id, label, item.name, XtreamApi.movieUrl(playlist, item), quality, headers)
            } else {
                val wantedSeason = season ?: return@mapNotNull null
                val wantedEpisode = episode ?: return@mapNotNull null
                val match = repository.getVodInfoOf(playlist, item).getOrNull()?.second
                    ?.firstOrNull { it.season == wantedSeason && it.episode == wantedEpisode }
                    ?: return@mapNotNull null
                IptvProviderStream(
                    playlist.id,
                    label,
                    "${item.name} S${wantedSeason}E$wantedEpisode",
                    XtreamApi.episodeUrl(playlist, match),
                    hint,
                    headers
                )
            }
        }
    }

    private fun matches(item: IptvVodItem, tmdbId: String, info: TitleInfo): Boolean {
        if (item.tmdbId != null) return item.tmdbId == tmdbId
        if (normalizeTitle(item.name) !in info.names) return false
        val itemYear = YEAR_REGEX.find(item.name)?.groupValues?.get(1)?.toIntOrNull()
        return itemYear == null || info.year == null || kotlin.math.abs(itemYear - info.year) <= 1
    }

    /** Italian, original and English titles of a TMDB entry, normalised for comparison. */
    private suspend fun titleInfo(tmdbId: String, kind: IptvVodKind): TitleInfo? {
        val key = "$kind:$tmdbId"
        titleCache[key]?.let { return it }
        val path = if (kind == IptvVodKind.MOVIE) "movie" else "tv"
        val names = LinkedHashSet<String>()
        var year: Int? = null
        for (language in listOf("it-IT", "en-US")) {
            val json = fetchJson("https://api.themoviedb.org/3/$path/$tmdbId?api_key=${tmdbService.apiKey()}&language=$language")
                ?: continue
            listOf("title", "name", "original_title", "original_name").forEach { field ->
                json.get(field)?.takeIf { it.isJsonPrimitive }?.asString
                    ?.let(::normalizeTitle)?.takeIf { it.isNotEmpty() }?.let(names::add)
            }
            if (year == null) {
                year = listOf("release_date", "first_air_date")
                    .firstNotNullOfOrNull { json.get(it)?.takeIf { e -> e.isJsonPrimitive }?.asString }
                    ?.take(4)?.toIntOrNull()
            }
        }
        if (names.isEmpty()) return null
        return TitleInfo(names, year).also { titleCache[key] = it }
    }

    private suspend fun fetchJson(url: String): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
            }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "IptvStreamSource"
        private const val LOOKUP_TIMEOUT_MS = 15_000L
        private const val MAX_MATCHES = 4
        private val YEAR_REGEX = Regex("\\((19\\d{2}|20\\d{2})\\)")
        private val BRACKETS_REGEX = Regex("\\([^)]*\\)|\\[[^]]*]")
        private val TAGS_REGEX = Regex("\\b(4k|uhd|fhd|hd|sd|hdr|hevc|h265|h264|x265|x264|1080p|2160p|720p|ita|eng|sub|multi)\\b")
        private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")
        private val DIACRITICS_REGEX = Regex("\\p{M}+")

        /** Lower case, no accents, no years or quality tags, no punctuation. */
        fun normalizeTitle(raw: String): String {
            val plain = DIACRITICS_REGEX.replace(Normalizer.normalize(raw, Normalizer.Form.NFD), "").lowercase()
            return NON_ALNUM_REGEX.replace(TAGS_REGEX.replace(BRACKETS_REGEX.replace(plain, " "), " "), " ").trim()
        }

        /** Resolution a title or category name advertises ("4K", "FHD"...), if any. */
        fun qualityHint(text: String): String? {
            val lower = text.lowercase()
            return when {
                Regex("\\b(4k|uhd|2160p?)\\b").containsMatchIn(lower) -> "4K"
                Regex("\\b(fhd|1080p?)\\b").containsMatchIn(lower) -> "1080p"
                Regex("\\b(720p?)\\b").containsMatchIn(lower) -> "720p"
                else -> null
            }
        }

        fun qualityFromHeight(height: Int): String? = when {
            height >= 2000 -> "4K"
            height >= 1000 -> "1080p"
            height >= 700 -> "720p"
            height > 0 -> "SD"
            else -> null
        }
    }
}
