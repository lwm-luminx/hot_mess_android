package social.hotmess.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.audiencekit.PrivacyChoiceKey
import com.audiencekit.PrivacyChoices
import com.audiencekit.PrivacyChoicesCopy
import com.audiencekit.PrivacyOffer
import com.audiencekit.PrivacyOptOutSource
import com.audiencekit.privacy
import com.audiencekit.setPrivacyChoice
import com.audiencekit.startPrivacyPass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import social.hotmess.android.PlayStorefront
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.DetailSection
import social.hotmess.android.ui.components.EmptyRow
import social.hotmess.android.ui.components.Feed
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.components.SecondaryButton
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val APP_NAME = "Hot Mess"

/**
 * "Your privacy choices": what the member chooses about their data in Hot Mess (the API's doc/PRIVACY_CHOICES.md). Each
 * free choice is one switch, the same either way, and the server enforces it. Where Hot Mess sells a privacy pass,
 * it's paid on the web in the browser, so it only shows in US Play storefronts, as with tips to read without ads.
 */
@Composable
fun PrivacyChoicesScreen(navigator: Navigator) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var offer by remember { mutableStateOf<PrivacyOffer?>(null) }
    var choices by remember { mutableStateOf(PrivacyChoices.DEFAULTS) }
    var storefront by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf<PrivacyChoiceKey?>(null) }
    var checkingOut by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val signedIn = graph.session.isSignedIn

    suspend fun load() {
        try {
            val (loadedOffer, loadedChoices) = graph.audienceKit.privacy()
            offer = loadedOffer
            choices = loadedChoices
            if (loadedChoices.hasPrivacyPass) checkingOut = false
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = "Your privacy choices couldn't load. Try again in a moment."
        }
    }

    LaunchedEffect(Unit) {
        load()
        storefront = PlayStorefront.countryCode(context)
    }
    // Back from the browser's checkout: read the pass again.
    LifecycleResumeEffect(checkingOut) {
        if (checkingOut) scope.launch { load() }
        onPauseOrDispose {}
    }

    ScreenScaffold(title = PrivacyChoicesCopy.TITLE, onBack = navigator::back) {
        Feed {
            val loaded = offer
            if (loaded == null) {
                item { DetailSection(null) { EmptyRow(error ?: "Loading…") } }
                return@Feed
            }
            item {
                Text(
                    PrivacyChoicesCopy.intro(APP_NAME),
                    style = HotMessType.bodySmall,
                    color = tokens.inkMuted,
                    modifier = Modifier.padding(horizontal = Space.s5),
                )
            }
            item {
                DetailSection(null) {
                    loaded.choices.filter { it != PrivacyChoiceKey.PRIVACY_PASS }.forEachIndexed { index, key ->
                        if (index > 0) RowDivider()
                        ChoiceRow(
                            key = key,
                            choices = choices,
                            enabled = signedIn && saving != key,
                            onChange = { on ->
                                scope.launch {
                                    saving = key
                                    try {
                                        choices = graph.audienceKit.setPrivacyChoice(key, on)
                                        error = null
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        error = "That didn't save. Try again."
                                    } finally {
                                        saving = null
                                    }
                                }
                            },
                        )
                    }
                }
            }
            val until = choices.privacyPassUntil?.let { formatDate(it) }
            val cents = loaded.passCents
            if (cents != null && PrivacyChoicesCopy.isPassOffered(loaded, storefront)) {
                item {
                    DetailSection(PrivacyChoicesCopy.label(PrivacyChoiceKey.PRIVACY_PASS)) {
                        Column(Modifier.padding(horizontal = Space.s5, vertical = Space.s3)) {
                            if (until != null) Text(PrivacyChoicesCopy.passRunning(until), style = HotMessType.body, color = tokens.ink)
                            if (checkingOut && until == null) {
                                Text(PrivacyChoicesCopy.PENDING, style = HotMessType.bodySmall, color = tokens.inkMuted)
                            }
                            Text(PrivacyChoicesCopy.passExplanation(loaded.passDays), style = HotMessType.bodySmall, color = tokens.inkMuted)
                            SecondaryButton(
                                PrivacyChoicesCopy.passButton(cents, loaded.passDays),
                                onClick = {
                                    scope.launch {
                                        try {
                                            val checkout = graph.audienceKit.startPrivacyPass(returnUrl = "hotmess://privacy")
                                            checkingOut = true
                                            // The browser, not a WebView: this is a web purchase.
                                            uriHandler.openUri(checkout.checkoutUrl)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            error = "The privacy pass isn't available right now."
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("privacy.pass"),
                            )
                        }
                    }
                }
            } else if (until != null) {
                item { DetailSection(null) { EmptyRow(PrivacyChoicesCopy.passRunning(until)) } }
            }
            error?.let { message ->
                item {
                    Text(message, style = HotMessType.bodySmall, color = tokens.danger, modifier = Modifier.padding(horizontal = Space.s5))
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(key: PrivacyChoiceKey, choices: PrivacyChoices, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.s5, vertical = Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(PrivacyChoicesCopy.label(key), style = HotMessType.body, color = tokens.ink)
            Text(PrivacyChoicesCopy.detail(key, APP_NAME), style = HotMessType.bodySmall, color = tokens.inkMuted)
            if (key == PrivacyChoiceKey.SALE_SHARE_OPT_OUT &&
                choices.saleShareOptOutSource == PrivacyOptOutSource.GLOBAL_PRIVACY_CONTROL
            ) {
                Text(PrivacyChoicesCopy.FROM_GLOBAL_PRIVACY_CONTROL, style = HotMessType.bodySmall, color = tokens.inkMuted)
            }
        }
        Switch(
            checked = choices.isOn(key),
            onCheckedChange = onChange,
            enabled = enabled,
            modifier = Modifier.testTag("privacy.${key.name}"),
        )
    }
}

private fun formatDate(iso: String): String? = runCatching { OffsetDateTime.parse(iso) }.getOrNull()
    ?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
