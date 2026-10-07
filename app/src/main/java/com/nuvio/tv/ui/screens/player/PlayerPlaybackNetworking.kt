package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.plugin.PluginSafety
import okhttp3.OkHttpClient
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object PlayerPlaybackNetworking {
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val playbackHostnameVerifier = HostnameVerifier { _, _ -> true }

    private const val TAG = "PlayerPlaybackNetworking"

    private fun connectivityManagerOf(context: Context): ConnectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun isVpnActive(context: Context): Boolean = try {
        val connectivityManager = connectivityManagerOf(context)
        val activeNetwork = connectivityManager.activeNetwork
        activeNetwork != null &&
            connectivityManager.getNetworkCapabilities(activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    } catch (e: Exception) {
        false
    }

    /**
     * Finds a physical (non-VPN) network to bind sockets to. Used to route
     * StreamingCommunity's traffic around an active app-scoped VPN tunnel, since its
     * stream host (vixsrc.to/vixcloud.co) returns HTTP 403 to requests coming from
     * known VPN exit IPs - see [PluginSafety.shouldBypassVpnForUrl]. Re-resolved on every
     * call rather than cached: a Network object can go stale (Wi-Fi reassociation, DHCP
     * renewal, etc.) and sockets bound to a dead one fail outright.
     */
    private fun findNonVpnNetwork(context: Context): Network? = try {
        val connectivityManager = connectivityManagerOf(context)
        connectivityManager.allNetworks.firstOrNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network)
            caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to look up a non-VPN network for VPN bypass", e)
        null
    }

    /**
     * Returns the non-VPN network [url] should be routed through, or null if [url] doesn't
     * need a VPN bypass, no VPN is currently active (nothing to bypass), or none is available.
     */
    internal fun networkForVpnBypass(context: Context, url: String?): Network? =
        if (PluginSafety.shouldBypassVpnForUrl(url) && isVpnActive(context)) {
            findNonVpnNetwork(context)
        } else {
            null
        }

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    }

    /**
     * Fallback OkHttpClient equipped with trust-all SSL configuration for self-signed
     * or untrusted local media servers (e.g. self-signed WebDAV / Plex / Jellyfin).
     */
    internal val trustAllPlaybackHttpClient: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(IPv4FirstDns())
            .eventListenerFactory(PlaybackConnectionEvents)
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier(playbackHostnameVerifier)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Primary OkHttpClient using standard system SSL certificates and full SNI support.
     * Includes an automatic fallback to [trustAllPlaybackHttpClient] if an [SSLException]
     * occurs on self-signed local media servers.
     */
    internal val playbackHttpClient: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(IPv4FirstDns())
            .eventListenerFactory(PlaybackConnectionEvents)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request()
                try {
                    chain.proceed(request)
                } catch (e: SSLException) {
                    // Fallback to trust-all only for servers on the local network (self-signed
                    // Jellyfin/WebDAV at home). For public hosts a failed certificate check is
                    // exactly what a network attacker produces: retrying without it would hand
                    // them the request, Authorization header included.
                    // Also allowed: bare-IP stream servers (common for scraped HLS, e.g.
                    // https://94.131.217.176/.../master.m3u8) that can't hold a valid
                    // certificate - but only for anonymous requests, so no credentials can
                    // ever be handed to an impostor this way.
                    val anonymousIpHost = isIpLiteralHost(request.url.host) &&
                        request.header("Authorization") == null
                    if (!isLocalNetworkHost(request.url.host) && !anonymousIpHost) throw e
                    trustAllPlaybackHttpClient.newCall(request).execute()
                }
            }
            .build()
    }

    /**
     * True for hosts that can only be on the user's own network: private/loopback/link-local
     * IP literals and single-label or .local/.lan/.home names. Decided from the URL alone,
     * never via DNS, which an attacker on the path could answer.
     */
    internal fun isIpLiteralHost(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        if (h.contains(':')) return true // IPv6 literal
        val parts = h.split('.')
        return parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }

    internal fun isLocalNetworkHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || !h.contains('.') && !h.contains(':')) return true
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home") || h.endsWith(".home.arpa")) return true
        val octets = h.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size == 4 && h.count { it == '.' } == 3) {
            val (a, b) = octets[0] to octets[1]
            return a == 10 || a == 127 || (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        if (h.contains(':')) {
            return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
        }
        return false
    }

    fun createHttpClient(
        defaultHeaders: Map<String, String> = emptyMap(),
        url: String? = null,
        context: Context? = null
    ): OkHttpClient {
        val builder = playbackHttpClient.newBuilder()
        val bypassNetwork = context?.let { networkForVpnBypass(it, url) }
        bypassNetwork?.let { network -> builder.socketFactory(network.socketFactory) }
        if (defaultHeaders.any { it.key.equals("Authorization", ignoreCase = true) }) {
            // OkHttp strips the Authorization header on cross-host redirects.
            // WebDAV servers behind reverse proxies commonly redirect to a
            // different host/port, causing auth to be lost. A network
            // interceptor ensures the header is always present on every
            // outgoing request — same behavior as mpv/curl.
            val authValue = defaultHeaders.entries
                .first { it.key.equals("Authorization", ignoreCase = true) }
                .value
            builder.addNetworkInterceptor { chain ->
                val request = chain.request()
                if (request.header("Authorization") == null) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Authorization", authValue)
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
        }
        val optimized = NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(builder)
        // A VPN-bypass client needs its own pool, set AFTER the optimizations (which install
        // the shared pool): OkHttp reuses pooled connections regardless of socket factory,
        // so with the shared pool a bypass request rides a connection that was opened
        // through the VPN - and vixsrc answers it with 403 in a few ms.
        if (bypassNetwork != null) optimized.connectionPool(okhttp3.ConnectionPool())
        return optimized.build()
    }

    @UnstableApi
    fun createHttpDataSourceFactory(
        defaultHeaders: Map<String, String> = emptyMap(),
        url: String? = null,
        context: Context? = null
    ): DataSource.Factory {
        val client = createHttpClient(defaultHeaders, url, context)
        val httpFactory = OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(defaultHeaders)
            if (defaultHeaders.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                setUserAgent(PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            }
        }
        return LoggingDataSourceFactory(httpFactory, "HTTP")
    }

    @UnstableApi
    fun createDataSourceFactory(
        context: android.content.Context,
        defaultHeaders: Map<String, String> = emptyMap(),
        url: String? = null
    ): DataSource.Factory {
        return DefaultDataSource.Factory(context, createHttpDataSourceFactory(defaultHeaders, url, context))
    }

    fun openConnection(
        url: String,
        headers: Map<String, String>,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        range: String? = null
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = method
            setRequestProperty("User-Agent", headers["User-Agent"] ?: PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            headers.forEach { (key, value) ->
                if (key.equals("Range", ignoreCase = true)) return@forEach
                if (key.equals("User-Agent", ignoreCase = true)) return@forEach
                setRequestProperty(key, value)
            }
            range?.let { setRequestProperty("Range", it) }
        }
    }
}
