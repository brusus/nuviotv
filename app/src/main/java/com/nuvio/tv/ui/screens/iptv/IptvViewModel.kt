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
import com.nuvio.tv.domain.model.IptvVodDetails
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
    /** The saved playlist being edited in the add dialog; null when adding a new one. */
    val editingPlaylist: IptvPlaylist? = null,
    val addPlaylistBusy: Boolean = false,
    /** The Xtream account just entered was rejected by the server. */
    val addPlaylistRejected: Boolean = false,
    val section: IptvSection = IptvSection.LIVE,
    val vodLoading: Boolean = false,
    val vodError: Boolean = false,
    val vodCategories: List<IptvVodCategory> = emptyList(),
    val vodItems: List<IptvVodItem> = emptyList(),
    val selectedVodCategoryId: String = VOD_CATEGORY_RECENT,
    /** Movie or series whose details are open. */
    val seriesDetail: IptvVodItem? = null,
    val seriesEpisodes: List<IptvSeriesEpisode> = emptyList(),
    val seriesLoading: Boolean = false,
    val vodDetails: IptvVodDetails? = null,
    /** Start of every key below for the source in use ("<playlist id>|"). */
    val sourceKeyPrefix: String = "",
    /** Hidden live groups and movie/series categories, as "<prefix><kind>|<id>". */
    val hiddenCategoryKeys: Set<String> = emptySet(),
    /** Starred movies and series, as "<prefix><KIND>|<id>". */
    val favoriteVodKeys: Set<String> = emptySet(),
    /** Channel number being typed on the remote, shown until it is applied. */
    val typedNumber: String = "",
    /** Channel the list must scroll to and focus (number entry); cleared once handled. */
    val jumpToChannelUrl: String? = null,
    /** Both catalogues, loaded when the global search opens. */
    val searchMovies: List<IptvVodItem> = emptyList(),
    val searchSeries: List<IptvVodItem> = emptyList(),
    val searchLoading: Boolean = false,
    /** Groups opened in the folder view of the All tab. */
    val expandedGroups: Set<String> = emptySet(),
    /** Channel whose past programmes (catch-up) are listed. */
    val archiveChannel: IptvChannel? = null
) {
    val activePlaylist: IptvPlaylist?
        get() = playlists.firstOrNull { it.id == activePlaylistId } ?: playlists.firstOrNull()

    /** Movies and series exist only for an Xtream account, and not in the world source. */
    val hasVodSections: Boolean
        get() = !worldSource && activePlaylist?.type == IptvPlaylistType.XTREAM

    fun isGroupHidden(group: String): Boolean = "${sourceKeyPrefix}live|$group" in hiddenCategoryKeys

    private val vodCategoryKind: String
        get() = if (section == IptvSection.SERIES) "series" else "movie"

    fun vodCategoryKey(categoryId: String): String = "$sourceKeyPrefix$vodCategoryKind|$categoryId"

    fun isVodCategoryHidden(categoryId: String): Boolean = vodCategoryKey(categoryId) in hiddenCategoryKeys

    fun vodKey(item: IptvVodItem): String = "$sourceKeyPrefix${item.kind.name}|${item.id}"

    fun isVodFavorite(item: IptvVodItem): Boolean = vodKey(item) in favoriteVodKeys

    val visibleVodItems: List<IptvVodItem>
        get() {
            // A hidden category drops out of the mixed lists, but can still be opened itself.
            val shown = vodItems.filter { !isVodCategoryHidden(it.categoryId) }
            if (searchQuery.isNotBlank()) {
                // Searching looks through the whole section, not just the open category.
                val q = searchQuery.trim()
                return shown.filter { it.name.contains(q, ignoreCase = true) }
            }
            return when (selectedVodCategoryId) {
                VOD_CATEGORY_RECENT -> shown.sortedByDescending { it.addedEpochSeconds }.take(MAX_RECENT_VOD)
                VOD_CATEGORY_ALL -> shown
                VOD_CATEGORY_FAVORITES -> vodItems.filter { isVodFavorite(it) }
                else -> vodItems.filter { it.categoryId == selectedVodCategoryId }
            }
        }

    val visibleChannels: List<IptvChannel>
        get() {
            var list = when (selectedTab) {
                IptvTab.ALL, IptvTab.GUIDE -> channels.filter { !isGroupHidden(it.groupTitle) }
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

    /** Global search: channels, movies and series whose name contains the query. */
    val searchChannels: List<IptvChannel>
        get() = searchQuery.trim().let { q ->
            if (q.length < 2) emptyList()
            else channels.asSequence()
                .filter { !isGroupHidden(it.groupTitle) && it.name.contains(q, ignoreCase = true) }
                .take(MAX_SEARCH_RESULTS)
                .toList()
        }

    val searchVodResults: List<IptvVodItem>
        get() = searchQuery.trim().let { q ->
            if (q.length < 2) emptyList()
            else (searchMovies + searchSeries).filter { it.name.contains(q, ignoreCase = true) }.take(MAX_SEARCH_RESULTS)
        }
}

enum class IptvError { NOT_CONFIGURED, LOAD_FAILED }

enum class IptvTab { ALL, GROUPS, FAVORITES, RECENT, GUIDE }

enum class IptvSection { LIVE, MOVIES, SERIES, SEARCH }

const val VOD_CATEGORY_RECENT = "__recent"
const val VOD_CATEGORY_ALL = "__all"
const val VOD_CATEGORY_FAVORITES = "__favorites"
private const val MAX_RECENT_VOD = 60
private const val MAX_SEARCH_RESULTS = 60
private const val NUMBER_ENTRY_DELAY_MS = 1_300L

@HiltViewModel
class IptvViewModel @Inject constructor(
    private val repository: IptvRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(IptvUiState())
    val uiState: StateFlow<IptvUiState> = _uiState.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null

    fun load(forceRefresh: Boolean = false) {
        // Newest wins: a slow answer for the source shown before (another playlist, another
        // country) must not land on top of the one selected since.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val worldSource = repository.worldSource.first()
            val worldCountry = repository.worldCountry.first()
            val playlists = repository.getPlaylists()
            val activePlaylistId = repository.getActivePlaylist()?.id
            val keyPrefix = repository.sourceKeyPrefix()
            val hidden = repository.getHiddenCategoryKeys()
            val favoriteVod = repository.getFavoriteVodKeys()
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    worldSource = worldSource,
                    worldCountryCode = worldCountry,
                    playlists = playlists,
                    activePlaylistId = activePlaylistId,
                    sourceKeyPrefix = keyPrefix,
                    hiddenCategoryKeys = hidden,
                    favoriteVodKeys = favoriteVod
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
                            // The folder of the last channel watched opens by itself, so the
                            // list can come back to it.
                            expandedGroups = it.expandedGroups +
                                listOfNotNull(channels.firstOrNull { c -> c.streamUrl == lastChannel }?.groupTitle),
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
        _uiState.update {
            it.copy(showPlaylists = false, showAddPlaylist = true, addPlaylistRejected = false, editingPlaylist = null)
        }
    }

    fun openEditPlaylist(id: String) {
        val playlist = _uiState.value.playlists.firstOrNull { it.id == id } ?: return
        _uiState.update {
            it.copy(showPlaylists = false, showAddPlaylist = true, addPlaylistRejected = false, editingPlaylist = playlist)
        }
    }

    fun dismissAddPlaylist() {
        _uiState.update { it.copy(showAddPlaylist = false, addPlaylistBusy = false, addPlaylistRejected = false, editingPlaylist = null) }
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

    fun addM3uPlaylist(name: String, url: String, epgUrl: String, userAgent: String) {
        if (url.isBlank()) return
        savePlaylist(
            IptvPlaylist(
                id = editedOrNewId(),
                name = name.trim().ifEmpty { hostOf(url) },
                type = IptvPlaylistType.M3U,
                url = url.trim(),
                epgUrl = epgUrl.trim(),
                userAgent = userAgent.trim()
            ),
            verify = false
        )
    }

    fun addXtreamPlaylist(name: String, server: String, username: String, password: String, userAgent: String) {
        if (server.isBlank() || username.isBlank() || password.isBlank()) return
        savePlaylist(
            IptvPlaylist(
                id = editedOrNewId(),
                name = name.trim().ifEmpty { hostOf(server) },
                type = IptvPlaylistType.XTREAM,
                url = server.trim(),
                username = username.trim(),
                password = password,
                userAgent = userAgent.trim()
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
            _uiState.update { it.copy(showAddPlaylist = false, addPlaylistBusy = false, editingPlaylist = null) }
            resetForNewSource()
            // An edited playlist keeps its id: skip what was cached for the old address or login.
            load(forceRefresh = true)
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
                seriesDetail = null,
                searchMovies = emptyList(),
                searchSeries = emptyList(),
                archiveChannel = null,
                expandedGroups = emptySet(),
                typedNumber = "",
                jumpToChannelUrl = null
            )
        }
    }

    private fun newPlaylistId(): String = java.util.UUID.randomUUID().toString()

    // Keeping the id keeps the favourites, recents and hidden groups tied to the playlist.
    private fun editedOrNewId(): String = _uiState.value.editingPlaylist?.id ?: newPlaylistId()

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(com.nuvio.tv.core.iptv.XtreamApi.baseUrl(url)).host }.getOrNull().orEmpty().ifEmpty { url.trim() }

    // --- Movies and series (Xtream) ------------------------------------------------------

    fun selectSection(section: IptvSection) {
        if (_uiState.value.section == section) return
        _uiState.update {
            it.copy(section = section, searchQuery = "", selectedVodCategoryId = VOD_CATEGORY_RECENT, seriesDetail = null)
        }
        when (section) {
            IptvSection.MOVIES, IptvSection.SERIES -> loadVod()
            IptvSection.SEARCH -> loadSearchCatalogues()
            IptvSection.LIVE -> Unit
        }
    }

    fun loadVod(forceRefresh: Boolean = false) {
        val kind = when (_uiState.value.section) {
            IptvSection.MOVIES -> IptvVodKind.MOVIE
            IptvSection.SERIES -> IptvVodKind.SERIES
            IptvSection.LIVE, IptvSection.SEARCH -> return
        }
        // Same rule as load(): switching Movies/Series quickly must not mix the two lists.
        vodJob?.cancel()
        vodJob = viewModelScope.launch {
            _uiState.update { it.copy(vodLoading = true, vodError = false, vodItems = emptyList(), vodCategories = emptyList()) }
            repository.getVod(kind, forceRefresh).fold(
                onSuccess = { (categories, items) ->
                    _uiState.update { it.copy(vodLoading = false, vodCategories = categories, vodItems = items) }
                },
                onFailure = { _uiState.update { it.copy(vodLoading = false, vodError = true) } }
            )
        }
    }

    private var vodJob: kotlinx.coroutines.Job? = null

    fun selectVodCategory(id: String) {
        _uiState.update { it.copy(selectedVodCategoryId = id) }
    }

    /** Opens the details of a movie or series: plot and credits, plus the episodes of a series. */
    fun openVodItem(item: IptvVodItem) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(seriesDetail = item, seriesEpisodes = emptyList(), vodDetails = null, seriesLoading = true)
            }
            val (details, episodes) = repository.getVodInfo(item)
                .getOrDefault(IptvVodDetails() to emptyList())
            _uiState.update {
                if (it.seriesDetail?.id == item.id && it.seriesDetail.kind == item.kind) {
                    it.copy(vodDetails = details, seriesEpisodes = episodes, seriesLoading = false)
                } else {
                    it
                }
            }
        }
    }

    fun dismissSeries() {
        _uiState.update {
            it.copy(seriesDetail = null, seriesEpisodes = emptyList(), vodDetails = null, seriesLoading = false)
        }
    }

    fun playMovie(item: IptvVodItem) {
        viewModelScope.launch {
            val url = repository.movieUrl(item) ?: return@launch
            val headers = repository.playbackHeaders()
            _uiState.update {
                it.copy(playbackRequest = IptvPlaybackRequest(url, headers, item.name, contentType = "movie"))
            }
        }
    }

    fun playEpisode(episode: IptvSeriesEpisode) {
        val series = _uiState.value.seriesDetail ?: return
        viewModelScope.launch {
            val url = repository.episodeUrl(episode) ?: return@launch
            val headers = repository.playbackHeaders()
            val label = "${series.name} S${episode.season}E${episode.episode}"
            _uiState.update {
                it.copy(playbackRequest = IptvPlaybackRequest(url, headers, label, contentType = "movie"))
            }
        }
    }

    fun toggleVodFavorite(item: IptvVodItem) {
        viewModelScope.launch {
            val keys = repository.toggleFavoriteVod(_uiState.value.vodKey(item))
            _uiState.update { it.copy(favoriteVodKeys = keys) }
        }
    }

    // --- Hidden categories ---------------------------------------------------------------

    fun toggleGroupHidden(group: String) {
        toggleHidden("${_uiState.value.sourceKeyPrefix}live|$group")
    }

    fun toggleVodCategoryHidden(categoryId: String) {
        toggleHidden(_uiState.value.vodCategoryKey(categoryId))
    }

    private fun toggleHidden(key: String) {
        viewModelScope.launch {
            val keys = repository.toggleHiddenCategory(key)
            _uiState.update { it.copy(hiddenCategoryKeys = keys) }
        }
    }

    // --- Channel number entry --------------------------------------------------------------

    private var numberJob: kotlinx.coroutines.Job? = null

    /** Digits typed on the remote form a channel number; after a pause the list jumps to it. */
    fun onDigit(digit: Int) {
        val typed = (_uiState.value.typedNumber + digit).takeLast(4)
        _uiState.update { it.copy(typedNumber = typed) }
        numberJob?.cancel()
        numberJob = viewModelScope.launch {
            kotlinx.coroutines.delay(NUMBER_ENTRY_DELAY_MS)
            val state = _uiState.value
            val channel = state.visibleChannels.getOrNull((typed.toIntOrNull() ?: 0) - 1)
            _uiState.update {
                it.copy(
                    typedNumber = "",
                    expandedGroups = it.expandedGroups + listOfNotNull(channel?.groupTitle),
                    jumpToChannelUrl = channel?.streamUrl ?: it.jumpToChannelUrl,
                    previewChannel = channel ?: it.previewChannel
                )
            }
        }
    }

    fun consumeChannelJump() {
        _uiState.update { it.copy(jumpToChannelUrl = null) }
    }

    // --- Global search ---------------------------------------------------------------------

    private fun loadSearchCatalogues() {
        viewModelScope.launch {
            _uiState.update { it.copy(searchLoading = true) }
            val movies = repository.getVod(IptvVodKind.MOVIE).getOrNull()?.second.orEmpty()
            val series = repository.getVod(IptvVodKind.SERIES).getOrNull()?.second.orEmpty()
            _uiState.update { it.copy(searchLoading = false, searchMovies = movies, searchSeries = series) }
        }
    }

    // --- Catch-up ----------------------------------------------------------------------------

    fun openArchive(channel: IptvChannel) {
        _uiState.update { it.copy(archiveChannel = channel) }
    }

    fun dismissArchive() {
        _uiState.update { it.copy(archiveChannel = null) }
    }

    /** Programmes of [channel] that already ended and are still inside its archive window. */
    fun pastProgrammes(channel: IptvChannel, nowMillis: Long = System.currentTimeMillis()): List<EpgProgramme> {
        val programmes = programmesFor(channel) ?: return emptyList()
        val oldest = nowMillis - channel.archiveDays * 24L * 60L * 60L * 1000L
        return programmes
            .filter { it.stopMillis <= nowMillis && it.startMillis >= oldest }
            .sortedByDescending { it.startMillis }
    }

    fun playArchive(programme: EpgProgramme) {
        val channel = _uiState.value.archiveChannel ?: return
        viewModelScope.launch {
            val url = repository.timeshiftUrl(channel, programme) ?: return@launch
            _uiState.update {
                it.copy(
                    archiveChannel = null,
                    playbackRequest = IptvPlaybackRequest(
                        streamUrl = url,
                        headers = channel.headers,
                        title = "${channel.name} · ${programme.title}",
                        contentType = "movie"
                    )
                )
            }
        }
    }

    fun toggleGroupExpanded(group: String) {
        _uiState.update {
            it.copy(expandedGroups = if (group in it.expandedGroups) it.expandedGroups - group else it.expandedGroups + group)
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
