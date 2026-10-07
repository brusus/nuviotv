package com.nuvio.tv.core.iptv

import com.nuvio.tv.data.local.IptvPreferencesDataStore
import com.nuvio.tv.domain.model.EpgProgramme
import com.nuvio.tv.domain.model.IptvChannel
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
        val url = if (worldSource) {
            worldPlaylistUrl(preferences.worldCountry.first())
        } else {
            preferences.playlistUrl.first()
        }
        if (url.isBlank()) return@withContext Result.failure(IllegalStateException("no_playlist_url"))
        val cached = cachedChannels
        if (!forceRefresh && cached != null && cachedPlaylistUrl == url) {
            return@withContext Result.success(cached)
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

    suspend fun getProgrammesByChannel(forceRefresh: Boolean = false): Map<String, List<EpgProgramme>> =
        withContext(Dispatchers.IO) {
            val url = preferences.epgUrl.first()
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
