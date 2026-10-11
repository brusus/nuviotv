package com.nuvio.tv.core.iptv

import com.nuvio.tv.data.local.IptvPreferencesDataStore
import com.nuvio.tv.domain.model.EpgProgramme
import com.nuvio.tv.domain.model.IptvChannel
import com.nuvio.tv.domain.model.IptvPlaylist
import com.nuvio.tv.domain.model.IptvPlaylistType
import com.nuvio.tv.domain.model.IptvSeriesEpisode
import com.nuvio.tv.domain.model.IptvVodCategory
import com.nuvio.tv.domain.model.IptvVodDetails
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
                val account = XtreamApi.parseAccount(fetchText(XtreamApi.apiUrl(playlist), playlist.userAgent))
                    ?: return@withContext Result.failure(IllegalStateException("xtream_login_failed"))
                val categories = XtreamApi.parseCategories(fetchText(XtreamApi.apiUrl(playlist, "get_live_categories"), playlist.userAgent))
                val channels = XtreamApi.parseLiveStreams(
                    fetchText(XtreamApi.apiUrl(playlist, "get_live_streams"), playlist.userAgent),
                    playlist,
                    categories,
                    account.liveExtension
                ).withUserAgent(playlist.userAgent)
                cachedChannels = channels
                cachedPlaylistUrl = url
                Result.success(channels)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        try {
            val request = Request.Builder().url(url).withUserAgent(playlist?.userAgent.orEmpty()).build()
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
                    parsed.withUserAgent(playlist?.userAgent.orEmpty())
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
        // Saving over an existing playlist may change its server or login.
        vodCaches.clear()
        vodInfoCache.clear()
    }

    suspend fun removePlaylist(id: String) = preferences.removePlaylist(id)

    /** Checks an Xtream account before it is saved. */
    suspend fun verifyXtreamAccount(playlist: IptvPlaylist): Boolean = withContext(Dispatchers.IO) {
        runCatching { XtreamApi.parseAccount(fetchText(XtreamApi.apiUrl(playlist), playlist.userAgent)) != null }.getOrDefault(false)
    }

    // --- Movies and series (Xtream accounts) ---------------------------------------------

    private class VodCache(
        val key: String,
        val categories: List<IptvVodCategory>,
        val items: List<IptvVodItem>
    )

    private val vodCaches = java.util.concurrent.ConcurrentHashMap<String, VodCache>()
    private val vodInfoCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<IptvVodDetails, List<IptvSeriesEpisode>>>()

    /** Categories and titles of the active Xtream account, cached for the session. */
    suspend fun getVod(
        kind: IptvVodKind,
        forceRefresh: Boolean = false
    ): Result<Pair<List<IptvVodCategory>, List<IptvVodItem>>> {
        val playlist = getActivePlaylist()?.takeIf { it.type == IptvPlaylistType.XTREAM }
            ?: return Result.failure(IllegalStateException("not_xtream"))
        return getVodOf(playlist, kind, forceRefresh)
    }

    /** Every saved Xtream account, whichever playlist is the one in use. */
    suspend fun getXtreamPlaylists(): List<IptvPlaylist> =
        preferences.getPlaylists().filter { it.type == IptvPlaylistType.XTREAM }

    /** Catalogue of a given Xtream account, cached for the session per account and kind. */
    suspend fun getVodOf(
        playlist: IptvPlaylist,
        kind: IptvVodKind,
        forceRefresh: Boolean = false
    ): Result<Pair<List<IptvVodCategory>, List<IptvVodItem>>> = withContext(Dispatchers.IO) {
        val key = "$kind:${playlist.id}:${playlist.url}:${playlist.username}"
        val cached = vodCaches[key]
        if (!forceRefresh && cached != null) {
            return@withContext Result.success(cached.categories to cached.items)
        }
        try {
            val (categoriesAction, itemsAction) = when (kind) {
                IptvVodKind.MOVIE -> "get_vod_categories" to "get_vod_streams"
                IptvVodKind.SERIES -> "get_series_categories" to "get_series"
            }
            val categories = XtreamApi.parseCategories(fetchText(XtreamApi.apiUrl(playlist, categoriesAction), playlist.userAgent))
            val body = fetchText(XtreamApi.apiUrl(playlist, itemsAction), playlist.userAgent)
            val items = if (kind == IptvVodKind.MOVIE) XtreamApi.parseMovies(body) else XtreamApi.parseSeries(body)
            vodCaches[key] = VodCache(key, categories, items)
            Result.success(categories to items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Plot and credits of a movie, or of a series together with its episodes (one request). */
    suspend fun getVodInfo(item: IptvVodItem): Result<Pair<IptvVodDetails, List<IptvSeriesEpisode>>> {
        val playlist = getActivePlaylist()?.takeIf { it.type == IptvPlaylistType.XTREAM }
            ?: return Result.failure(IllegalStateException("not_xtream"))
        return getVodInfoOf(playlist, item)
    }

    suspend fun getVodInfoOf(
        playlist: IptvPlaylist,
        item: IptvVodItem
    ): Result<Pair<IptvVodDetails, List<IptvSeriesEpisode>>> =
        withContext(Dispatchers.IO) {
            // Kept for the session: the same title is asked again by the player's source list
            // while the account is busy streaming, when the provider may not answer.
            val cacheKey = "${item.kind}:${playlist.id}:${playlist.username}:${item.id}"
            vodInfoCache[cacheKey]?.let { return@withContext Result.success(it) }
            runCatching {
                if (item.kind == IptvVodKind.MOVIE) {
                    val body = fetchText(
                        XtreamApi.apiUrl(playlist, "get_vod_info", mapOf("vod_id" to item.id)),
                        playlist.userAgent
                    )
                    XtreamApi.parseVodDetails(body) to emptyList()
                } else {
                    val body = fetchText(
                        XtreamApi.apiUrl(playlist, "get_series_info", mapOf("series_id" to item.id)),
                        playlist.userAgent
                    )
                    XtreamApi.parseVodDetails(body) to XtreamApi.parseSeriesEpisodes(body)
                }
            }.onSuccess { vodInfoCache[cacheKey] = it }
        }

    /** Headers the player must send for this playlist's streams (its custom User-Agent). */
    suspend fun playbackHeaders(): Map<String, String>? =
        getActivePlaylist()?.userAgent?.takeIf { it.isNotBlank() }?.let { mapOf("User-Agent" to it) }

    /** Catch-up address of a programme that already aired, when the channel is archived. */
    suspend fun timeshiftUrl(channel: IptvChannel, programme: EpgProgramme): String? {
        if (channel.archiveDays <= 0) return null
        val playlist = getActivePlaylist()?.takeIf { it.type == IptvPlaylistType.XTREAM } ?: return null
        val minutes = ((programme.stopMillis - programme.startMillis) / 60_000L).toInt()
        return XtreamApi.timeshiftUrl(playlist, channel.id, programme.startMillis, minutes)
    }

    // --- Hidden categories and favourite titles --------------------------------------------

    /** Key of a live group ("live"), movie category ("movie") or series category ("series"). */
    suspend fun categoryKey(kind: String, id: String): String = "${activeSourceId()}|$kind|$id"

    suspend fun vodKey(item: IptvVodItem): String = "${activeSourceId()}|${item.kind.name}|${item.id}"

    /** Start of every hidden-category and favourite key of the source in use. */
    suspend fun sourceKeyPrefix(): String = "${activeSourceId()}|"

    private suspend fun activeSourceId(): String =
        if (preferences.worldSource.first()) "world" else getActivePlaylist()?.id.orEmpty()

    suspend fun getHiddenCategoryKeys(): Set<String> = preferences.getHiddenCategoryKeys()

    suspend fun toggleHiddenCategory(key: String): Set<String> = preferences.toggleHiddenCategory(key)

    suspend fun getFavoriteVodKeys(): Set<String> = preferences.getFavoriteVodKeys()

    suspend fun toggleFavoriteVod(key: String): Set<String> = preferences.toggleFavoriteVod(key)

    private fun Request.Builder.withUserAgent(userAgent: String): Request.Builder =
        if (userAgent.isBlank()) this else header("User-Agent", userAgent)

    /** Streams must be requested with the playlist's User-Agent too, not just the list. */
    private fun List<IptvChannel>.withUserAgent(userAgent: String): List<IptvChannel> {
        if (userAgent.isBlank()) return this
        return map { channel ->
            val headers = channel.headers.orEmpty()
            if (headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) channel
            else channel.copy(headers = headers + ("User-Agent" to userAgent))
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

    private fun fetchText(url: String, userAgent: String = ""): String {
        val request = Request.Builder().url(url).withUserAgent(userAgent).build()
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
                // Providers that only answer to a given player name check it on the guide too.
                val request = Request.Builder().url(url).withUserAgent(playlist?.userAgent.orEmpty()).build()
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
