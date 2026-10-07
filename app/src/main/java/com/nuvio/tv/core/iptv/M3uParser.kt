package com.nuvio.tv.core.iptv

import com.nuvio.tv.domain.model.IptvChannel
import java.net.URLDecoder
import java.util.Locale

/**
 * Parses an M3U/M3U8 extended playlist (#EXTM3U / #EXTINF format) into a flat
 * list of channels. Only the attributes commonly used by IPTV providers are
 * read - tvg-id/tvg-name/tvg-logo/group-title - plus the request headers some
 * providers require for the stream URL that follows. Those headers can come from
 * any of the forms playlists use in the wild, applied in this order (later wins):
 * - http-user-agent / http-referrer attributes on the #EXTINF line
 * - #EXTVLCOPT:http-user-agent= / http-referrer= / http-origin= lines
 * - #EXTHTTP:{"User-Agent":"..."} JSON lines
 * - Kodi-style options appended to the URL: http://host/stream|User-Agent=x&Referer=y
 */
object M3uParser {
    private val attributeRegex = Regex("""([\w-]+)="([^"]*)"""")
    private val jsonStringPairRegex = Regex(""""([^"\\]+)"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    fun parse(content: String): List<IptvChannel> {
        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val channels = mutableListOf<IptvChannel>()

        var pendingAttributes: Map<String, String>? = null
        var pendingDisplayName: String? = null
        var pendingHeaders: MutableMap<String, String>? = null

        fun addHeader(name: String, value: String) {
            if (value.isBlank()) return
            if (pendingHeaders == null) pendingHeaders = mutableMapOf()
            pendingHeaders!![name] = value
        }

        for (line in lines) {
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> continue
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val separator = displayNameSeparatorIndex(line)
                    val attributesPart = if (separator >= 0) line.substring(0, separator) else line
                    pendingDisplayName = if (separator >= 0) line.substring(separator + 1).trim() else null
                    val attributes = attributeRegex.findAll(attributesPart)
                        .associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }
                    pendingAttributes = attributes
                    pendingHeaders = null
                    attributes["http-user-agent"]?.let { addHeader("User-Agent", it) }
                    (attributes["http-referrer"] ?: attributes["http-referer"])?.let { addHeader("Referer", it) }
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.removePrefix("#EXTVLCOPT:").trim()
                    val eq = opt.indexOf('=')
                    if (eq > 0) {
                        val key = opt.substring(0, eq).trim().lowercase(Locale.ROOT)
                        val headerName = when (key) {
                            "http-user-agent" -> "User-Agent"
                            "http-referrer", "http-referer" -> "Referer"
                            "http-origin" -> "Origin"
                            else -> null
                        }
                        if (headerName != null) addHeader(headerName, opt.substring(eq + 1).trim())
                    }
                }
                line.startsWith("#EXTHTTP:", ignoreCase = true) -> {
                    val json = line.removePrefix("#EXTHTTP:").trim()
                    jsonStringPairRegex.findAll(json).forEach { match ->
                        addHeader(match.groupValues[1], match.groupValues[2].replace("\\\"", "\"").replace("\\/", "/"))
                    }
                }
                line.startsWith("#") -> continue
                else -> {
                    val attrs = pendingAttributes
                    if (attrs != null) {
                        val pipe = line.indexOf('|')
                        val streamUrl = if (pipe >= 0) line.substring(0, pipe).trim() else line
                        if (pipe >= 0) {
                            parseUrlOptions(line.substring(pipe + 1)).forEach { (name, value) -> addHeader(name, value) }
                        }
                        val name = pendingDisplayName?.takeIf { it.isNotBlank() }
                            ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                            ?: "Unknown"
                        channels.add(
                            IptvChannel(
                                id = attrs["tvg-id"]?.takeIf { it.isNotBlank() } ?: streamUrl,
                                name = name,
                                logoUrl = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                                groupTitle = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: "Altro",
                                streamUrl = streamUrl,
                                epgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                                headers = pendingHeaders?.toMap()
                            )
                        )
                    }
                    pendingAttributes = null
                    pendingDisplayName = null
                    pendingHeaders = null
                }
            }
        }
        return channels
    }

    /**
     * Index of the comma that ends the #EXTINF attributes and starts the display name:
     * the first one outside a quoted attribute value. The name itself may contain
     * commas ("Rai 1, HD"), so splitting at the last comma would truncate it.
     */
    private fun displayNameSeparatorIndex(line: String): Int {
        var inQuotes = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }

    /** Kodi-style "User-Agent=x&Referer=y" options, values optionally URL-encoded. */
    private fun parseUrlOptions(options: String): List<Pair<String, String>> =
        options.split('&').mapNotNull { option ->
            val eq = option.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val key = option.substring(0, eq).trim()
            val rawValue = option.substring(eq + 1).trim()
            val value = runCatching { URLDecoder.decode(rawValue, "UTF-8") }.getOrDefault(rawValue)
            val headerName = when (key.lowercase(Locale.ROOT)) {
                "user-agent" -> "User-Agent"
                "referer", "referrer" -> "Referer"
                "origin" -> "Origin"
                "cookie" -> "Cookie"
                // Kodi also accepts non-header options here (e.g. verifypeer=false); only
                // pass through names shaped like real headers (X-Forwarded-For, ...).
                else -> key.takeIf { it.contains('-') } ?: return@mapNotNull null
            }
            headerName to value
        }
}
