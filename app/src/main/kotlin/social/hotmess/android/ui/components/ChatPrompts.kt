package social.hotmess.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import social.hotmess.android.ui.theme.HotMessTheme
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.ChatPrompt
import social.hotmess.core.ChatPromptKind
import social.hotmess.core.ChatPromptShownAs
import social.hotmess.core.ChatPrompts
import social.hotmess.core.Formatting
import social.hotmess.core.initialsForDisplay
import java.time.Duration
import java.time.Instant

/*
 * Event chat prompts (design system ChatPromptsAndTinyAvatars.md): the PromptBar under the room's title,
 * the TriviaCard in its place for a trivia prompt, the PromptCard a quiet room gets in its thread, the
 * Prompt label on answers and the answer chip in the composer. Prompts are the audience talking: they
 * carry its name and mark, never a person's.
 */

/** Overlines on prompts: caption size, extra bold and spaced, upper case. */
private val PromptOverline = HotMessType.caption.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.04.em)

/**
 * The audience's mark, for what it posts (prompts): an `accent` disc with its initial, at chat's
 * [TinyAvatarSize]. It has no presence and no photo, so it never reads as a person.
 */
@Composable
fun AudienceMark(name: String, modifier: Modifier = Modifier, size: Dp = TinyAvatarSize) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(tokens.accent)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.initialsForDisplay().take(1).ifEmpty { "?" },
            style = HotMessType.caption.copy(fontSize = if (size <= 20.dp) 9.sp else 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold),
            color = tokens.onAccent,
            maxLines = 1,
        )
    }
}

/** The 28 `accent-soft` disc with a question bubble that leads the PromptBar. */
@Composable
private fun PromptGlyph() {
    Box(Modifier.size(28.dp).clip(CircleShape).background(tokens.accentSoft), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.QuestionAnswer, contentDescription = null, tint = tokens.accentInk, modifier = Modifier.size(16.dp))
    }
}

/** The quiet button prompts use for "Answer": `accent-ink` words, no fill. */
@Composable
private fun QuietButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 36.dp)) {
        Text(text, style = HotMessType.label, color = tokens.accentInk)
    }
}

/** A `surface-raised` card, 8 radius and a small shadow, with the 3dp spectrum stripe along its top. */
@Composable
private fun PromptSurface(modifier: Modifier = Modifier, stripe: Boolean = true, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .shadow(1.dp, Radius.sm)
            .clip(Radius.sm)
            .background(tokens.surfaceRaised),
    ) {
        if (stripe) SpectrumBar(height = 3.dp)
        content()
    }
}

/**
 * The night's pinned prompt under the room's title (design system PromptBar): the question-bubble disc,
 * "Tonight's prompt · 2 of 6", the prompt, and a footer ("14 answers · Next one at 9:15pm") with a quiet
 * "Answer". [collapsed] (once the reader scrolls, or under a pinned announcement) it's one line: "PROMPT",
 * the prompt truncated, and "Answer". A trivia prompt draws as a [TriviaCard] instead.
 */
@Composable
fun PromptBar(
    prompt: ChatPrompt,
    footer: String?,
    onAnswer: () -> Unit,
    modifier: Modifier = Modifier,
    collapsed: Boolean = false,
    answerCount: Int = 0,
) {
    if (prompt.isTrivia && !collapsed) {
        TriviaCard(prompt, answerCount, onAnswer, modifier)
        return
    }
    PromptSurface(modifier.padding(horizontal = Space.s3, vertical = Space.s1)) {
        if (collapsed) {
            Row(
                Modifier.fillMaxWidth().padding(start = Space.s3, end = Space.s1),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                Text(prompt.answerLabel.uppercase(), style = PromptOverline, color = tokens.accentInk)
                Text(
                    prompt.text,
                    style = HotMessType.bodySmall,
                    color = tokens.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                QuietButton("Answer", onAnswer)
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = Space.s3, end = Space.s3, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PromptGlyph()
                Column(Modifier.weight(1f)) {
                    Text(prompt.overline.uppercase(), style = PromptOverline, color = tokens.accentInk, modifier = Modifier.semantics { heading() })
                    Text(prompt.text, style = HotMessType.body.copy(fontWeight = FontWeight.SemiBold), color = tokens.ink)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = Space.s3, end = Space.s1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(footer.orEmpty(), style = HotMessType.bodySmall, color = tokens.inkMuted, modifier = Modifier.weight(1f))
                QuietButton("Answer", onAnswer)
            }
        }
    }
}

/**
 * A trivia prompt in the PromptBar's place (design system TriviaCard): "Trivia · 3 of 6" with "9 answers"
 * on the right, the question, "Shout it to your table before you look it up.", then "Show the answer"
 * and a quiet "Answer". The answer shows as a `success-soft` row when the reader taps, or for everyone at
 * its reveal time; "Hide the answer" puts it back.
 */
@Composable
fun TriviaCard(prompt: ChatPrompt, answerCount: Int, onAnswer: () -> Unit, modifier: Modifier = Modifier) {
    // What the reader chose ("Show the answer" or "Hide the answer"); null follows the reveal time.
    var choice by rememberSaveable(prompt.id) { mutableStateOf<Boolean?>(null) }
    // Revealed for everyone once revealAt passes, while the card shows; that overrides an earlier "Hide".
    var revealedForAll by remember(prompt.id, prompt.revealAt) { mutableStateOf(prompt.isRevealed()) }
    LaunchedEffect(prompt.id, prompt.revealAt) {
        val at = prompt.revealAt ?: return@LaunchedEffect
        if (prompt.answer == null || revealedForAll) return@LaunchedEffect
        delay(Duration.between(Instant.now(), at).toMillis().coerceAtLeast(0L))
        revealedForAll = true
        if (choice == false) choice = null
    }
    val answer = prompt.answer?.takeIf { it.isNotBlank() }
    val revealed = answer != null && (choice ?: revealedForAll)
    PromptSurface(modifier.padding(horizontal = Space.s3, vertical = Space.s1)) {
        Column(Modifier.padding(start = Space.s3, end = Space.s3, top = 10.dp, bottom = Space.s1)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    prompt.overline.uppercase(),
                    style = PromptOverline,
                    color = tokens.accentInk,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                ChatPrompts.answers(answerCount)?.let {
                    Text(it, style = HotMessType.bodySmall, color = tokens.inkMuted)
                }
            }
            Text(prompt.text, style = HotMessType.heading, color = tokens.ink, modifier = Modifier.padding(top = 2.dp))
            Text(
                "Shout it to your table before you look it up.",
                style = HotMessType.bodySmall,
                color = tokens.inkMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (revealed && answer != null) {
                Row(
                    Modifier
                        .padding(top = Space.s2)
                        .fillMaxWidth()
                        .clip(Radius.sm)
                        .background(tokens.successSoft)
                        .padding(horizontal = Space.s3, vertical = Space.s2),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s2),
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = "Answer", tint = tokens.success, modifier = Modifier.size(18.dp))
                    Text(answer, style = HotMessType.body.copy(fontWeight = FontWeight.SemiBold), color = tokens.ink)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = Space.s2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                if (answer != null) {
                    SecondaryButton(
                        if (revealed) "Hide the answer" else "Show the answer",
                        onClick = { choice = !revealed },
                    )
                }
                QuietButton("Answer", onAnswer)
            }
        }
    }
}

/**
 * The card a quiet room gets in its thread (design system PromptCard), centred at up to 330 wide: the
 * stripe, "**Hot Mess** · Prompt · 9:14pm" beside the audience's mark, the prompt in heading type, then
 * "Answer" and "Not now", which hides it for this reader only.
 */
@Composable
fun PromptCard(
    prompt: ChatPrompt,
    audienceName: String,
    sentAt: Instant?,
    onAnswer: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        PromptSurface(Modifier.widthIn(max = 330.dp)) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    AudienceMark(audienceName)
                    val ink = tokens.ink
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = ink)) { append(audienceName) }
                            append(" · ")
                            append(prompt.answerLabel)
                            sentAt?.let { append(" · ${Formatting.clock(it)}") }
                        },
                        style = HotMessType.caption,
                        color = tokens.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(prompt.text, style = HotMessType.heading, color = tokens.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    PrimaryButton("Answer", onAnswer)
                    SecondaryButton("Not now", onDismiss)
                }
            }
        }
    }
}

/**
 * The first line of an answer's bubble: a reply arrow and "Prompt" (or "Trivia") in caption size, weight
 * 600, in [color] (`ink-muted` on others' bubbles, `on-accent` on your own).
 */
@Composable
fun PromptLabel(label: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(label, style = HotMessType.caption.copy(fontWeight = FontWeight.SemiBold), color = color, maxLines = 1)
    }
}

/**
 * Above the composer while answering (design system answer chip): an `accent-soft` chip with the reply
 * arrow, "**Answering the prompt**", the prompt on one truncated line, and a 36 close button ("Stop
 * answering the prompt").
 */
@Composable
fun AnswerChip(prompt: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(Radius.md)
            .background(tokens.accentSoft)
            .padding(start = Space.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = null, tint = tokens.accentInk, modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp).clearAndSetSemantics { contentDescription = "Answering the prompt: $prompt" }) {
            Text("Answering the prompt", style = HotMessType.caption.copy(fontWeight = FontWeight.Bold), color = tokens.accentInk, maxLines = 1)
            Text(prompt, style = HotMessType.bodySmall, color = tokens.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Stop answering the prompt", onClick = onClose)
                .semantics { contentDescription = "Stop answering the prompt" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = tokens.accentInk, modifier = Modifier.size(18.dp))
        }
    }
}

private val previewPrompt = ChatPrompt(
    id = "p-2",
    text = "Who should have won last season? Find someone at the bar who disagrees.",
    shownAs = ChatPromptShownAs.PINNED,
    number = 2,
    total = 6,
    nextAt = Instant.parse("2026-10-24T04:15:00Z"),
)

private val previewTrivia = ChatPrompt(
    id = "p-3",
    text = "Which queen won season 4?",
    kind = ChatPromptKind.TRIVIA,
    answer = "Sharon Needles",
    shownAs = ChatPromptShownAs.PINNED,
    number = 3,
    total = 6,
)

@Preview(name = "Prompts", widthDp = 360)
@Composable
private fun PromptsPreview() {
    HotMessTheme(darkTheme = false) {
        Column(Modifier.background(tokens.surface).padding(vertical = Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            PromptBar(previewPrompt, "14 answers · Next one at 9:15pm", onAnswer = {})
            PromptBar(previewPrompt, null, onAnswer = {}, collapsed = true)
            TriviaCard(previewTrivia, 9, onAnswer = {})
            PromptCard(
                previewPrompt.copy(shownAs = ChatPromptShownAs.CARD, text = "Ask whoever is next to you what they're drinking."),
                "Hot Mess",
                Instant.parse("2026-10-24T04:14:00Z"),
                onAnswer = {},
                onDismiss = {},
                modifier = Modifier.padding(horizontal = Space.s4),
            )
            AnswerChip(previewPrompt.text, onClose = {}, modifier = Modifier.padding(horizontal = Space.s3))
        }
    }
}

@Preview(name = "Prompts, dark", widthDp = 360)
@Composable
private fun PromptsDarkPreview() {
    HotMessTheme(darkTheme = true) {
        Column(Modifier.background(tokens.surface).padding(vertical = Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            PromptBar(previewPrompt, "14 answers · Next one at 9:15pm", onAnswer = {})
            TriviaCard(previewTrivia.copy(revealAt = Instant.EPOCH), 9, onAnswer = {})
        }
    }
}
