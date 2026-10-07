package com.nuvio.tv.core.iptv

import com.nuvio.tv.domain.model.EpgProgramme
import com.nuvio.tv.domain.model.IptvChannel

/**
 * The channel list the user was browsing when they started an IPTV channel, kept so the
 * player can zap (CH+/CH-) through it. The player only receives a stream URL through
 * navigation, so this in-memory hand-off is what tells it which channels come before and
 * after the current one - in the same order and group filter the user saw.
 */
object IptvZapList {
    @Volatile
    private var channels: List<IptvChannel> = emptyList()

    @Volatile
    private var programmesByChannel: Map<String, List<EpgProgramme>> = emptyMap()

    @Volatile
    private var normalizedProgrammes: Map<String, List<EpgProgramme>> = emptyMap()

    /** EPG loaded by the IPTV screen, keyed by tvg-id, for the in-player channel banner. */
    fun setProgrammes(programmes: Map<String, List<EpgProgramme>>) {
        if (programmes === programmesByChannel) return
        normalizedProgrammes = EpgIdMatcher.normalizedIndex(programmes)
        programmesByChannel = programmes
    }

    /** Programme on air at [nowMillis] and the one after it, from the EPG (if loaded). */
    fun nowAndNext(channel: IptvChannel, nowMillis: Long = System.currentTimeMillis()): Pair<EpgProgramme?, EpgProgramme?> {
        val programmes = EpgIdMatcher.lookup(channel.epgId, programmesByChannel, normalizedProgrammes)
            ?: return null to null
        val upcoming = programmes.filter { it.stopMillis > nowMillis }.sortedBy { it.startMillis }
        val now = upcoming.firstOrNull { nowMillis >= it.startMillis }
        val next = upcoming.firstOrNull { it.startMillis >= (now?.stopMillis ?: nowMillis) && it !== now }
        return now to next
    }

    fun indexOf(streamUrl: String?): Int = channels.indexOfFirst { it.streamUrl == streamUrl }

    fun size(): Int = channels.size

    fun set(list: List<IptvChannel>) {
        channels = list.toList()
    }

    /**
     * Last channel actually played, including zaps done inside the player (which has no
     * access to preferences). The IPTV screen persists it and reopens the grid on it.
     */
    @Volatile
    var lastPlayedUrl: String? = null
        private set

    fun markPlayed(streamUrl: String) {
        lastPlayedUrl = streamUrl
    }

    /** The whole list, for the in-player channel picker. */
    fun all(): List<IptvChannel> = channels

    fun find(streamUrl: String): IptvChannel? = channels.firstOrNull { it.streamUrl == streamUrl }

    /** Whether [streamUrl] belongs to the current zap list (i.e. zapping is possible). */
    fun contains(streamUrl: String?): Boolean =
        streamUrl != null && channels.size > 1 && channels.any { it.streamUrl == streamUrl }

    /** The channel [delta] steps from [currentStreamUrl], wrapping around the list ends. */
    fun step(currentStreamUrl: String?, delta: Int): IptvChannel? {
        val list = channels
        if (list.size < 2) return null
        val index = list.indexOfFirst { it.streamUrl == currentStreamUrl }
        if (index < 0) return null
        return list[Math.floorMod(index + delta, list.size)]
    }
}
