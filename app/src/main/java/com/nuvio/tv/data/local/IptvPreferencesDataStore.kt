package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.security.SecureStringCipher
import com.nuvio.tv.domain.model.IptvPlaylist
import com.nuvio.tv.domain.model.IptvPlaylistType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IptvPreferencesDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val cipher: SecureStringCipher
) {
    companion object {
        private const val FEATURE = "iptv_settings"
        private const val SHARED_PROFILE_ID = 1
        private const val MAX_RECENT_CHANNELS = 30
        const val LEGACY_PLAYLIST_ID = "legacy"
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
    private val playlistsKey = stringPreferencesKey("playlists")
    private val activePlaylistIdKey = stringPreferencesKey("active_playlist_id")

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

    // --- Saved playlists ---------------------------------------------------------------
    //
    // The single playlist of the settings screen (playlist_url / epg_url) stays where it
    // is and shows up as the first entry, with id LEGACY_PLAYLIST_ID; playlists added from
    // the IPTV screen are kept as one JSON list, encrypted because Xtream entries carry the
    // account password.

    suspend fun getPlaylists(): List<IptvPlaylist> {
        val prefs = store().data.first()
        val legacyUrl = prefs[playlistUrlKey].orEmpty()
        val legacy = if (legacyUrl.isBlank()) emptyList() else listOf(
            IptvPlaylist(
                id = LEGACY_PLAYLIST_ID,
                name = "",
                type = IptvPlaylistType.M3U,
                url = legacyUrl,
                epgUrl = prefs[epgUrlKey].orEmpty()
            )
        )
        return legacy + decodePlaylists(prefs[playlistsKey])
    }

    suspend fun addPlaylist(playlist: IptvPlaylist) {
        store().edit { prefs ->
            val current = decodePlaylists(prefs[playlistsKey]).filter { it.id != playlist.id }
            prefs[playlistsKey] = cipher.encrypt(encodePlaylists(current + playlist))
        }
    }

    suspend fun removePlaylist(id: String) {
        store().edit { prefs ->
            if (id == LEGACY_PLAYLIST_ID) {
                prefs.remove(playlistUrlKey)
                prefs.remove(epgUrlKey)
            } else {
                val remaining = decodePlaylists(prefs[playlistsKey]).filter { it.id != id }
                prefs[playlistsKey] = cipher.encrypt(encodePlaylists(remaining))
            }
            if (prefs[activePlaylistIdKey] == id) prefs.remove(activePlaylistIdKey)
        }
    }

    suspend fun getActivePlaylistId(): String? = store().data.first()[activePlaylistIdKey]

    suspend fun setActivePlaylistId(id: String) {
        store().edit { it[activePlaylistIdKey] = id }
    }

    // Written field by field rather than through reflection: release builds rename class
    // members, and a reflective format would stop reading its own data after an update.
    private fun encodePlaylists(playlists: List<IptvPlaylist>): String {
        val array = com.google.gson.JsonArray()
        playlists.forEach { playlist ->
            array.add(com.google.gson.JsonObject().apply {
                addProperty("id", playlist.id)
                addProperty("name", playlist.name)
                addProperty("type", playlist.type.name)
                addProperty("url", playlist.url)
                addProperty("username", playlist.username)
                addProperty("password", playlist.password)
                addProperty("epgUrl", playlist.epgUrl)
            })
        }
        return array.toString()
    }

    private fun decodePlaylists(stored: String?): List<IptvPlaylist> {
        if (stored.isNullOrBlank()) return emptyList()
        return runCatching {
            com.google.gson.JsonParser.parseString(cipher.decrypt(stored)).asJsonArray.mapNotNull { element ->
                val obj = element.asJsonObject
                fun text(key: String) = obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val type = runCatching { IptvPlaylistType.valueOf(text("type")) }.getOrNull() ?: return@mapNotNull null
                IptvPlaylist(
                    id = text("id").ifBlank { return@mapNotNull null },
                    name = text("name"),
                    type = type,
                    url = text("url").ifBlank { return@mapNotNull null },
                    username = text("username"),
                    password = text("password"),
                    epgUrl = text("epgUrl")
                )
            }
        }.getOrDefault(emptyList())
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
