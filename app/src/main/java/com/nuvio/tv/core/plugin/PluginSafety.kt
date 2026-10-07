package com.nuvio.tv.core.plugin

internal object PluginSafety {
    fun isVideoEasyScraper(
        scraperId: String?,
        scraperName: String? = null,
        filename: String? = null
    ): Boolean {
        return listOf(scraperId, scraperName, filename).any { value ->
            value?.contains("videasy", ignoreCase = true) == true
        }
    }

    /**
     * StreamingCommunity's stream source (vixsrc.to/vixcloud.co) returns HTTP 403 on its
     * playlist pre-check for requests coming from known VPN exit IPs - confirmed against a
     * real ProtonVPN endpoint, and confirmed working with the user's real IP with the VPN off.
     * This is the destination site's own anti-VPN/geo block, not something fixable by changing
     * how NuvioTV talks to it, so this scraper's own traffic is explicitly routed around the
     * app-scoped VPN tunnel (see PluginRuntime's bypassVpnClient) while every other scraper -
     * and the rest of the app - keeps using the VPN when the user has it on.
     *
     * CB01's own requests are routed the same way; its MixDrop streams are covered by the
     * host list below.
     */
    private val VPN_BYPASS_SCRAPERS = listOf("streamingcommunity", "cb01")

    fun shouldBypassVpn(
        scraperId: String?,
        scraperName: String? = null
    ): Boolean {
        return listOf(scraperId, scraperName).any { value ->
            value != null && VPN_BYPASS_SCRAPERS.any { value.contains(it, ignoreCase = true) }
        }
    }

    /**
     * Same anti-VPN block as [shouldBypassVpn], but applied at the actual video-playback
     * layer, where only the resolved stream URL is available (not the scraper that produced
     * it). Matches StreamingCommunity's known stream hosts (vixsrc.to/vixcloud.co) and the
     * unity redirect domains it resolves through, so the ExoPlayer HTTP data source bypasses
     * the VPN the same way PluginRuntime's scraper client does.
     */
    private val VPN_BYPASS_HOSTS = listOf(
        "vixsrc.to",
        "vixcloud.co",
        // The provider's fallback media host when its remote domains.json is unreachable.
        "dancingmonkeyvideolover.xyz",
        "streamingunity.vip",
        "streamingunity.win",
        "streamingunity.fun",
        // MixDrop (used by CB01 and the shared extractor of Guardoserie/AnimeUnity/
        // AnimeSaturn) signs the media URL for the resolving IP *and* User-Agent - verified:
        // a link resolved from the real IP gets 403 when played through the VPN. So both
        // the embed hosts and the delivery hosts (e.g. gfve4dog1.mxcontent.net) bypass,
        // keeping resolution and playback on the same address whichever scraper found it.
        "mxcontent.net",
        "mxdcontent.net"
    )

    // MixDrop rotates TLDs (mixdrop.ag/.co/.to/.ps..., m1xdrop.*): matched by label.
    private val VPN_BYPASS_HOST_LABELS = listOf("mixdrop", "m1xdrop")

    fun shouldBypassVpnForUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = try {
            java.net.URI(url).host
        } catch (_: Exception) {
            null
        }?.lowercase() ?: return false
        if (VPN_BYPASS_HOSTS.any { bypassHost -> host == bypassHost || host.endsWith(".$bypassHost") }) {
            return true
        }
        val labels = host.split('.')
        return labels.size >= 2 && labels.dropLast(1).any { it in VPN_BYPASS_HOST_LABELS }
    }
}
