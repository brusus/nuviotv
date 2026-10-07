package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IptvPreferencesDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "iptv_settings"
    }

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    private val playlistUrlKey = stringPreferencesKey("playlist_url")
    private val epgUrlKey = stringPreferencesKey("epg_url")
    private val lastChannelUrlKey = stringPreferencesKey("last_channel_url")
    private val worldSourceKey = booleanPreferencesKey("world_source")
    private val worldCountryKey = stringPreferencesKey("world_country")

    /** True when the IPTV screen shows iptv-org's free channels instead of the user's playlist. */
    val worldSource: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[worldSourceKey] ?: false }
    }

    /** ISO 3166 code of the country browsed in the world source (iptv-org playlist name). */
    val worldCountry: Flow<String> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[worldCountryKey] ?: "it" }
    }

    suspend fun setWorldSource(enabled: Boolean) {
        store().edit { it[worldSourceKey] = enabled }
    }

    suspend fun setWorldCountry(code: String) {
        store().edit { it[worldCountryKey] = code.lowercase() }
    }

    /** Stream URL of the last channel watched, so the channel grid reopens on it. */
    val lastChannelUrl: Flow<String> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[lastChannelUrlKey] ?: "" }
    }

    suspend fun setLastChannelUrl(url: String) {
        store().edit { it[lastChannelUrlKey] = url }
    }

    val playlistUrl: Flow<String> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[playlistUrlKey] ?: "" }
    }

    val epgUrl: Flow<String> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[epgUrlKey] ?: "" }
    }

    suspend fun setPlaylistUrl(url: String) {
        store().edit { it[playlistUrlKey] = url.trim() }
    }

    suspend fun setEpgUrl(url: String) {
        store().edit { it[epgUrlKey] = url.trim() }
    }
}
