package social.hotmess.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.audiencekit.AdFreeTipUnavailableException
import com.audiencekit.AdFreeTips
import com.audiencekit.Publication
import com.audiencekit.TipConfirmationStatus
import com.audiencekit.confirmTip
import com.audiencekit.publication
import com.audiencekit.startAdFreeTip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import social.hotmess.android.PlayStorefront
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.components.SecondaryButton
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.tokens
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "Tip QSaltLake to read without ads": a reader tips a Soapbox publication, all of it the publisher's (Hot Mess takes no
 * cut), and a big enough tip reads that publication here without ads for a while. The ads going away ties the tip to
 * content, so it isn't a gift in the store's eyes and can't use the in-app payment sheet: the reader pays on the web,
 * in their browser, and the button only shows in US Play storefronts (AdFreeTips.isOffered). [onPaid] reloads.
 */
@Composable
fun AdFreeTipButton(publicationId: String, publicationName: String, offered: Boolean?, onPaid: () -> Unit) {
    val context = LocalContext.current
    var storefront by remember { mutableStateOf<String?>(null) }
    var tipping by remember { mutableStateOf(false) }
    LaunchedEffect(offered) {
        if (offered == true) storefront = PlayStorefront.countryCode(context)
    }
    if (!AdFreeTips.isOffered(offered, storefront)) return
    SecondaryButton(
        AdFreeTips.button(publicationName),
        onClick = { tipping = true },
        modifier = Modifier.fillMaxWidth().testTag("adFreeTip.button"),
    )
    if (tipping) {
        AdFreeTipDialog(publicationId, publicationName, onDismiss = { tipping = false }, onPaid = onPaid)
    }
}

/** Shown where the ads would be once the reader reads a publication without ads; [until] is ISO 8601. */
@Composable
fun AdFreeThanks(publicationName: String, until: String? = null) {
    val date = until?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
        ?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    Text(
        "♥ " + AdFreeTips.thanks(publicationName, date),
        style = HotMessType.bodySmall,
        color = tokens.inkMuted,
        modifier = Modifier.testTag("adFreeTip.thanks"),
    )
}

private sealed interface TipStep {
    data object Choosing : TipStep
    data object Starting : TipStep
    data class CheckingOut(val tipId: String) : TipStep
    data class Paid(val until: String?) : TipStep
    data object NotPaid : TipStep
    data class Failed(val message: String) : TipStep
}

/** Picks an amount, explains the deal, sends the reader to the web checkout, and checks the tip when they're back. */
@Composable
private fun AdFreeTipDialog(publicationId: String, publicationName: String, onDismiss: () -> Unit, onPaid: () -> Unit) {
    val graph = LocalAppGraph.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var publication by remember { mutableStateOf<Publication?>(null) }
    var amountCents by remember { mutableStateOf<Int?>(null) }
    var step by remember { mutableStateOf<TipStep>(TipStep.Choosing) }

    LaunchedEffect(publicationId) {
        try {
            publication = graph.audienceKit.publication(publicationId)
            amountCents = publication?.adFreeTipCents
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            step = TipStep.Failed(e.message ?: "Couldn't load $publicationName.")
        }
    }

    // Back from the browser: check the tip (it's pending until the payment clears).
    LifecycleResumeEffect(step) {
        val checking = step as? TipStep.CheckingOut
        if (checking != null) {
            scope.launch {
                val tip = runCatching { graph.audienceKit.confirmTip(checking.tipId) }.getOrNull() ?: return@launch
                step = when (tip.status) {
                    TipConfirmationStatus.PAID -> TipStep.Paid(tip.readsAdFreeUntil).also { onPaid() }
                    TipConfirmationStatus.PENDING -> checking
                    else -> TipStep.NotPaid
                }
            }
        }
        onPauseOrDispose { }
    }

    val tipCents = publication?.adFreeTipCents
    val days = publication?.adFreeDays
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(publicationName, style = HotMessType.heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(social.hotmess.android.ui.theme.Space.s2)) {
                if (tipCents != null && days != null) {
                    Text(AdFreeTips.explanation(publicationName, tipCents, days), style = HotMessType.body)
                    Row(horizontalArrangement = Arrangement.spacedBy(social.hotmess.android.ui.theme.Space.s2)) {
                        adFreePresets(tipCents).forEach { cents ->
                            FilterChip(
                                selected = amountCents == cents,
                                onClick = { amountCents = cents },
                                label = { Text(AdFreeTipsMoney.format(cents)) },
                            )
                        }
                    }
                }
                when (val current = step) {
                    is TipStep.CheckingOut -> Text(AdFreeTips.PENDING, style = HotMessType.bodySmall, color = tokens.inkMuted)
                    is TipStep.Paid -> AdFreeThanks(publicationName, current.until)
                    TipStep.NotPaid -> Text(AdFreeTips.NOT_PAID, style = HotMessType.bodySmall, color = tokens.inkMuted)
                    is TipStep.Failed -> Text(current.message, style = HotMessType.bodySmall, color = tokens.danger)
                    else -> Unit
                }
            }
        },
        confirmButton = {
            if (step is TipStep.Paid) {
                TextButton(onClick = onDismiss) { Text("Done") }
            } else {
                TextButton(
                    enabled = amountCents != null && step != TipStep.Starting && step !is TipStep.CheckingOut,
                    onClick = {
                        val amount = amountCents ?: return@TextButton
                        step = TipStep.Starting
                        scope.launch {
                            step = try {
                                val checkout = graph.audienceKit.startAdFreeTip(publicationId, amount)
                                // The reader's browser, not a WebView: this is a web purchase.
                                uriHandler.openUri(checkout.checkoutUrl)
                                TipStep.CheckingOut(checkout.tipId)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: AdFreeTipUnavailableException) {
                                TipStep.Failed("$publicationName can't take tips here yet.")
                            } catch (e: Exception) {
                                TipStep.Failed(e.message ?: "Something went wrong.")
                            }
                        }
                    },
                    modifier = Modifier.testTag("adFreeTip.checkout"),
                ) { Text(AdFreeTips.CONTINUE_TO_CHECKOUT) }
            }
        },
        dismissButton = { if (step !is TipStep.Paid) TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The least tip that counts, twice that and four times that, within the $200 limit on a tip. */
fun adFreePresets(tipCents: Int): List<Int> = listOf(tipCents, tipCents * 2, tipCents * 4).filter { it <= 20_000 }

/** "$5" or "$5.50". */
object AdFreeTipsMoney {
    fun format(cents: Int): String =
        if (cents % 100 == 0) "$" + (cents / 100) else "$" + "%d.%02d".format(cents / 100, cents % 100)
}
