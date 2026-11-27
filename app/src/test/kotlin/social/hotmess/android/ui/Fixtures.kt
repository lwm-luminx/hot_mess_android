package social.hotmess.android.ui

import java.time.Instant
import java.time.temporal.ChronoUnit

/** Inline GraphQL payloads the screen tests answer with (camelCase, as the documents select them). */
object Fixtures {
    const val VENUE_ID = "6f1c2c1e-4d2a-4f6b-9a37-0c1d2e3f4a5b"
    const val EVENT_ID = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
    const val LOCALE_ID = "4e5f6071-8293-4a41-8d5e-6f7a8b9c0d1e"

    fun iso(hoursFromNow: Long): String = Instant.now().plus(hoursFromNow, ChronoUnit.HOURS)
        .truncatedTo(ChronoUnit.SECONDS).toString()

    fun venue(name: String = "Nyne") = """{"id":"$VENUE_ID","name":"$name","distance":null,"point":"POINT (-117.41 47.65)",
        "photoUrl":null,"bannerUrl":null,"profile":{"address":"232 W Sprague","phone":null,"facebook":{"facebookId":null,"isLiked":false}}}"""

    fun event(id: String = EVENT_ID, name: String = "Bingo Night", startHours: Long = 2, rsvp: String = "null") =
        """{"id":"$id","name":"$name","startAt":"${iso(startHours)}","endAt":"${iso(startHours + 4)}","facebookId":null,
        "bannerUrl":null,"isFeatured":false,"rsvp":{"mine":$rsvp},"venue":{"name":"Nyne","venue":${venue()}}}"""

    val chat = """"chat":{"open":true,"recentMessages":{"nodes":[{"id":"1b2c3d4e-5f60-4718-9a2b-3c4d5e6f7a8b",
        "message":"who's here?","name":"Sam","userId":"2c3d4e5f-6071-4829-8b3c-4d5e6f7a8b9c","avatarUrl":null,
        "sentAt":"${iso(0)}"}]}}"""

    val friends = """"friends":{"list":{"nodes":[{"id":"3d4e5f60-7182-4930-9c4d-5e6f7a8b9c0d","name":"Alex Friend","facebookId":"4242"}]}}"""

    val nowAtVenue get() = """{"reportLocation":{"nearbyVenues":null,"venue":{"id":"$VENUE_ID","name":"Nyne","bannerUrl":null,
        "events":{"upcoming":{"nodes":[]}},$chat,$friends},"locale":{"id":"$LOCALE_ID","name":"Spokane"}}}"""

    val nowAtEvent get() = """{"reportLocation":{"nearbyVenues":null,"event":${event(startHours = -1)},
        "venue":{"id":"$VENUE_ID","name":"Nyne","bannerUrl":null,"events":{"upcoming":{"nodes":[${event(startHours = -1)}]}}},
        "locale":{"id":"$LOCALE_ID","name":"Spokane"}}}"""

    val nowNearby get() = """{"reportLocation":{"venue":null,"nearbyVenues":[${venue("The Stud")}],
        "locale":{"id":"$LOCALE_ID","name":"Spokane","events":{"upcoming":{"nodes":[${event()}]}}}}}"""

    val venueOverview get() = """{"venues":{"venue":{"id":"$VENUE_ID","name":"Nyne","distance":null,"point":"POINT (-117.41 47.65)",
        "photoUrl":null,"bannerUrl":null,"profile":{"address":"232 W Sprague","phone":null,"facebook":{"facebookId":null,"isLiked":false},
        "socialLinks":{"nodes":[]}},"events":{"upcoming":{"nodes":[${event(startHours = 26)}]}},$chat,$friends}}}"""

    fun eventDetail(rsvp: String = "null") = """{"events":{"event":${event(rsvp = rsvp).trim().dropLast(1)},
        "people":{"hosts":{"nodes":[{"id":"7d6c5b4a-3928-4170-8a6b-5c4d3e2f1a0b","name":"Miss Coco","avatarUrl":null,"bannerUrl":null}]},
        "organizers":{"nodes":[]},"lineup":{"nodes":[]}}}}}"""

    const val NO_PING = """{"me":{"ping":{"myPing":null}}}"""
    const val NO_FRIEND_PINGS = """{"me":{"ping":{"friendPings":[]}}}"""
    const val SAFETY = """{"me":{"termsAcceptedAt":"2026-01-01T00:00:00Z","blockedUsers":[],"canPretendLocation":false,"self":{"hasFacebook":true}}}"""
    const val ME = """{"me":{"self":{"id":"11111111-2222-4333-8444-555555555555","name":"Test Person","firstName":"Test","lastName":"Person","photo":null}}}"""

    val venues get() = """{"venues":{"list":{"nodes":[
        {"id":"$VENUE_ID","name":"Nyne","hidden":false,"order":1,"photoUrl":null,"bannerUrl":null,"locale":{"id":"$LOCALE_ID","name":"Spokane","label":"spokane"}},
        {"id":"2b3c4d5e-6f70-4182-9a3b-4c5d6e7f8091","name":"The Globe","hidden":false,"order":2,"photoUrl":null,"bannerUrl":null,"locale":{"id":"$LOCALE_ID","name":"Spokane","label":"spokane"}}]}}}"""

    /** [venues] as one page of the paged Venues list (VenuesPage), the last. */
    val venuesPage get() = venues.replace("]}}}", "],\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":\"MQ\"}}}}")

    const val CONTACT_MATCHING_OFF =
        """{"me":{"contactMatching":{"enabled":false,"phoneVerified":false,"contactsChecked":0,"textCodesAvailable":false,"matches":[],"incomingRequests":[]}}}"""

    /** Matching off and a code can be texted: Now offers the card. */
    const val CONTACT_MATCHING_OFFERED =
        """{"me":{"contactMatching":{"enabled":false,"phoneVerified":false,"contactsChecked":0,"textCodesAvailable":true,"matches":[],"incomingRequests":[
        {"id":"r-1","status":"PENDING","createdAt":null,"user":{"id":"7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","name":"Jordan P.","avatarUrl":null},"friend":{"id":"11111111-2222-4333-8444-555555555555","name":"Test Person","avatarUrl":null}}]}}}"""

    const val SIGN_IN_METHODS = """{"methods":["facebook","apple","google","review_code"],"warnings":{}}"""
}

/** What every signed-in screen asks for on the side: the user's Pings, the card order (built-in on error), safety, me. */
fun StubApi.stubBasics() {
    data("MyPing", Fixtures.NO_PING)
    data("FriendPings", Fixtures.NO_FRIEND_PINGS)
    error("Layout", "No layout in the stub")
    data("Safety", Fixtures.SAFETY)
    data("Me", Fixtures.ME)
    // Friends from contacts: texting isn't set up in the stub, so Now and Me show nothing unless a test says otherwise.
    data("ContactMatching", Fixtures.CONTACT_MATCHING_OFF)
}

/** A venue page's side loads, each answered as not there: no pair-up, no safety alerts, no lineup, no posts. */
fun StubApi.stubVenueSideLoads() {
    data("VenuePairUp", """{"venues":{"venue":{"pairUp":null}}}""")
    error("VenueSafety", "No safety alerts in the stub")
    error("VenueLineup", "No lineup in the stub")
    error("VenueSuggestionState", "No suggestions in the stub")
    error("ArticlesAbout", "No articles in the stub")
    error("VenuePosts", "No posts in the stub")
    error("EventPosts", "No posts in the stub")
}
