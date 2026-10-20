package social.hotmess.core

import com.audiencekit.Queries

/**
 * The GraphQL documents the app runs against the audience's endpoint. Fields decode straight into
 * the models in Models.kt; ask for a field there before using it here.
 */
object Documents {
    /**
     * How many of the locale's upcoming events Now lists away from a venue. A string, so the document that
     * interpolates it stays a constant the schema check can validate.
     */
    const val LOCALE_EVENT_FIRST: String = "5"

    /** [LOCALE_EVENT_FIRST] as a number, for trimming the list. */
    val LOCALE_EVENT_LIMIT: Int = LOCALE_EVENT_FIRST.toInt()

    /**
     * A venue, with its address, phone and Facebook Page under its profile mixin. `bannerUrl` is its wide image
     * (its first banner, else its own photo, else its Page's cover) and `photoUrl` its own photo; both null when
     * there's none.
     */
    const val VENUE_FIELDS: String =
        "id name description distance point photoUrl bannerUrl profile { address phone facebook { facebookId isLiked link } }"

    /** A chat line as a preview shows it: rich messages in words (`message`), with the sender's role and presence. */
    const val CHAT_LINE_FIELDS: String = "id message name userId avatarUrl sentAt presence role kind endsAt postedAsVenue postedAsPerson"

    /**
     * A room's last few chat lines, oldest first, in the venue's or locale's chat mixin (`chat { ... }`); empty unless
     * the viewer is in the room or an admin.
     */
    const val RECENT_MESSAGES: String = "recentMessages(limit: 3) { nodes { $CHAT_LINE_FIELDS } }"

    /** Tonight's cover at a venue and the user's pass for it, in the cover mixin inside the venue's tickets mixin. */
    const val VENUE_COVER_FIELDS: String =
        "tickets { cover { charge { ${Queries.COVER_CHARGE_FIELDS} } viewerAdmission { ${Queries.ADMISSION_FIELDS} } } }"

    /** A person, with their Facebook Page under their profile mixin. */
    const val PERSON_FIELDS: String = "id name avatarUrl bannerUrl profile { facebook { facebookId isLiked link } }"

    /** A venue's or person's social links, a connection in the profile mixin (merged with the fields' own `profile`). */
    const val PROFILE_SOCIAL_LINKS: String = "profile { socialLinks { nodes { id handle provider url } } }"

    /**
     * An event, with the signed-in user's answer under its rsvp mixin (`rsvp { mine }`) and its venue under the
     * event's venue mixin (`venue { venue }`).
     */
    const val EVENT_FIELDS: String =
        "id name startAt endAt facebookId bannerUrl isFeatured rsvp { mine } venue { venue { $VENUE_FIELDS } }"

    /** Who's on at a venue right now, in its people mixin. */
    const val ON_NOW_FIELDS: String = "people { onNow { nodes { id role startsAt endsAt person { id name avatarUrl } } } }"

    const val FRIEND_FIELDS: String = "id name facebookId avatarUrl"

    /** Friends out now, with whether they can be reached (in chat, or by push). */
    const val FRIEND_OUT_FIELDS: String = "$FRIEND_FIELDS presence"

    /** A Ping with its picks and who's in. Picks carry enough of the venue or event for a row. */
    const val PING_FIELDS: String =
        "id note createdAt expiresAt isMine joined reach user { $FRIEND_FIELDS } via { $FRIEND_FIELDS } " +
            "targets { id venue { id name photoUrl } event { id name startAt venue { venue { id name } } } " +
            "joins { id targetId user { $FRIEND_FIELDS } } } " +
            "joins { id targetId user { $FRIEND_FIELDS } }"

    val REPORT_LOCATION: String = """
        mutation ReportLocation(${'$'}position: CoordinatesInput!) {
          reportLocation(input: { position: ${'$'}position }) {
            event { $EVENT_FIELDS }
            venue {
              $VENUE_FIELDS chat { $RECENT_MESSAGES } $VENUE_COVER_FIELDS
              $ON_NOW_FIELDS
              events { upcoming { nodes { $EVENT_FIELDS } } }
              friends { list { nodes { $FRIEND_OUT_FIELDS } } }
            }
            nearbyVenues { $VENUE_FIELDS }
            locale {
              id name bannerUrl chat { open $RECENT_MESSAGES }
              events { upcoming(first: $LOCALE_EVENT_FIRST) { nodes { $EVENT_FIELDS } } }
              friends {
                list { nodes { $FRIEND_OUT_FIELDS } }
                venues { nodes { venue { $VENUE_FIELDS } friendCount friends { $FRIEND_OUT_FIELDS } } }
              }
            }
            audience { me { ping { myPing { $PING_FIELDS } friendPings { $PING_FIELDS } } } }
          }
        }
    """.trimIndent()

    /** The signed-in user, with their picture (the SDK's Me doesn't ask for it). */
    val ME: String = """
        query Me {
          me { self { id name firstName lastName avatarUrl signedInWithGoogle } }
        }
    """.trimIndent()

    /**
     * A link to one of the audience's venues, events or people that opens it for a month, then says it has expired;
     * null when the path names nothing it shares.
     */
    val SHARE_LINK: String = """
        query ShareLink(${'$'}path: String!) {
          shareLink(path: ${'$'}path, lifetime: MONTH) { url expiresAt }
        }
    """.trimIndent()

    /** Whether the signed-in user gets pushes about rooms they left in the last 30 minutes. Off until they turn it on. */
    val RECENT_CHAT_PUSH: String = """
        query RecentChatPush {
          me { recentChatPush }
        }
    """.trimIndent()

    val SET_RECENT_CHAT_PUSH: String = """
        mutation SetRecentChatPush(${'$'}enabled: Boolean!) {
          setRecentChatPush(input: { enabled: ${'$'}enabled }) { recentChatPush }
        }
    """.trimIndent()

    val CLOSEST_LOCALE: String = """
        query ClosestLocale(${'$'}near: CoordinatesInput!) {
          closestLocale(near: ${'$'}near) { id name label }
        }
    """.trimIndent()

    val VENUES: String = """
        query Venues {
          venues { list { nodes { $VENUE_FIELDS hidden order locale { id name label } } } }
        }
    """.trimIndent()

    val VENUE: String = """
        query Venue(${'$'}id: ID!) {
          venues {
            venue(id: ${'$'}id) {
              $VENUE_FIELDS
              chat { open $RECENT_MESSAGES }
              tickets { cover { charge { ${Queries.COVER_CHARGE_FIELDS} } viewerAdmission { ${Queries.ADMISSION_FIELDS} } } }
              $ON_NOW_FIELDS
              events { upcoming { nodes { $EVENT_FIELDS } } }
              $PROFILE_SOCIAL_LINKS
              friends { list { nodes { $FRIEND_OUT_FIELDS } } }
              ping { friendPings { $PING_FIELDS } }
            }
          }
        }
    """.trimIndent()

    val PEOPLE: String = """
        query People {
          people { list { nodes { $PERSON_FIELDS order } } }
        }
    """.trimIndent()

    val PERSON: String = """
        query Person(${'$'}id: ID!) {
          people {
            person(id: ${'$'}id) {
              $PERSON_FIELDS
              events { upcoming { nodes { $EVENT_FIELDS } } }
              $PROFILE_SOCIAL_LINKS
              members { id name avatarUrl }
              groups { id name avatarUrl }
              music { tracks { nodes { id title provider providerUrl waveformUrl artworkUrl } } }
            }
          }
        }
    """.trimIndent()

    val LOCALE_EVENTS: String = """
        query LocaleEvents(${'$'}id: ID!) {
          locale(id: ${'$'}id) { events { upcoming { nodes { $EVENT_FIELDS } } } }
        }
    """.trimIndent()

    val EVENT: String = """
        query Event(${'$'}id: ID!) {
          events {
            event(id: ${'$'}id) {
              $EVENT_FIELDS
              tickets { cover { charge { ${Queries.COVER_CHARGE_FIELDS} } } }
              venue { venue { id $VENUE_COVER_FIELDS } }
              people { lineup { nodes { $PERSON_FIELDS } } }
              ping { friendPings { $PING_FIELDS } }
            }
          }
        }
    """.trimIndent()

    val MY_PING: String = """
        query MyPing {
          me { ping { myPing { $PING_FIELDS } } }
        }
    """.trimIndent()

    val FRIEND_PINGS: String = """
        query FriendPings {
          me { ping { friendPings { $PING_FIELDS } } }
        }
    """.trimIndent()

    val SEND_PING: String = """
        mutation SendPing(${'$'}venueIds: [ID!], ${'$'}eventIds: [ID!], ${'$'}note: String, ${'$'}localeId: ID, ${'$'}reach: PingReach) {
          sendPing(input: { venueIds: ${'$'}venueIds, eventIds: ${'$'}eventIds, note: ${'$'}note, localeId: ${'$'}localeId, reach: ${'$'}reach }) {
            ping { $PING_FIELDS }
          }
        }
    """.trimIndent()

    val JOIN_PING: String = """
        mutation JoinPing(${'$'}pingId: ID!, ${'$'}targetId: ID) {
          joinPing(input: { pingId: ${'$'}pingId, targetId: ${'$'}targetId }) { ping { $PING_FIELDS } }
        }
    """.trimIndent()

    val LEAVE_PING: String = """
        mutation LeavePing(${'$'}pingId: ID!) {
          leavePing(input: { pingId: ${'$'}pingId }) { ping { $PING_FIELDS } }
        }
    """.trimIndent()

    val END_PING: String = """
        mutation EndPing {
          endPing(input: {}) { ended }
        }
    """.trimIndent()

    private const val PAIR_UP_MATCH_FIELDS =
        "id accepted partnerAccepted bonus metAt endReason topic { key text } partner { id name avatarUrl lookFor }"

    private const val PAIR_UP_STATE_FIELDS =
        "status lookFor metCount match { $PAIR_UP_MATCH_FIELDS } bonus { $PAIR_UP_MATCH_FIELDS }"

    val VENUE_PAIR_UP: String = """
        query VenuePairUp(${'$'}id: ID!) {
          venues { venue(id: ${'$'}id) { pairUp { here mine { $PAIR_UP_STATE_FIELDS } } } }
        }
    """.trimIndent()

    val JOIN_PAIR_UP: String = """
        mutation JoinPairUp(${'$'}venueId: ID!, ${'$'}lookFor: String) {
          joinPairUp(input: { venueId: ${'$'}venueId, lookFor: ${'$'}lookFor }) { pairUp { $PAIR_UP_STATE_FIELDS } }
        }
    """.trimIndent()

    val ACCEPT_PAIR_UP: String = """
        mutation AcceptPairUp(${'$'}matchId: ID!) {
          acceptPairUp(input: { matchId: ${'$'}matchId }) { pairUp { $PAIR_UP_STATE_FIELDS } }
        }
    """.trimIndent()

    val SKIP_PAIR_UP: String = """
        mutation SkipPairUp(${'$'}matchId: ID!, ${'$'}what: PairUpSkip!) {
          skipPairUp(input: { matchId: ${'$'}matchId, what: ${'$'}what }) { pairUp { $PAIR_UP_STATE_FIELDS } }
        }
    """.trimIndent()

    val CANCEL_PAIR_UP: String = """
        mutation CancelPairUp(${'$'}matchId: ID!) {
          cancelPairUp(input: { matchId: ${'$'}matchId }) { pairUp { $PAIR_UP_STATE_FIELDS } }
        }
    """.trimIndent()

    val LEAVE_PAIR_UP: String = """
        mutation LeavePairUp(${'$'}venueId: ID!) {
          leavePairUp(input: { venueId: ${'$'}venueId }) { pairUp { $PAIR_UP_STATE_FIELDS } }
        }
    """.trimIndent()

    val SHOW_PAIR_UP_CODE: String = """
        mutation ShowPairUpCode(${'$'}matchId: ID!) {
          showPairUpCode(input: { matchId: ${'$'}matchId }) { code }
        }
    """.trimIndent()

    val SCAN_PAIR_UP_CODE: String = """
        mutation ScanPairUpCode(${'$'}venueId: ID!, ${'$'}code: String!) {
          scanPairUpCode(input: { venueId: ${'$'}venueId, code: ${'$'}code }) {
            outcome match { $PAIR_UP_MATCH_FIELDS } pairUp { $PAIR_UP_STATE_FIELDS }
          }
        }
    """.trimIndent()

    /** What the signed-in user has said about their own safety: when they agreed to the terms, and who they block. */
    val SAFETY: String = """
        query Safety {
          me { termsAcceptedAt blockedUsers { id name avatarUrl } }
        }
    """.trimIndent()

    val ACCEPT_TERMS: String = """
        mutation AcceptTerms {
          acceptTerms(input: {}) { termsAcceptedAt }
        }
    """.trimIndent()

    val BLOCK_USER: String = """
        mutation BlockUser(${'$'}userId: ID!) {
          blockUser(input: { userId: ${'$'}userId }) { blockedUsers { id name avatarUrl } }
        }
    """.trimIndent()

    val UNBLOCK_USER: String = """
        mutation UnblockUser(${'$'}userId: ID!) {
          unblockUser(input: { userId: ${'$'}userId }) { blockedUsers { id name avatarUrl } }
        }
    """.trimIndent()

    /** Files a report with the audience's admins, and blocks the person behind it when [block] is true and there is one. */
    val REPORT_CONTENT: String = """
        mutation ReportContent(${'$'}targetType: ReportTarget!, ${'$'}targetId: ID!, ${'$'}reason: String, ${'$'}block: Boolean) {
          reportContent(input: { targetType: ${'$'}targetType, targetId: ${'$'}targetId, reason: ${'$'}reason, block: ${'$'}block }) { reported blocked }
        }
    """.trimIndent()

    /** Deletes the signed-in user's account and everything recorded about them. It can't be undone. */
    val DELETE_ACCOUNT: String = """
        mutation DeleteAccount {
          deleteAccount(input: { confirm: true }) { deleted }
        }
    """.trimIndent()

    /** The SDK's registerDevice, plus which app the token is for; Android builds are never sandboxed. */
    val REGISTER_DEVICE: String = """
        mutation RegisterDevice(${'$'}notificationToken: String!, ${'$'}appId: String, ${'$'}sandbox: Boolean) {
          registerDevice(input: { notificationToken: ${'$'}notificationToken, appId: ${'$'}appId, sandbox: ${'$'}sandbox }) { registered }
        }
    """.trimIndent()
}
