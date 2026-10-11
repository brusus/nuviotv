package com.nuvio.tv.core.iptv

import com.nuvio.tv.domain.model.IptvPlaylist
import com.nuvio.tv.domain.model.IptvPlaylistType
import com.nuvio.tv.domain.model.IptvVodCategory
import com.nuvio.tv.domain.model.IptvVodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamApiTest {

    private val playlist = IptvPlaylist(
        id = "p1",
        name = "Test",
        type = IptvPlaylistType.XTREAM,
        url = "example.com:8080/player_api.php?username=x",
        username = "user name",
        password = "p@ss/1"
    )

    @Test
    fun `base url is the server root whatever the user typed`() {
        assertEquals("http://example.com:8080", XtreamApi.baseUrl("example.com:8080/"))
        assertEquals("https://tv.example.com", XtreamApi.baseUrl(" https://tv.example.com/get.php?username=a&password=b "))
        assertEquals("http://example.com:8080", XtreamApi.baseUrl("http://example.com:8080/player_api.php"))
    }

    @Test
    fun `credentials are encoded in api and stream urls`() {
        assertEquals(
            "http://example.com:8080/player_api.php?username=user%20name&password=p%40ss%2F1&action=get_series_info&series_id=7",
            XtreamApi.apiUrl(playlist, "get_series_info", mapOf("series_id" to "7"))
        )
        assertEquals(
            "http://example.com:8080/live/user%20name/p%40ss%2F1/42.ts",
            XtreamApi.liveUrl(playlist, "42")
        )
    }

    @Test
    fun `an active account is accepted and a refused one is not`() {
        val active = """{"user_info":{"auth":1,"status":"Active","allowed_output_formats":["m3u8","ts"]},"server_info":{}}"""
        assertEquals("ts", XtreamApi.parseAccount(active)?.liveExtension)

        val hlsOnly = """{"user_info":{"auth":1,"status":"Active","allowed_output_formats":["m3u8"]}}"""
        assertEquals("m3u8", XtreamApi.parseAccount(hlsOnly)?.liveExtension)

        assertNull(XtreamApi.parseAccount("""{"user_info":{"auth":0}}"""))
        assertNull(XtreamApi.parseAccount("""{"user_info":{"auth":1,"status":"Expired"}}"""))
        assertNull(XtreamApi.parseAccount("<html>not json</html>"))
    }

    @Test
    fun `live streams become channels grouped by category name`() {
        val categories = listOf(IptvVodCategory("3", "Notizie"))
        val json = """[
            {"num":1,"name":" LA7 HD ","stream_id":101,"stream_icon":"http://img/la7.png","epg_channel_id":"la7.it","category_id":"3"},
            {"num":2,"name":"Senza categoria","stream_id":"102","stream_icon":"","epg_channel_id":null,"category_id":"9"},
            {"num":3,"stream_id":103},
            "garbage"
        ]"""
        val channels = XtreamApi.parseLiveStreams(json, playlist, categories, "ts")
        assertEquals(2, channels.size)
        assertEquals("LA7 HD", channels[0].name)
        assertEquals("Notizie", channels[0].groupTitle)
        assertEquals("la7.it", channels[0].epgId)
        assertEquals("http://example.com:8080/live/user%20name/p%40ss%2F1/101.ts", channels[0].streamUrl)
        assertEquals("", channels[1].groupTitle)
        assertNull(channels[1].logoUrl)
        assertNull(channels[1].epgId)
    }

    /**
     * NUVIO-002. Some panels send "episodes" as a list of seasons instead of a map keyed by
     * season number, with no "season" field on the episodes: the position is the season.
     */
    @Test
    fun `episodes sent as a list of seasons keep their season numbers`() {
        val json = """{"episodes":[
            [{"id":"1","episode_num":1,"title":"A","container_extension":"mp4"}],
            [{"id":"2","episode_num":1,"title":"B","container_extension":"mp4"},
             {"id":"3","episode_num":2,"title":"C","container_extension":"mp4"}]
        ]}"""
        val episodes = XtreamApi.parseSeriesEpisodes(json)
        assertEquals(listOf(1, 2, 2), episodes.map { it.season })
        assertEquals(listOf("1", "2", "3"), episodes.map { it.id })
    }

    @Test
    fun `movies keep their extension and added date`() {
        val json = """[{"name":"Film Uno","stream_id":5,"stream_icon":"https://img/1.jpg","rating":"7.5","added":"1713920000","category_id":"12","container_extension":"mkv"}]"""
        val movie = XtreamApi.parseMovies(json).single()
        assertEquals(IptvVodKind.MOVIE, movie.kind)
        assertEquals(1713920000L, movie.addedEpochSeconds)
        assertEquals("http://example.com:8080/movie/user%20name/p%40ss%2F1/5.mkv", XtreamApi.movieUrl(playlist, movie))
    }

    @Test
    fun `series episodes are read from the season map in order`() {
        val json = """{"seasons":[],"info":{},"episodes":{
            "2":[{"id":"900","episode_num":1,"title":"S2E1","container_extension":"mp4","season":2}],
            "1":[{"id":"801","episode_num":"2","title":"S1E2","container_extension":"mkv"},
                 {"id":800,"episode_num":1,"title":"S1E1","container_extension":"mkv"}]
        }}"""
        val episodes = XtreamApi.parseSeriesEpisodes(json)
        assertEquals(listOf("800", "801", "900"), episodes.map { it.id })
        assertEquals(1, episodes[0].season)
        assertEquals(2, episodes[2].season)
        assertEquals("http://example.com:8080/series/user%20name/p%40ss%2F1/800.mkv", XtreamApi.episodeUrl(playlist, episodes[0]))
        assertNotNull(XtreamApi.parseSeries("""[{"series_id":1,"name":"Serie","cover":"http://c.jpg","category_id":"4","last_modified":"1700000000"}]""").singleOrNull())
    }
}
