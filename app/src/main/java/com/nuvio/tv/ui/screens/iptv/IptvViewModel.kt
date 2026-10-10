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
    val title: String
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
    val previewChannel: IptvChannel? = null
) {
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
            _uiState.update {
                it.copy(isLoading = true, error = null, worldSource = worldSource, worldCountryCode = worldCountry)
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
