package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.iptv.IptvZapList
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MIN_CHANNEL_ZAP_INTERVAL_MS = 400L
private const val CHANNEL_BANNER_DURATION_MS = 5_000L

/** Re-evaluates whether CH+/CH- can zap from the stream currently playing. */
internal fun PlayerRuntimeController.refreshChannelZapAvailability() {
    val canZap = LivePlaybackUiPolicy.isLiveContentType(contentType) && IptvZapList.contains(currentStreamUrl)
    if (_uiState.value.canZapChannels != canZap) {
        _uiState.update { it.copy(canZapChannels = canZap) }
    }
    // Once per channel: on the first start and after each zap, not on reconnect rebuilds.
    if (canZap && currentStreamUrl != lastChannelBannerStreamUrl) {
        lastChannelBannerStreamUrl = currentStreamUrl
        showChannelBanner()
    }
}

/** Shows the number/logo/name + now/next banner for the current channel, then hides it. */
internal fun PlayerRuntimeController.showChannelBanner() {
    val url = currentStreamUrl
    val channel = IptvZapList.find(url) ?: return
    val (now, next) = IptvZapList.nowAndNext(channel)
    val info = ChannelBannerInfo(
        number = IptvZapList.indexOf(url) + 1,
        total = IptvZapList.size(),
        name = channel.name,
        logoUrl = channel.logoUrl,
        nowTitle = now?.title,
        nowStartMs = now?.startMillis,
        nowEndMs = now?.stopMillis,
        nextTitle = next?.title,
        nextStartMs = next?.startMillis
    )
    _uiState.update { it.copy(channelBanner = info) }
    channelBannerJob?.cancel()
    channelBannerJob = scope.launch {
        delay(CHANNEL_BANNER_DURATION_MS)
        _uiState.update { it.copy(channelBanner = null) }
    }
}

/**
 * Switches to the IPTV channel [delta] positions away in the list the user started from,
 * through the regular in-player source switch (same teardown, retry reset and header
 * handling as picking another source), and retitles the player for the new channel.
 */
internal fun PlayerRuntimeController.zapToChannel(delta: Int) {
    if (!_uiState.value.canZapChannels) return
    // A held key auto-repeats many times a second; each zap tears down and rebuilds the
    // player, so space them out instead of starting (and aborting) a dozen streams.
    val now = android.os.SystemClock.elapsedRealtime()
    if (now - lastChannelZapAtMs < MIN_CHANNEL_ZAP_INTERVAL_MS) return
    lastChannelZapAtMs = now
    val next = IptvZapList.step(currentStreamUrl, delta) ?: return
    playIptvChannel(next)
}

internal fun PlayerRuntimeController.showChannelList() {
    if (!_uiState.value.canZapChannels) return
    _uiState.update {
        it.copy(showChannelList = true, iptvChannels = IptvZapList.all(), showControls = false)
    }
}

internal fun PlayerRuntimeController.dismissChannelList() {
    _uiState.update { it.copy(showChannelList = false) }
}

/** Plays the channel picked in the in-player list (no-op if it is the current one). */
internal fun PlayerRuntimeController.selectChannel(streamUrl: String) {
    dismissChannelList()
    if (streamUrl == currentStreamUrl) return
    val channel = IptvZapList.find(streamUrl) ?: return
    lastChannelZapAtMs = android.os.SystemClock.elapsedRealtime()
    playIptvChannel(channel)
}

private fun PlayerRuntimeController.playIptvChannel(next: com.nuvio.tv.domain.model.IptvChannel) {
    val stream = Stream(
        name = next.name,
        title = next.name,
        description = null,
        url = next.streamUrl,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = next.headers?.let { ProxyHeaders(request = it, response = null) }
        ),
        addonName = "IPTV",
        addonLogo = next.logoUrl
    )
    switchToSourceStream(stream)
    IptvZapList.markPlayed(next.streamUrl)
    _uiState.update { it.copy(title = next.name, logo = next.logoUrl) }
    refreshChannelZapAvailability()
}
