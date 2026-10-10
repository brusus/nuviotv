package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.iptv.IptvRepository
import com.nuvio.tv.core.iptv.EpgIdMatcher
import com.nuvio.tv.core.iptv.IptvZapList
import com.nuvio.tv.core.iptv.WorldCountry
import kotlinx.coroutines.flow.first
import com.nuvio.tv.domain.model.EpgProgramme
import com.nuvio.tv.domain.model.IptvChannel
import com.nuvio.tv.domain.model.IptvPlaylist
import com.nuvio.tv.domain.model.IptvPlaylistType
import com.nuvio.tv.domain.model.IptvSeriesEpisode
import com.nuvio.tv.domain.model.IptvVodCategory
import com.nuvio.tv.domain.model.IptvVodItem
import com.nuvio.tv.domain.model.IptvVodKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val PREVIEW_FOCUS_DELAY_MS = 900L

data class IptvPlaybackRequest(
    val streamUrl: String,
    val headers: Map<String, String>?,
    val title: String,
    /** "channel" for live TV (enables zapping in the player), "movie" for films and episodes. */
    val contentType: String = "channel"
)

data class IptvUiState(
    val isLoading: Boolean = true,
    val error: IptvError? = null,
    val channels: List<IptvChannel> = emptyList(),
    val groups: List<String> = emptyList(),
    val selectedGroup: String? = null,
    val searchQuery: String = "",
    val programmesByChannel: Map<String, List<EpgProgramme>> = emptyMap(),
    val playbackRequest: IptvPlaybackRequest? = null,
    /** Stream URL of the last channel watched: the grid opens scrolled to and focused on it. */
    val lastChannelUrl: String? = null,
    /** Showing iptv-org's free channels for [worldCountryCode] instead of the user's playlist. */
    val worldSource: Boolean = false,
    val worldCountryCode: String = "it",
    val worldCountries: List<WorldCountry> = emptyList(),
    val showCountryPicker: Boolean = false,
    val selectedTab: IptvTab = IptvTab.ALL,
    /** Stream URLs of the starred channels. */
    val favoriteUrls: Set<String> = emptySet(),
    /** Stream URLs of the channels watched most recently, newest first. */
    val recentUrls: List<String> = emptyList(),
    /** Channel shown in the preview pane: follows the focused row after a short pause. */
    val previewChannel: IptvChannel? = null,
    val playlists: List<IptvPlaylist> = emptyList(),
    val activePlaylistId: String? = null,
    val showPlaylists: Boolean = false,
    val showAddPlaylist: Boolean = false,
    val addPlaylistBusy: Boolean = false,
    /** The Xtream account just entered was rejected by the server. */
    val addPlaylistRejected: Boolean = false,
    val section: IptvSection = IptvSection.LIVE,
    val vodLoading: Boolean = false,
    val vodError: Boolean = false,
    val vodCategories: List<IptvVodCategory> = emptyList(),
    val vodItems: List<IptvVodItem> = emptyList(),
    val selectedVodCategoryId: String = VOD_CATEGORY_RECENT,
    /** Series whose episode list is open. */
    val seriesDetail: IptvVodItem? = null,
    val seriesEpisodes: List<IptvSeriesEpisode> = emptyList(),
    val seriesLoading: Boolean = false
) {
    val activePlaylist: IptvPlaylist?
        get() = playlists.firstOrNull { it.id == activePlaylistId } ?: playlists.firstOrNull()

    /** Movies and series exist only for an Xtream account, and not in the world source. */
    val hasVodSections: Boolean
        get() = !worldSource && activePlaylist?.type == IptvPlaylistType.XTREAM

    val visibleVodItems: List<IptvVodItem>
        get() {
            if (searchQuery.isNotBlank()) {
                // Searching looks through the whole section, not just the open category.
                val q = searchQuery.trim()
                return vodItems.filter { it.name.contains(q, ignoreCase = true) }
            }
            return when (selectedVodCategoryId) {
                VOD_CATEGORY_RECENT -> vodItems.sortedByDescending { it.addedEpochSeconds }.take(MAX_RECENT_VOD)
                VOD_CATEGORY_ALL -> vodItems
                else -> vodItems.filter { it.categoryId == selectedVodCategoryId }
            }
        }

    val visibleChannels: List<IptvChannel>
        get() {
            var list = when (selectedTab) {
                IptvTab.ALL -> channels
                IptvTab.GROUPS ->
                    if (selectedGroup.isNullOrBlank()) emptyList()
                    else channels.filter { it.groupTitle == selectedGroup }
                IptvTab.FAVORITES -> channels.filter { it.streamUrl in favoriteUrls }
                IptvTab.RECENT -> {
                    val byUrl = channels.associateBy { it.streamUrl }
                    recentUrls.mapNotNull { byUrl[it] }
                }
            }
            if (searchQuery.isNotBlank()) {
                val q = searchQuery.trim()
                list = list.filter { it.name.contains(q, ignoreCase = true) }
            }
            return list
        }

    /** Groups with their channel count, for the Groups tab; honours the search query. */
    val visibleGroups: List<Pair<String, Int>>
        get() {
            val q = searchQuery.trim()
            val counts = channels.groupingBy { it.groupTitle }.eachCount()
            return groups
                .filter { q.isEmpty() || it.contains(q, ignoreCase = true) }
                .map { it to (counts[it] ?: 0) }
        }

    /** True while the Groups tab shows the list of groups rather than one group's channels. */
    val showingGroupList: Boolean
        get() = selectedTab == IptvTab.GROUPS && selectedGroup.isNullOrBlank()
}

enum class IptvError { NOT_CONFIGURED, LOAD_FAILED }

enum class IptvTab { ALL, GROUPS, FAVORITES, RECENT }

enum class IptvSection { LIVE, MOVIES, SERIES }

const val VOD_CATEGORY_RECENT = "__recent"
const val VOD_CATEGORY_ALL = "__all"
private const val MAX_RECENT_VOD = 60

@HiltViewModel
class IptvViewModel @Inject constructor(
    private val repository: IptvRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(IptvUiState())
    val uiState: StateFlow<IptvUiState> = _uiState.asStateFlow()

    fun load(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            val worldSource = repository.worldSource.first()
            val worldCountry = repository.worldCountry.first()
            val playlists = repository.getPlaylists()
            val activePlaylistId = repository.getActivePlaylist()?.id
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    worldSource = worldSource,
                    worldCountryCode = worldCountry,
                    playlists = playlists,
                    activePlaylistId = activePlaylistId
                )
            }
            if (worldSource) loadWorldCountries()
            val result = repository.getChannels(forceRefresh)
            result.fold(
                onSuccess = { channels ->
                    val groups = channels.map { it.groupTitle }.distinct().sorted()
                    // A channel zapped to inside the player wins over the stored one; store it.
                    val zappedTo = IptvZapList.lastPlayedUrl
                    if (zappedTo != null) repository.setLastChannelUrl(zappedTo)
                    val lastChannel = zappedTo ?: repository.getLastChannelUrl()
                    val recents = if (zappedTo != null) {
                        repository.addRecentChannel(zappedTo)
                    } else {
                        repository.getRecentChannelUrls()
                    }
                    val favorites = repository.getFavoriteChannelUrls()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            channels = channels,
                            groups = groups,
                            error = null,
                            lastChannelUrl = lastChannel,
                            favoriteUrls = favorites,
                            recentUrls = recents,
                            // Keep the preview on a channel of the list just loaded.
                            previewChannel = it.previewChannel?.takeIf { p -> channels.any { c -> c.streamUrl == p.streamUrl } }
                                ?: channels.firstOrNull { c -> c.streamUrl == lastChannel }
                        )
                    }
                    val programmes = repository.getProgrammesByChannel(forceRefresh)
                    _uiState.update { it.copy(programmesByChannel = programmes) }
                    // The guide often lands after a channel was already started: hand it to
                    // the player too, so its banner shows now/next from the next zap on.
                    IptvZapList.setProgrammes(programmes)
                },
                onFailure = { throwable ->
                    val error = if (throwable.message == "no_playlist_url") {
                        IptvError.NOT_CONFIGURED
                    } else {
                        IptvError.LOAD_FAILED
                    }
                    _uiState.update { it.copy(isLoading = false, error = error) }
                }
            )
        }
    }

    /** Switches between the user's playlist and iptv-org's free channels, then reloads. */
    fun selectWorldSource(enabled: Boolean) {
        if (_uiState.value.worldSource == enabled) {
            if (enabled) openCountryPicker()
            return
        }
        viewModelScope.launch {
            repository.setWorldSource(enabled)
            _uiState.update { it.copy(selectedGroup = null) }
            load()
        }
    }

    fun openCountryPicker() {
        viewModelScope.launch {
            loadWorldCountries()
            _uiState.update { it.copy(showCountryPicker = true) }
        }
    }

    fun dismissCountryPicker() {
        _uiState.update { it.copy(showCountryPicker = false) }
    }

    fun selectCountry(code: String) {
        viewModelScope.launch {
            repository.setWorldCountry(code)
            _uiState.update { it.copy(showCountryPicker = false, selectedGroup = null) }
            load()
        }
    }

    private suspend fun loadWorldCountries() {
        if (_uiState.value.worldCountries.isNotEmpty()) return
        repository.getWorldCountries().onSuccess { countries ->
            _uiState.update { it.copy(worldCountries = countries) }
        }
    }

    fun selectGroup(group: String?) {
        _uiState.update { it.copy(selectedGroup = group) }
    }

    // --- Playlists ---------------------------------------------------------------------

    fun openPlaylists() {
        _uiState.update { it.copy(showPlaylists = true) }
    }

    fun dismissPlaylists() {
        _uiState.update { it.copy(showPlaylists = false) }
    }

    fun openAddPlaylist() {
        _uiState.update { it.copy(showPlaylists = false, showAddPlaylist = true, addPlaylistRejected = false) }
    }

    fun dismissAddPlaylist() {
        _uiState.update { it.copy(showAddPlaylist = false, addPlaylistBusy = false, addPlaylistRejected = false) }
    }

    fun selectPlaylist(id: String) {
        viewModelScope.launch {
            repository.setActivePlaylist(id)
            if (_uiState.value.worldSource) repository.setWorldSource(false)
            resetForNewSource()
            load()
        }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch {
            repository.removePlaylist(id)
            resetForNewSource()
            load()
        }
    }

    fun addM3uPlaylist(name: String, url: String, epgUrl: String) {
        if (url.isBlank()) return
        savePlaylist(
            IptvPlaylist(
                id = newPlaylistId(),
                name = name.trim().ifEmpty { hostOf(url) },
                type = IptvPlaylistType.M3U,
                url = url.trim(),
                epgUrl = epgUrl.trim()
            ),
            verify = false
        )
    }

    fun addXtreamPlaylist(name: String, server: String, username: String, password: String) {
        if (server.isBlank() || username.isBlank() || password.isBlank()) return
        savePlaylist(
            IptvPlaylist(
                id = newPlaylistId(),
                name = name.trim().ifEmpty { hostOf(server) },
                type = IptvPlaylistType.XTREAM,
                url = server.trim(),
                username = username.trim(),
                password = password
            ),
            verify = true
        )
    }

    private fun savePlaylist(playlist: IptvPlaylist, verify: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(addPlaylistBusy = true, addPlaylistRejected = false) }
            // An Xtream account is checked first, so a typo is reported in the dialog
            // instead of leaving a playlist that never loads.
            if (verify && !repository.verifyXtreamAccount(playlist)) {
                _uiState.update { it.copy(addPlaylistBusy = false, addPlaylistRejected = true) }
                return@launch
            }
            repository.addPlaylist(playlist)
            if (_uiState.value.worldSource) repository.setWorldSource(false)
            _uiState.update { it.copy(showAddPlaylist = false, addPlaylistBusy = false) }
            resetForNewSource()
            load()
        }
    }

    private fun resetForNewSource() {
        _uiState.update {
            it.copy(
                showPlaylists = false,
                section = IptvSection.LIVE,
                selectedTab = IptvTab.ALL,
                selectedGroup = null,
                searchQuery = "",
                previewChannel = null,
                vodCategories = emptyList(),
                vodItems = emptyList(),
                selectedVodCategoryId = VOD_CATEGORY_RECENT,
                seriesDetail = null
            )
        }
    }

    private fun newPlaylistId(): String = java.util.UUID.randomUUID().toString()

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(com.nuvio.tv.core.iptv.XtreamApi.baseUrl(url)).host }.getOrNull().orEmpty().ifEmpty { url.trim() }

    // --- Movies and series (Xtream) ------------------------------------------------------

    fun selectSection(section: IptvSection) {
        if (_uiState.value.section == section) return
        _uiState.update {
            it.copy(section = section, searchQuery = "", selectedVodCategoryId = VOD_CATEGORY_RECENT, seriesDetail = null)
        }
        if (section != IptvSection.LIVE) loadVod()
    }

    fun loadVod(forceRefresh: Boolean = false) {
        val kind = when (_uiState.value.section) {
            IptvSection.MOVIES -> IptvVodKind.MOVIE
            IptvSection.SERIES -> IptvVodKind.SERIES
            IptvSection.LIVE -> return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(vodLoading = true, vodError = false, vodItems = emptyList(), vodCategories = emptyList()) }
            repository.getVod(kind, forceRefresh).fold(
                onSuccess = { (categories, items) ->
                    _uiState.update { it.copy(vodLoading = false, vodCategories = categories, vodItems = items) }
                },
                onFailure = { _uiState.update { it.copy(vodLoading = false, vodError = true) } }
            )
        }
    }

    fun selectVodCategory(id: String) {
        _uiState.update { it.copy(selectedVodCategoryId = id) }
    }

    /** A movie starts playing; a series opens its episode list. */
    fun openVodItem(item: IptvVodItem) {
        viewModelScope.launch {
            if (item.kind == IptvVodKind.MOVIE) {
                val url = repository.movieUrl(item) ?: return@launch
                _uiState.update {
                    it.copy(playbackRequest = IptvPlaybackRequest(url, null, item.name, contentType = "movie"))
                }
            } else {
                _uiState.update { it.copy(seriesDetail = item, seriesEpisodes = emptyList(), seriesLoading = true) }
                val episodes = repository.getSeriesEpisodes(item).getOrDefault(emptyList())
                _uiState.update {
                    if (it.seriesDetail?.id == item.id) it.copy(seriesEpisodes = episodes, seriesLoading = false) else it
                }
            }
        }
    }

    fun dismissSeries() {
        _uiState.update { it.copy(seriesDetail = null, seriesEpisodes = emptyList(), seriesLoading = false) }
    }

    fun playEpisode(episode: IptvSeriesEpisode) {
        val series = _uiState.value.seriesDetail ?: return
        viewModelScope.launch {
            val url = repository.episodeUrl(episode) ?: return@launch
            val label = "${series.name} S${episode.season}E${episode.episode}"
            _uiState.update {
                it.copy(playbackRequest = IptvPlaybackRequest(url, null, label, contentType = "movie"))
            }
        }
    }

    fun selectTab(tab: IptvTab) {
        _uiState.update {
            // Entering Groups always starts from the list of groups.
            it.copy(selectedTab = tab, selectedGroup = null)
        }
    }

    fun toggleFavorite(channel: IptvChannel) {
        viewModelScope.launch {
            val favorites = repository.toggleFavoriteChannel(channel.streamUrl)
            _uiState.update { it.copy(favoriteUrls = favorites) }
        }
    }

    private var previewJob: kotlinx.coroutines.Job? = null

    /**
     * The preview follows the focused row, but only once the selection rests on it: starting
     * a stream for every row scrolled past would stall the list on low-end TV sticks.
     */
    fun onChannelFocused(channel: IptvChannel) {
        if (_uiState.value.previewChannel?.streamUrl == channel.streamUrl) return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            kotlinx.coroutines.delay(PREVIEW_FOCUS_DELAY_MS)
            _uiState.update { it.copy(previewChannel = channel) }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun playChannel(channel: IptvChannel) {
        // Zap through exactly what the user was looking at (current group/search filter).
        IptvZapList.set(_uiState.value.visibleChannels)
        IptvZapList.setProgrammes(_uiState.value.programmesByChannel)
        IptvZapList.markPlayed(channel.streamUrl)
        previewJob?.cancel()
        viewModelScope.launch {
            repository.setLastChannelUrl(channel.streamUrl)
            val recents = repository.addRecentChannel(channel.streamUrl)
            _uiState.update { it.copy(recentUrls = recents) }
        }
        _uiState.update {
            it.copy(
                lastChannelUrl = channel.streamUrl,
                previewChannel = channel,
                playbackRequest = IptvPlaybackRequest(
                    streamUrl = channel.streamUrl,
                    headers = channel.headers,
                    title = channel.name
                )
            )
        }
    }

    // Rebuilt only when a new guide is loaded (the map instance changes), not per lookup.
    private var normalizedIndexSource: Map<String, List<EpgProgramme>>? = null
    private var normalizedIndex: Map<String, List<EpgProgramme>> = emptyMap()

    private fun programmesFor(channel: IptvChannel): List<EpgProgramme>? {
        val programmes = _uiState.value.programmesByChannel
        if (programmes !== normalizedIndexSource) {
            normalizedIndex = EpgIdMatcher.normalizedIndex(programmes)
            normalizedIndexSource = programmes
        }
        return EpgIdMatcher.lookup(channel.epgId, programmes, normalizedIndex)
    }

    fun consumePlaybackRequest() {
        _uiState.update { it.copy(playbackRequest = null) }
    }

    /** Current "on now" programme title for a channel, if EPG data is loaded and matches. */
    fun nowPlayingTitle(channel: IptvChannel, nowMillis: Long = System.currentTimeMillis()): String? {
        val programmes = programmesFor(channel) ?: return null
        return programmes.firstOrNull { nowMillis in it.startMillis until it.stopMillis }?.title
    }

    /** Upcoming programmes for a channel (including the current one), for a guide view. */
    fun upcomingProgrammes(channel: IptvChannel, nowMillis: Long = System.currentTimeMillis()): List<EpgProgramme> {
        val programmes = programmesFor(channel) ?: return emptyList()
        return programmes.filter { it.stopMillis > nowMillis }.sortedBy { it.startMillis }
    }
}
