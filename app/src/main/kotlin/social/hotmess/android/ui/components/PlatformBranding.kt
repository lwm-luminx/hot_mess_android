package social.hotmess.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiencekit.PlatformCredit
import com.audiencekit.StaffApp
import com.audiencekit.android.StaffApps
import social.hotmess.android.BuildConfig
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.openStaffApp
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens

/** What a [ManagedListingNotice] is on: a venue (Velvet) or a person page (Backstage). */
enum class ManagedKind { VENUE, PERSON }

/** Whether the signed-in person manages this venue or person page (Me's "Your pages"), so it gets a [ManagedListingNotice]. */
@Composable
fun rememberManages(kind: ManagedKind, id: String): Boolean {
    val listings by LocalAppGraph.current.listings.state.collectAsStateWithLifecycle()
    return (if (kind == ManagedKind.VENUE) listings.venues else listings.people).any { it.id.equals(id, ignoreCase = true) }
}

/**
 * "You manage this venue · Open in Velvet" on a venue the signed-in person manages, and the same with Backstage on their
 * person page. Hot Mess doesn't edit pages; the staff app does, opened on this page. On a phone without the app the
 * button gets it instead ("Join the Velvet beta", "Get Backstage"). With [eventId], it's on an event at venue [id] and
 * opens Velvet on that event. Nothing on anyone else's page.
 */
@Composable
fun ManagedListingNotice(kind: ManagedKind, id: String, modifier: Modifier = Modifier, eventId: String? = null) {
    val context = LocalContext.current
    val app = if (kind == ManagedKind.VENUE) StaffApp.VELVET else StaffApp.BACKSTAGE
    val audienceId = BuildConfig.AUDIENCE_ID.takeIf { it.isNotEmpty() }
    val link = if (kind == ManagedKind.VENUE) StaffApp.velvet(id, audienceId, eventId) else StaffApp.backstage(id, audienceId)
    val installed = StaffApps.isInstalled(context, app)

    Row(
        modifier
            .fillMaxWidth()
            .background(tokens.accentSoft, Radius.lg)
            .padding(Space.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            Text(
                when {
                    eventId != null -> "This is at a venue you manage"
                    kind == ManagedKind.VENUE -> "You manage this venue"
                    else -> "You manage this page"
                },
                style = HotMessType.label,
                color = tokens.accentInk,
            )
            Text(if (eventId != null) "Edit it in ${app.appName}." else "Post and manage it in ${app.appName}.", style = HotMessType.bodySmall, color = tokens.inkMuted)
        }
        PrimaryButton(app.linkTitle(installed), onClick = { context.openStaffApp(app, link) })
    }
}

/**
 * "Powered by AudienceKit · A Love Wins Media product" for the foot of Me, with Love Wins Media in its pink-to-amber
 * gradient, then AudienceKit's policies (Privacy · Terms · Child safety · Contact), "Bring AudienceKit to your venue" and
 * the copyright line. Words and links come from the SDK's [PlatformCredit], tagged with this app's package.
 */
@Composable
fun PlatformCreditFooter(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val source = context.packageName
    val gradient = (if (tokens.isDark) PlatformCredit.gradientDark else PlatformCredit.gradientLight).map(::hexColor)

    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s5),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Text(
            buildAnnotatedString {
                append(PlatformCredit.POWERED_BY_PREFIX)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = tokens.ink)) { append(PlatformCredit.PLATFORM_NAME) }
            },
            style = HotMessType.bodySmall,
            color = tokens.inkMuted,
            modifier = Modifier.clickable(role = Role.Button) { context.openUrl(PlatformCredit.platformUrl(source = source)) },
        )
        Text(
            buildAnnotatedString {
                append(PlatformCredit.PRODUCT_PREFIX)
                withStyle(SpanStyle(brush = Brush.horizontalGradient(gradient), fontWeight = FontWeight.Bold)) {
                    append(PlatformCredit.COMPANY_NAME)
                }
                append(PlatformCredit.PRODUCT_SUFFIX)
            },
            style = HotMessType.bodySmall,
            color = tokens.inkMuted,
            modifier = Modifier.clickable(role = Role.Button) { context.openUrl(PlatformCredit.companyUrl(source)) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            PlatformCredit.policies.forEach { policy ->
                Text(
                    policy.title,
                    style = HotMessType.caption,
                    color = tokens.accentInk,
                    modifier = Modifier.clickable(role = Role.Button) { context.openUrl(PlatformCredit.policyUrl(policy, source)) },
                )
            }
        }
        Text(
            "${PlatformCredit.CALL_TO_ACTION} →",
            style = HotMessType.label,
            color = tokens.accentInk,
            modifier = Modifier.clickable(role = Role.Button) { context.openUrl(PlatformCredit.callToActionUrl(source)) },
        )
        Text(PlatformCredit.copyright(), style = HotMessType.caption, color = tokens.inkMuted, textAlign = TextAlign.Center)
    }
}

private fun hexColor(hex: String): Color = Color(0xFF000000 or hex.removePrefix("#").toLong(16))
