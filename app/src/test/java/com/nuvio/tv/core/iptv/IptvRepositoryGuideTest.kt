package com.nuvio.tv.core.iptv

import com.nuvio.tv.data.local.IptvPreferencesDataStore
import com.nuvio.tv.domain.model.IptvPlaylist
import com.nuvio.tv.domain.model.IptvPlaylistType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IptvRepositoryGuideTest {

    private val requests = mutableListOf<Request>()

    // Answers every request locally, so the test never touches the network.
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            requests += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("<tv></tv>".toResponseBody("application/xml".toMediaType()))
                .build()
        }
        .build()

    private fun repository(playlist: IptvPlaylist): IptvRepository {
        val preferences = mockk<IptvPreferencesDataStore>(relaxed = true)
        coEvery { preferences.getPlaylists() } returns listOf(playlist)
        coEvery { preferences.getActivePlaylistId() } returns playlist.id
        every { preferences.worldSource } returns flowOf(false)
        every { preferences.epgUrl } returns flowOf("")
        return IptvRepository(client, preferences)
    }

    @Test
    fun `the guide request carries the playlist user agent`() = runTest {
        val playlist = IptvPlaylist(
            id = "a",
            name = "Lista A",
            type = IptvPlaylistType.M3U,
            url = "http://example.test/a.m3u",
            epgUrl = "http://example.test/guide.xml",
            userAgent = "VLC/3.0.20"
        )

        repository(playlist).getProgrammesByChannel()

        assertEquals("http://example.test/guide.xml", requests.single().url.toString())
        assertEquals("VLC/3.0.20", requests.single().header("User-Agent"))
    }

    @Test
    fun `the guide request sets no user agent when the playlist has none`() = runTest {
        val playlist = IptvPlaylist(
            id = "a",
            name = "Lista A",
            type = IptvPlaylistType.M3U,
            url = "http://example.test/a.m3u",
            epgUrl = "http://example.test/guide.xml"
        )

        repository(playlist).getProgrammesByChannel()

        assertNull(requests.single().header("User-Agent"))
    }
}
