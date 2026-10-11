package com.nuvio.tv.core.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.Collections
import java.util.WeakHashMap

/**
 * Every OkHttp connection pool the app keeps alive, so they can all be emptied when the
 * route changes underneath them.
 *
 * When the app-scoped VPN comes up (or goes down) the system re-routes this app's packets,
 * including those of connections opened before: a keep-alive connection made over Wi-Fi now
 * sends through the tunnel with its old source address and simply stops answering. OkHttp
 * keeps reusing it until the read timeout, so the catalogue, the details page and the images
 * hang for a minute or more after every VPN toggle. Closing the idle connections at that
 * moment makes the next request open a fresh one on the current route.
 */
object ConnectionPoolRegistry {
    private val pools: MutableSet<ConnectionPool> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<ConnectionPool, Boolean>()))

    /** Registers [client]'s pool and returns the client, for use at the end of a builder chain. */
    fun track(client: OkHttpClient): OkHttpClient {
        pools.add(client.connectionPool)
        return client
    }

    /** A fresh pool, already registered: OkHttpClient.Builder().connectionPool(newPool()). */
    fun newPool(): ConnectionPool = ConnectionPool().also(::track)

    fun track(pool: ConnectionPool) {
        pools.add(pool)
    }

    /** Closes the idle connections of every registered pool. Not for the main thread. */
    fun evictAll() {
        val snapshot = synchronized(pools) { pools.toList() }
        snapshot.forEach { pool -> runCatching { pool.evictAll() } }
    }
}
