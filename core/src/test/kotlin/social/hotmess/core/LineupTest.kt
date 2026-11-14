package social.hotmess.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LineupTest {
    private fun lineup(venue: String): VenueLineup =
        HotMessApi.json.decodeFromString(TonightLineupResponse.serializer(), """{"venues":{"venue":$venue}}""").lineup!!

    private val karaokeNight = """
        {"id":"v-1","name":"Club Try-Angles",
         "people":{"runningOrder":{"nodes":[
           {"id":"h-1","kind":"ACT","role":"HOST","state":"ON","people":{"nodes":[{"id":"p-1","name":"Aurora"}]}},
           {"id":"s-1","kind":"ACT","role":"SINGER","state":"DONE","songRequest":{"id":"r-1","kind":"KARAOKE","title":"Waterloo","singer":"Ada","state":"DONE"}},
           {"id":"s-2","kind":"ACT","role":"SINGER","state":"ON","songRequest":{"id":"r-2","kind":"KARAOKE","title":"Believe","singer":"Bo","state":"UP"}},
           {"id":"i-1","kind":"INTERMISSION","title":"Break","state":"UPCOMING"},
           {"id":"s-3","kind":"ACT","role":"SINGER","state":"UPCOMING","songRequest":{"id":"r-3","kind":"KARAOKE","title":"Toxic","singer":"Me","state":"QUEUED","mine":true}}
         ]},"onStage":{"id":"s-2"},"upNext":{"id":"i-1"}},
         "karaoke":{"provider":"audiencekit","tonight":true},
         "requests":{
           "karaoke":{"permitted":true,"open":true,"paused":false,"cutOff":false,"host":{"id":"h-1","role":"HOST","state":"ON","people":{"nodes":[{"id":"p-1","name":"Aurora"}]}},
                      "mine":{"nodes":[{"id":"r-3","kind":"KARAOKE","title":"Toxic","singer":"Me","state":"QUEUED","mine":true}]}},
           "song":{"permitted":true,"open":false,"paused":true,"closedReason":"Requests are paused",
                   "mine":{"nodes":[{"id":"q-1","kind":"SONG","title":"Vogue","artist":"Madonna","state":"QUEUED","mine":true}]},
                   "queue":{"nodes":[{"id":"q-0","kind":"SONG","title":"Hung Up","state":"UP"},{"id":"q-1","kind":"SONG","title":"Vogue","state":"QUEUED"}]}}}}
    """.trimIndent()

    @Test
    fun readsTonightsLineupKaraokeAndSongRequests() {
        val night = lineup(karaokeNight)
        assertEquals("Club Try-Angles", night.venueName)
        assertEquals(listOf("h-1", "i-1"), night.acts.map { it.id })
        assertEquals(listOf("s-1", "s-2", "s-3"), night.singers.map { it.id })
        assertEquals("Aurora", night.hostOnNow?.billing)
        assertEquals("Bo", night.singers[1].billing, "a singer is billed by the name they gave")
        assertTrue(night.onlyHosts.not())
        assertEquals(listOf("s-1", "s-2", "i-1", "s-3"), night.karaokeEntries.map { it.id })

        val mine = night.featuredSignup!!
        assertEquals("r-3", mine.id)
        assertEquals(3, night.singerNumber(night.entryFor(mine)!!))
        assertEquals(1, night.singersBefore(mine), "the one on stage; the one who sang doesn't count")
        assertTrue(night.takesKaraokeSignups)

        val songs = night.songRequests!!
        assertTrue(songs.closed)
        assertEquals("q-0", songs.playing?.id)
        assertEquals(1, songs.requestsBefore(songs.queue[1]))
        assertEquals("Vogue · Madonna", night.mySongRequests.single().songLine)
    }

    @Test
    fun aPartnerSessionTonightTakesNoSignupsHere() {
        val night = lineup(
            """{"id":"v-2","name":"The Sun","people":{"runningOrder":{"nodes":[]}},
               "karaoke":{"provider":"karafun","providerName":"KaraFun","joinUrl":"https://kf.example/abc","sessionCode":"123456","tonight":true},
               "requests":{"karaoke":{"permitted":true,"open":true}}}""",
        )
        assertTrue(night.karaokeSession!!.isPartnerSessionTonight)
        assertFalse(night.takesKaraokeSignups)
        assertFalse(night.isEmpty)
        assertNull(night.songRequests)
    }

    @Test
    fun unknownStatesAndKindsStillRead() {
        val night = lineup(
            """{"id":"v-3","people":{"runningOrder":{"nodes":[{"id":"x","role":"SINGER","state":"ON",
               "songRequest":{"id":"r","kind":"BALLAD","title":"Hello","state":"ECHOING"}}]}}}""",
        )
        val request = night.singers.single().songRequest!!
        assertEquals(SongRequestKind.UNKNOWN, request.kind)
        assertEquals(SongRequestState.UNKNOWN, request.state)
        assertFalse(request.state.isOpen)
    }

    @Test
    fun documentsAskForWhatTheyRead() {
        assertTrue(Lineups.VENUE_LINEUP.contains("songRequest { ${Lineups.SONG_REQUEST_FIELDS} }"))
        assertTrue(Lineups.VENUE_LINEUP.contains("karaoke { provider providerName joinUrl sessionCode tonight }"))
        assertTrue(Lineups.REQUEST_SONG.contains("requestSong(input:"))
        assertTrue(Lineups.WITHDRAW_SONG_REQUEST.contains("withdrawSongRequest(input: { id: \$id })"))
        assertEquals("Ada, Bo and Cy", Lineups.andList(listOf("Ada", "Bo", "Cy")))
    }
}
