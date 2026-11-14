package social.hotmess.android.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import social.hotmess.android.ui.screens.ArticleScreen
import social.hotmess.android.ui.screens.EventScreen
import social.hotmess.android.ui.screens.NowScreen
import social.hotmess.android.ui.screens.PublicationScreen
import social.hotmess.android.ui.screens.VenueScreen

/**
 * A venue's night: tonight's lineup and karaoke (signing up, requesting a song), the cover, and the news about it,
 * on the venue page, Now and an event; and an article and its publication.
 */
class VenueNightTests : ScreenTestCase() {
    private val me = "11111111-2222-4333-8444-555555555555"

    init {
        stub.stubVenueSideLoads()
        stub.error("VenuePhotos", "No photos in the stub")
        stub.error("EventPhotos", "No photos in the stub")
    }

    private fun entry(id: String, name: String, role: String, state: String, personId: String = "p-$id", song: String? = null) =
        """{"id":"$id","kind":"APPEARANCE","role":"$role","state":"$state","startTiming":"NONE",
        "person":{"id":"$personId","name":"$name","avatarUrl":null},"people":{"nodes":[{"id":"$personId","name":"$name","avatarUrl":null}]},
        "scenes":[],"afterScene":null${song?.let { ""","songRequest":$it""" } ?: ""}}"""

    private fun signup(id: String, title: String, mine: Boolean, position: Int, state: String = "QUEUED") =
        """{"id":"$id","kind":"KARAOKE","title":"$title","artist":"Britney Spears","singer":"Test P.","requesterName":"Test P.",
        "note":null,"mine":$mine,"state":"$state","summary":null,"position":$position}"""

    private val host = entry("e1", "Miss Coco", "HOST", "ON")

    private fun lineup(karaokeOpen: Boolean = true, songs: Boolean = true, karaoke: Boolean = true, singers: List<String> = emptyList(), mine: List<String> = emptyList()) =
        """{"venues":{"venue":{"id":"${Fixtures.VENUE_ID}","name":"Nyne","photoUrl":null,
        "people":{"runningOrder":{"nodes":[$host,${entry("e2", "Kiki Riot", "PERFORMER", "UPCOMING")}${singers.joinToString("") { ",$it" }}]},
        "onStage":{"id":"e1"},"upNext":{"id":"e2"}},
        "karaoke":null,
        "requests":{"karaoke":{"permitted":$karaoke,"open":$karaokeOpen,"paused":${!karaokeOpen},"closedReason":${if (karaokeOpen) "null" else "\"Paused for the show\""},
          "cutOff":false,"reopened":false,"host":$host,"mine":{"nodes":[${mine.joinToString(",")}]}},
         "song":{"permitted":$songs,"open":$songs,"paused":false,"closedReason":null,"cutOff":false,"reopened":false,"host":$host,
          "mine":{"nodes":[]},"queue":{"nodes":[]}}}}}}"""

    private fun venueWithCover(payable: Boolean, paid: Boolean = false): String {
        val night = java.time.LocalDate.now().toString()
        val admission = if (paid) """{"id":"ad1","status":"PAID","night":"$night","amount":{"cents":1000,"currency":"usd"},
            "fee":{"cents":112,"currency":"usd"},"total":{"cents":1112,"currency":"usd"},"passSecret":"c2VjcmV0c2VjcmV0c2VjcmV0",
            "venue":{"id":"${Fixtures.VENUE_ID}","name":"Nyne"},"kind":"COVER","quantity":1}""" else "null"
        return Fixtures.venueOverview.replace(
            """"photoUrl":null,"bannerUrl":null,"profile"""",
            """"photoUrl":null,"bannerUrl":null,"tickets":{"cover":{"charge":{"amount":{"cents":1000,"currency":"usd"},
            "total":{"cents":1112,"currency":"usd"},"fee":{"cents":112,"currency":"usd"},"night":"$night","from":"21:00",
            "payable":$payable,"event":null,"coatCheck":null},"viewerAdmission":$admission,"viewerCoatCheck":null}},"profile"""",
        )
    }

    private fun article(id: String = "a1", title: String = "Nyne turns ten") = """{"id":"$id","slug":"nyne-turns-ten","title":"$title",
        "summary":"A decade of Sprague Street nights.","byline":"Inlander Staff","kind":"ARTICLE","publishedAt":"${Fixtures.iso(-30)}",
        "edited":false,"pinned":false,"teaser":false,"canonicalUrl":null,"disclosure":null,"heroPhotoUrl":null,
        "publication":{"id":"pub1","name":"The Inlander","photoUrl":null,"trusted":true,"acceptsOpEds":false}}"""

    private fun showVenue() = show { VenueScreen(Fixtures.VENUE_ID, navigator) }

    /** Tonight's section on its own, for a signed-in viewer: its "+" signs up or requests (the venue page's, signed in). */
    private fun showTonight() = show {
        val model = social.hotmess.android.ui.screens.rememberTonightLineup(Fixtures.VENUE_ID)
        val lineup by model.lineup.collectAsState()
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
            lineup?.let { social.hotmess.android.ui.screens.TonightLineupSection(model, it, "Test P.") { id -> navigator.opened += "person:$id" } }
        }
    }

    // region Tonight's lineup and karaoke

    @Test fun theVenueShowsWhosOnTonight() {
        stub.data("Venue", Fixtures.venueOverview)
        stub.data("VenueLineup", lineup())
        showVenue()
        expectText("Miss Coco")
        expectText("Hosting now")
        expectText("Kiki Riot")
        expectText("Karaoke")
        expectText("Nobody's on the list yet. Be the first.")
    }

    @Test fun signingUpToSingSendsTheSong() {
        stub.data("Venue", Fixtures.venueOverview)
        stub.data("VenueLineup", lineup())
        stub.data("RequestSong", """{"requestSong":{"request":${signup("sr9", "Toxic", mine = true, position = 0)}}}""")
        showTonight()
        expectText("Kiki Riot")
        scrollTo("Sign up to sing")
        tap("Sign up to sing")
        expectText("Put me on the list")
        type("Song", "Toxic")
        type("Artist", "Britney Spears")
        tap("Put me on the list")
        awaitRequest("RequestSong")
        val sent = stub.last("RequestSong")!!.variables
        assertEquals("KARAOKE", sent.optString("kind"))
        assertEquals("Toxic", sent.optString("title"))
        assertEquals("Britney Spears", sent.optString("artist"))
        assertEquals(Fixtures.VENUE_ID, sent.optString("venueId"))
    }

    @Test fun mySignupSaysWhereIAmOnTheList() {
        stub.data("Venue", Fixtures.venueOverview)
        val mine = signup("sr1", "Toxic", mine = true, position = 0)
        stub.data("VenueLineup", lineup(singers = listOf(entry("e3", "Test P.", "SINGER", "UPCOMING", personId = me, song = mine)), mine = listOf(mine)))
        showVenue()
        expectText("Toxic")
        expectText("You're")
    }

    @Test fun pausedSignupsSayTheyreClosed() {
        stub.data("Venue", Fixtures.venueOverview)
        stub.data("VenueLineup", lineup(karaokeOpen = false, songs = false))
        showTonight()
        expectText("Kiki Riot")
        scrollTo("Signups closed")
        assertTrue(!compose.hasText("Sign up to sing"))
    }

    @Test fun requestingASongSendsIt() {
        stub.data("Venue", Fixtures.venueOverview)
        stub.data("VenueLineup", lineup(karaoke = false))
        stub.data("RequestSong", """{"requestSong":{"request":{"id":"sr8","kind":"SONG","title":"Padam Padam","artist":"Kylie","mine":true,"state":"QUEUED","position":0}}}""")
        showTonight()
        expectText("Kiki Riot")
        scrollTo("Request a song")
        tap("Request a song")
        expectText("Send request")
        type("Song", "Padam Padam")
        tap("Send request")
        awaitRequest("RequestSong")
        assertEquals("SONG", stub.last("RequestSong")!!.variables.optString("kind"))
        assertEquals("Padam Padam", stub.last("RequestSong")!!.variables.optString("title"))
    }

    @Test fun nowAtTheVenueShowsTonightsLineup() {
        stub.data("ReportLocation", Fixtures.nowAtVenue)
        stub.data("VenueLineup", lineup())
        stub.tolerate("VenueSafety", "VenuePairUp", "VenuePosts", "ArticlesAbout", "ClosestLocale")
        show(atVenue = true) { NowScreen(navigator) }
        expectText("Miss Coco")
        expectText("Kiki Riot")
    }

    // endregion

    // region Cover

    @Test fun aPayableCoverOffersToSkipTheLine() {
        stub.data("Venue", venueWithCover(payable = true))
        stub.tolerate("VenueLineup")
        showVenue()
        expectText("$11.12")
        assertTrue(screen(), compose.hasText("Pay") || compose.hasText("Skip the line"))
    }

    @Test fun aCoverPaidAtTheDoorSaysSo() {
        stub.data("Venue", venueWithCover(payable = false))
        stub.tolerate("VenueLineup")
        showVenue()
        expectText("$11.12")
        expectText("door")
    }

    @Test fun aPaidCoverShowsThePass() {
        stub.data("Venue", venueWithCover(payable = true, paid = true))
        stub.tolerate("VenueLineup")
        showVenue()
        expectText("pass")
    }

    // endregion

    // region News

    @Test fun theVenuesNewsListsItsArticles() {
        stub.data("Venue", Fixtures.venueOverview)
        stub.tolerate("VenueLineup")
        stub.data("ArticlesAbout", """{"venues":{"venue":{"articles":{"list":{"nodes":[${article()}]}}}}}""")
        showVenue()
        expectText("232 W Sprague")
        scrollTo("Nyne turns ten")
        expectText("Nyne turns ten")
        expectText("The Inlander")
    }

    @Test fun anArticleShowsItsBody() {
        stub.data("Article", """{"soapbox":{"article":${article().dropLast(1)},"body":"Ten years of **dancing**.","venues":[],"events":[],"people":[],"locales":[]}}}""")
        stub.tolerate("Publication", "ArticlesAbout")
        show { ArticleScreen("a1", navigator) }
        expectText("Nyne turns ten")
        expectText("Ten years of")
        expectText("The Inlander")
    }

    @Test fun aPublicationShowsWhatItsAbout() {
        stub.data(
            "Publication",
            """{"soapbox":{"publication":{"id":"pub1","name":"The Inlander","masthead":"The Inlander","about":"Spokane's alt weekly.",
            "websiteUrl":"https://inlander.com","photoUrl":null,"trusted":true,"acceptsOpEds":false}}}""",
        )
        stub.tolerate("MyOpEds")
        stub.data("Articles", """{"soapbox":{"articles":{"nodes":[${article(id = "a2", title = "Pride guide")}]}}}""")
        show { PublicationScreen("pub1", navigator) }
        expectText("The Inlander")
        expectText("Spokane's alt weekly.")
        expectText("Pride guide")
    }

    // endregion

}
