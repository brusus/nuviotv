package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IptvPreferencesDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory
) {
    companion object {
        private const val FEATURE = "iptv_settings"
        private const val SHARED_PROFILE_ID = 1
        private const val MAX_RECENT_CHANNELS = 30
    }

    // IPTV, plugins and VPN are shared by every profile: they always live in the primary
    // profile's store. Only the home catalog stays per profile.
    private val sharedProfileId: Flow<Int> = flowOf(SHARED_PROFILE_ID)

    private fun store() = factory.get(SHARED_PROFILE_ID, FEATURE)

    private val playlistUrlKey = stringPreferencesKey("playlist_url")
    private val epgUrlKey = stringPreferencesKey("epg_url")
    private val lastChannelUrlKey = stringPreferencesKey("last_channel_url")
    private val worldSourceKey = booleanPreferencesKey("world_source")
    private val worldCountryKey = stringPreferencesKey("world_country")
    private val favoriteChannelsKey = stringSetPreferencesKey("favorite_channels")
    private val recentChannelsKey = stringPreferencesKey("recent_channels")

    /** True when the IPTV screen shows iptv-org's free channels instead of the user's playlist. */
    val worldSource: Flow<Boolean> = sharedProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[worldSourceKey] ?: false }
    }

    /** ISO 3166 code of the country browsed in the world source (iptv-org playlist name). */
    val worldCountry: Flow<String> = sharedProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[worldCountryKey] ?: "it" }
    }

    suspend fun setWorldSource(enabled: Boolean) {
        store().edit { it[worldSourceKey] = enabled }
    }

    suspend fun setWorldCountry(code: String) {
        store().edit { it[worldCountryKey] = code.lowercase() }
    }

    /** Stream URL of the last channel watched, so the channel grid reopens on it. */
    val lastChannelUrl: Flow<String> = sharedProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[lastChannelUrlKey] ?: "" }
    }

    suspend fun setLastChannelUrl(url: String) {
        store().edit { it[lastChannelUrlKey] = url }
    }

    /** Stream URLs of the channels the user starred. */
    val favoriteChannelUrls: Flow<Set<String>> = store().data.map { it[favoriteChannelsKey] ?: emptySet() }

    /** Adds or removes a favourite; returns the resulting set. */
    suspend fun toggleFavoriteChannel(url: String): Set<String> {
        var result: Set<String> = emptySet()
        store().edit { prefs ->
            val current = prefs[favoriteChannelsKey] ?: emptySet()
            result = if (url in current) current - url else current + url
            prefs[favoriteChannelsKey] = result
        }
        return result
    }

    /** Stream URLs of the channels watched most recently, newest first. */
    val recentChannelUrls: Flow<List<String>> = store().data.map { prefs ->
        prefs[recentChannelsKey].orEmpty().split('\n').filter { it.isNotBlank() }
    }

    /** Moves [url] to the top of the recent list; returns the resulting list. */
    suspend fun addRecentChannel(url: String): List<String> {
        var result: List<String> = emptyList()
        store().edit { prefs ->
            val current = prefs[recentChannelsKey].orEmpty().split('\n').filter { it.isNotBlank() }
            result = (listOf(url) + current.filter { it != url }).take(MAX_RECENT_CHANNELS)
            prefs[recentChannelsKey] = result.joinToString("\n")
        }
        return result
    }

    val playlistUrl: Flow<String> = sharedProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[playlistUrlKey] ?: "" }
    }

    val epgUrl: Flow<String> = sharedProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[epgUrlKey] ?: "" }
    }

    suspend fun setPlaylistUrl(url: String) {
        store().edit { it[playlistUrlKey] = url.trim() }
    }

    suspend fun setEpgUrl(url: String) {
        store().edit { it[epgUrlKey] = url.trim() }
    }
}
