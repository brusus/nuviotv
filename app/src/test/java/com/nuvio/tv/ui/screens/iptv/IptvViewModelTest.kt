package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.iptv.IptvRepository
import com.nuvio.tv.domain.model.IptvChannel
import com.nuvio.tv.domain.model.IptvPlaylist
import com.nuvio.tv.domain.model.IptvPlaylistType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IptvViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun channel(name: String, group: String = "Italia") = IptvChannel(
        id = name,
        name = name,
        logoUrl = null,
        groupTitle = group,
        streamUrl = "http://example.test/$name",
        epgId = null
    )

    private val playlistA = IptvPlaylist("a", "Lista A", IptvPlaylistType.M3U, "http://example.test/a.m3u")
    private val playlistB = IptvPlaylist("b", "Lista B", IptvPlaylistType.M3U, "http://example.test/b.m3u")

    private fun repository(): IptvRepository = mockk<IptvRepository>(relaxed = true).also { repo ->
        every { repo.worldSource } returns flowOf(false)
        every { repo.worldCountry } returns flowOf("it")
        coEvery { repo.getPlaylists() } returns listOf(playlistA, playlistB)
        coEvery { repo.sourceKeyPrefix() } returns "a|"
        coEvery { repo.getHiddenCategoryKeys() } returns emptySet()
        coEvery { repo.getFavoriteVodKeys() } returns emptySet()
        coEvery { repo.getFavoriteChannelUrls() } returns emptySet()
        coEvery { repo.getRecentChannelUrls() } returns emptyList()
        coEvery { repo.getLastChannelUrl() } returns null
        coEvery { repo.getProgrammesByChannel(any()) } returns emptyMap()
    }

    /**
     * NUVIO-001. Open the screen (playlist A starts loading, slowly), switch to playlist B
     * (loads at once), then A's answer arrives late: the list must stay B's.
     */
    @Test
    fun `a late answer for the previous playlist does not replace the current one`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = repository()
        val slowAnswerForA = CompletableDeferred<Result<List<IptvChannel>>>()
        var active = playlistA
        coEvery { repo.getActivePlaylist() } answers { active }
        coEvery { repo.setActivePlaylist(any()) } answers { active = playlistB }
        coEvery { repo.getChannels(any()) } coAnswers {
            if (active.id == "a") slowAnswerForA.await() else Result.success(listOf(channel("Canale B")))
        }

        val viewModel = IptvViewModel(repo)
        viewModel.load()
        advanceUntilIdle()

        viewModel.selectPlaylist("b")
        advanceUntilIdle()
        assertEquals(listOf("Canale B"), viewModel.uiState.value.channels.map { it.name })

        slowAnswerForA.complete(Result.success(listOf(channel("Canale A"))))
        advanceUntilIdle()

        assertEquals("b", viewModel.uiState.value.activePlaylist?.id)
        assertEquals(listOf("Canale B"), viewModel.uiState.value.channels.map { it.name })
        assertFalse(viewModel.uiState.value.isLoading)
    }

    /** NUVIO-003. A hidden group leaves the global search, like it leaves the All tab. */
    @Test
    fun `global search skips channels of hidden groups`() {
        val state = IptvUiState(
            isLoading = false,
            channels = listOf(channel("Rai 1", "Italia"), channel("Rai World", "Estero")),
            sourceKeyPrefix = "a|",
            hiddenCategoryKeys = setOf("a|live|Estero"),
            searchQuery = "rai"
        )
        assertEquals(listOf("Rai 1"), state.searchChannels.map { it.name })
        // Same rule the All tab already applies.
        assertEquals(listOf("Rai 1"), state.visibleChannels.map { it.name })
    }

    /** Editing saves over the same playlist, so what is tied to its id is kept. */
    @Test
    fun `editing a playlist keeps its id and reloads without the cache`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = repository()
        val account = IptvPlaylist("x", "Account", IptvPlaylistType.XTREAM, "http://example.test:8080", "user", "old")
        coEvery { repo.getPlaylists() } returns listOf(account)
        coEvery { repo.getActivePlaylist() } returns account
        coEvery { repo.getChannels(any()) } returns Result.success(emptyList())
        coEvery { repo.verifyXtreamAccount(any()) } returns true
        val saved = io.mockk.slot<IptvPlaylist>()
        coEvery { repo.addPlaylist(capture(saved)) } returns Unit

        val viewModel = IptvViewModel(repo)
        viewModel.load()
        advanceUntilIdle()

        viewModel.openEditPlaylist("x")
        assertEquals(account, viewModel.uiState.value.editingPlaylist)
        viewModel.addXtreamPlaylist("Account", "http://example.test:8080", "user", "new", "")
        advanceUntilIdle()

        assertEquals("x", saved.captured.id)
        assertEquals("new", saved.captured.password)
        assertEquals(null, viewModel.uiState.value.editingPlaylist)
        io.mockk.coVerify { repo.getChannels(true) }
    }
}
