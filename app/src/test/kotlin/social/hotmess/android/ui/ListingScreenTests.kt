package social.hotmess.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import social.hotmess.android.ui.screens.PassesScreen
import social.hotmess.android.ui.screens.PeopleScreen
import social.hotmess.android.ui.screens.PersonScreen
import social.hotmess.android.ui.screens.PingSheet
import social.hotmess.android.ui.screens.PostScreen
import social.hotmess.android.ui.screens.TribePickerScreen
import social.hotmess.android.ui.screens.UpdatesScreen

/** People and a person's page, a post and its comments, the user's passes, tribes and sending a Ping. */
class ListingScreenTests : ScreenTestCase() {
    private val personId = "7d6c5b4a-3928-4170-8a6b-5c4d3e2f1a0b"
    private val postId = "8e7d6c5b-4a39-4281-9b7c-6d5e4f3a2b1c"

    private fun post(body: String = "Doors at 9, show at 10!", commentCount: Int = 1, commentsOpen: Boolean = true) =
        """{"id":"$postId","body":"$body","posterName":"Miss Coco","posterAvatarUrl":null,"roleLabel":null,
        "actingPersonName":null,"createdAt":"${Fixtures.iso(-2)}","edited":false,"pinned":false,
        "commentsOpen":$commentsOpen,"commentCount":$commentCount,"photos":[],"event":null,"person":{"id":"$personId"},
        "venue":null,"visibility":"PUBLIC""""

    // region People

    @Test fun peopleListsEveryoneTheAudienceFollows() {
        stub.data(
            "People",
            """{"people":{"list":{"nodes":[
            {"id":"$personId","name":"Miss Coco","role":"Host","order":1},
            {"id":"a1b2c3d4-e5f6-4071-8293-a4b5c6d7e8f9","name":"DJ Lux","role":"DJ","order":2}]}}}""",
        )
        show { PeopleScreen(navigator) }
        expectText("Miss Coco")
        expectText("DJ Lux")
        tap("Miss Coco")
        assertTrue(navigator.opened.toString(), navigator.opened.single().contains(personId))
    }

    @Test fun peopleWithNobodySaysSo() {
        stub.data("People", """{"people":{"list":{"nodes":[]}}}""")
        show(dark = true) { PeopleScreen(navigator) }
        expectText("No one here yet")
    }

    @Test fun aPersonShowsTheirMembersEventsAndPosts() {
        stub.stubVenueSideLoads()
        stub.error("PersonTitles", "No titles in the stub")
        stub.error("PersonPhotos", "No photos in the stub")
        stub.data(
            "Person",
            """{"people":{"person":{"id":"$personId","name":"House of Lux","role":"Troupe",
            "events":{"upcoming":{"nodes":[${Fixtures.event(name = "Lux Revue", startHours = 30)}]}},
            "members":[{"id":"m1","name":"Kiki Riot","avatarUrl":null}],"groups":[],"music":null}}}""",
        )
        stub.data("PersonPosts", """{"people":{"person":{"posts":{"list":{"nodes":[${post()}}]}}}}}""")
        show { PersonScreen(personId, navigator) }
        expectText("House of Lux")
        expectText("Members")
        expectText("Kiki Riot")
        expectText("Lux Revue")
        assertEquals(personId, stub.last("Person")?.variables?.optString("id"))
    }

    @Test fun aPersonTheApiDoesntHaveIsAnError() {
        stub.stubVenueSideLoads()
        stub.tolerate("PersonTitles", "PersonPosts", "PersonPhotos")
        stub.data("Person", """{"people":{"person":null}}""")
        show { PersonScreen(personId, navigator) }
        expectText("Something went wrong")
    }

    // endregion

    // region Posts

    @Test fun aPostShowsItsComments() {
        stub.data(
            "Post",
            """{"posts":{"post":${post()},"comments":{"nodes":[{"id":"c1","authorId":"u2","authorName":"Sam B.",
            "authorAvatarUrl":null,"body":"See you there!","createdAt":"${Fixtures.iso(-1)}","viewerCanRemove":false}]}}}}""",
        )
        show { PostScreen(postId, navigator) }
        expectText("Doors at 9, show at 10!")
        expectText("Miss Coco")
        expectText("See you there!")
        assertEquals(postId, stub.last("Post")?.variables?.optString("id"))
    }

    @Test fun aPostWithCommentsOffSaysSo() {
        stub.data("Post", """{"posts":{"post":${post(commentCount = 0, commentsOpen = false)},"comments":{"nodes":[]}}}}""")
        show { PostScreen(postId, navigator) }
        expectText("Comments are off on this post.")
    }

    @Test fun aRemovedPostSaysItsGone() {
        stub.data("Post", """{"posts":{"post":null}}""")
        show { PostScreen(postId, navigator) }
        expectText("This post isn't here any more.")
    }

    @Test fun updatesListsTheLocalesPosts() {
        stub.data("LocalePosts", """{"locale":{"posts":{"list":{"nodes":[${post(body = "Patio's open tonight")}}]}}}}""")
        show { UpdatesScreen(Fixtures.LOCALE_ID, "Spokane", navigator) }
        expectText("Updates in Spokane")
        expectText("Patio's open tonight")
        assertEquals(Fixtures.LOCALE_ID, stub.last("LocalePosts")?.variables?.optString("id"))
    }

    // endregion

    // region Passes, tribes and Pings

    @Test fun passesListsThePaidPasses() {
        stub.data(
            "Admissions",
            """{"me":{"cover":{"admissions":{"nodes":[{"id":"ad1","status":"PAID","night":"2026-11-14",
            "amount":{"cents":1000,"currency":"usd"},"fee":{"cents":112,"currency":"usd"},"total":{"cents":1112,"currency":"usd"},
            "venue":{"id":"${Fixtures.VENUE_ID}","name":"Nyne","photoUrl":null},"kind":"COVER","quantity":2}]}}}}""",
        )
        show { PassesScreen(navigator) }
        expectText("Your passes")
        expectText("Nyne")
        tap("Nyne")
        assertEquals(listOf("pass:ad1"), navigator.opened)
    }

    @Test fun withoutPassesItSaysHowToGetOne() {
        stub.data("Admissions", """{"me":{"cover":{"admissions":{"nodes":[]}}}}""")
        show { PassesScreen(navigator) }
        expectText("No passes yet")
    }

    @Test fun pickingTribesSavesThem() {
        stub.data(
            "Tribes",
            """{"tribeCap":2,"tribeCatalog":[{"key":"scene","name":"Scene","tribes":[{"id":"t1","name":"Bears"},
            {"id":"t2","name":"Drag fans"},{"id":"t3","name":"Leather"}]}],"me":{"self":{"tribes":[{"tag":{"id":"t1"}}]}}}""",
        )
        stub.data("SetTribes", """{"setTribes":{"tribes":[{"tag":{"id":"t1"}},{"tag":{"id":"t2"}}]}}""")
        show { TribePickerScreen(navigator) }
        expectText("Your tribes")
        expectText("drag-fans")
        expectText("Your picks")
        tap("drag-fans")
        tap("Done")
        awaitRequest("SetTribes")
        val ids = stub.last("SetTribes")!!.variables.getJSONArray("ids")
        assertEquals(setOf("t1", "t2"), (0 until ids.length()).map { ids.getString(it) }.toSet())
    }

    @Test fun sendingAPingSendsTheNote() {
        stub.data(
            "SendPing",
            """{"sendPing":{"ping":{"id":"pg1","note":"Who's out?","expiresAt":"${Fixtures.iso(6)}","targets":[],"circle":[]}}}""",
        )
        show { PingSheet(null, onDismiss = {}, onSent = {}) }
        expectText("Ping your friends")
        type("Add a note", "Who's out?")
        tap("Send ping")
        awaitRequest("SendPing")
        assertEquals("Who's out?", stub.last("SendPing")?.variables?.optString("note"))
    }

    // endregion

    // region Me

    @Test fun meOpensHelpAndSafetyAndTheUsersSuggestions() {
        stub.tolerate("Tribes", "CalendarFeed", "Admissions", "ChatInitials", "RecentChatPush", "PairUpPush", "AgeCheck", "ManagedListings")
        show { social.hotmess.android.ui.screens.MeScreen(navigator) }
        expectText("Help and safety")
        tap("Need support?")
        tap("Getting help at a venue")
        tap("Your safety alerts")
        tap("Your suggestions")
        assertEquals(listOf("support", "gettingHelp", "safetyAlerts", "suggestions"), navigator.opened)
    }

    // endregion
}
