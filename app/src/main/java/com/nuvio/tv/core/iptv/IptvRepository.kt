package com.nuvio.tv.core.iptv

import com.nuvio.tv.data.local.IptvPreferencesDataStore
import com.nuvio.tv.domain.model.EpgProgramme
import com.nuvio.tv.domain.model.IptvChannel
import com.nuvio.tv.domain.model.IptvPlaylist
import com.nuvio.tv.domain.model.IptvPlaylistType
import com.nuvio.tv.domain.model.IptvSeriesEpisode
import com.nuvio.tv.domain.model.IptvVodCategory
import com.nuvio.tv.domain.model.IptvVodItem
import com.nuvio.tv.domain.model.IptvVodKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class IptvRepository @Inject constructor(
    @Named("addonPermissive") private val httpClient: OkHttpClient,
    private val preferences: IptvPreferencesDataStore
) {
    private var cachedChannels: List<IptvChannel>? = null
    private var cachedPlaylistUrl: String? = null

    private var cachedProgrammes: Map<String, List<EpgProgramme>>? = null
    private var cachedEpgUrl: String? = null

    suspend fun getChannels(forceRefresh: Boolean = false): Result<List<IptvChannel>> = withContext(Dispatchers.IO) {
        val worldSource = preferences.worldSource.first()
        val playlist = if (worldSource) null else getActivePlaylist()
        val url = when {
            worldSource -> worldPlaylistUrl(preferences.worldCountry.first())
            // The key identifies the account, so switching playlist never serves another's cache.
            playlist?.type == IptvPlaylistType.XTREAM -> "xtream:${playlist.id}:${playlist.url}:${playlist.username}"
            else -> playlist?.url.orEmpty()
        }
        if (url.isBlank()) return@withContext Result.failure(IllegalStateException("no_playlist_url"))
        val cached = cachedChannels
        if (!forceRefresh && cached != null && cachedPlaylistUrl == url) {
            return@withContext Result.success(cached)
        }
        if (playlist?.type == IptvPlaylistType.XTREAM) {
            return@withContext try {
                val account = XtreamApi.parseAccount(fetchText(XtreamApi.apiUrl(playlist)))
                    ?: return@withContext Result.failure(IllegalStateException("xtream_login_failed"))
                val categories = XtreamApi.parseCategories(fetchText(XtreamApi.apiUrl(playlist, "get_live_categories")))
                val channels = XtreamApi.parseLiveStreams(
                    fetchText(XtreamApi.apiUrl(playlist, "get_live_streams")),
                    playlist,
                    categories,
                    account.liveExtension
                )
                cachedChannels = channels
                cachedPlaylistUrl = url
                Result.success(channels)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                val body = response.body?.string()
                    ?: return@withContext Result.failure(IOException("empty_response"))
                val parsed = M3uParser.parse(body)
                // iptv-org tags channels with several categories ("Animation;Kids"), which
                // would turn every combination into its own filter chip: keep the first.
                val channels = if (worldSource) {
                    parsed.map { it.copy(groupTitle = it.groupTitle.substringBefore(';').ifBlank { it.groupTitle }) }
                } else {
                    parsed
                }
                cachedChannels = channels
                cachedPlaylistUrl = url
                Result.success(channels)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- World source (iptv-org's public catalog of free channels) ---------------------

    private var cachedCountries: List<WorldCountry>? = null

    /** Countries iptv-org publishes a playlist for, sorted by name. Cached for the session. */
    suspend fun getWorldCountries(): Result<List<WorldCountry>> = withContext(Dispatchers.IO) {
        cachedCountries?.let { return@withContext Result.success(it) }
        try {
            val request = Request.Builder().url(WORLD_COUNTRIES_URL).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val array = org.json.JSONArray(response.body?.string() ?: "[]")
                val countries = (0 until array.length()).mapNotNull { i ->
                    val obj = array.optJSONObject(i) ?: return@mapNotNull null
                    val code = obj.optString("code").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    WorldCountry(
                        code = code.lowercase(),
                        name = obj.optString("name", code),
                        flag = obj.optString("flag", "")
                    )
                }.sortedBy { it.name }
                cachedCountries = countries
                Result.success(countries)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    val worldSource = preferences.worldSource
    val worldCountry = preferences.worldCountry

    suspend fun setWorldSource(enabled: Boolean) = preferences.setWorldSource(enabled)

    suspend fun setWorldCountry(code: String) = preferences.setWorldCountry(code)

    private fun worldPlaylistUrl(countryCode: String): String =
        "https://iptv-org.github.io/iptv/countries/${countryCode.lowercase()}.m3u"

    suspend fun getLastChannelUrl(): String? =
        preferences.lastChannelUrl.first().takeIf { it.isNotBlank() }

    suspend fun setLastChannelUrl(url: String) {
        preferences.setLastChannelUrl(url)
    }

    // --- Playlists ---------------------------------------------------------------------

    suspend fun getPlaylists(): List<IptvPlaylist> = preferences.getPlaylists()

    /** The playlist in use: the one the user picked, or the first saved one. */
    suspend fun getActivePlaylist(): IptvPlaylist? {
        val playlists = preferences.getPlaylists()
        val activeId = preferences.getActivePlaylistId()
        return playlists.firstOrNull { it.id == activeId } ?: playlists.firstOrNull()
    }

    suspend fun setActivePlaylist(id: String) = preferences.setActivePlaylistId(id)

    suspend fun addPlaylist(playlist: IptvPlaylist) {
        preferences.addPlaylist(playlist)
        preferences.setActivePlaylistId(playlist.id)
    }

    suspend fun removePlaylist(id: String) = preferences.removePlaylist(id)

    /** Checks an Xtream account before it is saved. */
    suspend fun verifyXtreamAccount(playlist: IptvPlaylist): Boolean = withContext(Dispatchers.IO) {
        runCatching { XtreamApi.parseAccount(fetchText(XtreamApi.apiUrl(playlist))) != null }.getOrDefault(false)
    }

    // --- Movies and series (Xtream accounts) ---------------------------------------------

    private class VodCache(
        val key: String,
        val categories: List<IptvVodCategory>,
        val items: List<IptvVodItem>
    )

    private val vodCaches = mutableMapOf<IptvVodKind, VodCache>()

    /** Categories and titles of the active Xtream account, cached for the session. */
    suspend fun getVod(
        kind: IptvVodKind,
        forceRefresh: Boolean = false
    ): Result<Pair<List<IptvVodCategory>, List<IptvVodItem>>> = withContext(Dispatchers.IO) {
        val playlist = getActivePlaylist()?.takeIf { it.type == IptvPlaylistType.XTREAM }
            ?: return@withContext Result.failure(IllegalStateException("not_xtream"))
        val key = "${playlist.id}:${playlist.url}:${playlist.username}"
        val cached = vodCaches[kind]
        if (!forceRefresh && cached != null && cached.key == key) {
            return@withContext Result.success(cached.categories to cached.items)
        }
        try {
            val (categoriesAction, itemsAction) = when (kind) {
                IptvVodKind.MOVIE -> "get_vod_categories" to "get_vod_streams"
                IptvVodKind.SERIES -> "get_series_categories" to "get_series"
            }
            val categories = XtreamApi.parseCategories(fetchText(XtreamApi.apiUrl(playlist, categoriesAction)))
            val body = fetchText(XtreamApi.apiUrl(playlist, itemsAction))
            val items = if (kind == IptvVodKind.MOVIE) XtreamApi.parseMovies(body) else XtreamApi.parseSeries(body)
            vodCaches[kind] = VodCache(key, categories, items)
            Result.success(categories to items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSeriesEpisodes(series: IptvVodItem): Result<List<IptvSeriesEpisode>> = withContext(Dispatchers.IO) {
        val playlist = getActivePlaylist()?.takeIf { it.type == IptvPlaylistType.XTREAM }
            ?: return@withContext Result.failure(IllegalStateException("not_xtream"))
        runCatching {
            XtreamApi.parseSeriesEpisodes(
                fetchText(XtreamApi.apiUrl(playlist, "get_series_info", mapOf("series_id" to series.id)))
            )
        }
    }

    suspend fun movieUrl(item: IptvVodItem): String? =
        getActivePlaylist()?.takeIf { it.type == IptvPlaylistType.XTREAM }?.let { XtreamApi.movieUrl(it, item) }

    suspend fun episodeUrl(episode: IptvSeriesEpisode): String? =
        getActivePlaylist()?.takeIf { it.type == IptvPlaylistType.XTREAM }?.let { XtreamApi.episodeUrl(it, episode) }

    // Account API calls fail fast: a mistyped server address must come back as an error in
    // a few seconds, not leave the "checking" dialog spinning for a minute.
    private val apiClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun fetchText(url: String): String {
        val request = Request.Builder().url(url).build()
        apiClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("empty_response")
        }
    }

    suspend fun getFavoriteChannelUrls(): Set<String> = preferences.favoriteChannelUrls.first()

    suspend fun toggleFavoriteChannel(url: String): Set<String> = preferences.toggleFavoriteChannel(url)

    suspend fun getRecentChannelUrls(): List<String> = preferences.recentChannelUrls.first()

    suspend fun addRecentChannel(url: String): List<String> = preferences.addRecentChannel(url)

    suspend fun getProgrammesByChannel(forceRefresh: Boolean = false): Map<String, List<EpgProgramme>> =
        withContext(Dispatchers.IO) {
            // The world source has no guide of its own; it keeps using the personal one.
            val playlist = getActivePlaylist()
            val url = when {
                playlist == null -> preferences.epgUrl.first()
                playlist.epgUrl.isNotBlank() -> playlist.epgUrl
                playlist.type == IptvPlaylistType.XTREAM && !preferences.worldSource.first() -> XtreamApi.xmltvUrl(playlist)
                else -> ""
            }
            if (url.isBlank()) return@withContext emptyMap()
            val cached = cachedProgrammes
            if (!forceRefresh && cached != null && cachedEpgUrl == url) {
                return@withContext cached
            }
            try {
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyMap()
                    val stream = response.body?.byteStream() ?: return@withContext emptyMap()
                    val grouped = XmltvParser.parse(stream).groupBy { it.channelId }
                    cachedProgrammes = grouped
                    cachedEpgUrl = url
                    grouped
                }
            } catch (e: Exception) {
                emptyMap()
            }
        }
}
