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
    val showCountryPicker: Boolean = false
) {
    val visibleChannels: List<IptvChannel>
        get() {
            var list = channels
            if (!selectedGroup.isNullOrBlank()) {
                list = list.filter { it.groupTitle == selectedGroup }
            }
            if (searchQuery.isNotBlank()) {
                val q = searchQuery.trim()
                list = list.filter { it.name.contains(q, ignoreCase = true) }
            }
            return list
        }
}

enum class IptvError { NOT_CONFIGURED, LOAD_FAILED }

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
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            channels = channels,
                            groups = groups,
                            error = null,
                            lastChannelUrl = lastChannel
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

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun playChannel(channel: IptvChannel) {
        // Zap through exactly what the user was looking at (current group/search filter).
        IptvZapList.set(_uiState.value.visibleChannels)
        IptvZapList.setProgrammes(_uiState.value.programmesByChannel)
        IptvZapList.markPlayed(channel.streamUrl)
        viewModelScope.launch { repository.setLastChannelUrl(channel.streamUrl) }
        _uiState.update {
            it.copy(
                lastChannelUrl = channel.streamUrl,
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
