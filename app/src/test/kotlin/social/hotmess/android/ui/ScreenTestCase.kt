package social.hotmess.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application
import social.hotmess.android.AppGraph

/**
 * What the screen test classes share: a compose rule, a [StubApi] answering the side loads every signed-in screen
 * makes, and a check after each test that nothing went to the API unstubbed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
abstract class ScreenTestCase {
    @get:Rule val compose = createComposeRule()

    protected val stub = StubApi().apply { stubBasics() }
    protected val navigator = RecordingNavigator()

    @After fun everythingStubbed() {
        assertTrue("Unstubbed requests: ${stub.unstubbed}", stub.unstubbed.isEmpty())
    }

    protected fun show(atVenue: Boolean = false, dark: Boolean = false, content: @Composable () -> Unit): AppGraph {
        val graph = stub.graph()
        if (atVenue) graph.location.simulate(47.65, -117.41, "Nyne")
        compose.show(graph, darkTheme = dark) { content() }
        return graph
    }

    /** Waits for `text`, failing with what is on screen instead. */
    protected fun expectText(text: String) {
        try {
            compose.waitForText(text)
        } catch (error: Throwable) {
            throw AssertionError("No \"$text\" on screen:\n${screen()}", error)
        }
    }

    protected fun expectNoText(text: String) {
        compose.waitForIdle()
        assertTrue("\"$text\" is on screen:\n${screen()}", !compose.hasText(text))
    }

    /** Taps the first clickable node showing exactly `text`. */
    protected fun tap(text: String) {
        try {
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText(text) or hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            throw AssertionError("Nothing to tap called \"$text\":\n${screen()}", error)
        }
        val byText = compose.onAllNodes(hasText(text) and hasClickAction())
        val node = if (byText.fetchSemanticsNodes().isNotEmpty()) byText.onFirst()
        else compose.onAllNodes(hasContentDescription(text) and hasClickAction()).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
        compose.waitForIdle()
    }

    /** Types into the first text field whose label, placeholder or text contains `hint`. */
    protected fun type(hint: String, text: String) {
        expectText(hint)
        val field = compose.onAllNodes(hasSetTextAction() and hasText(hint, substring = true)).onFirst()
        runCatching { field.performScrollTo() }
        field.performTextInput(text)
        compose.waitForIdle()
    }

    /** Waits until the stub has seen `key` at least `times` times. */
    protected fun awaitRequest(key: String, times: Int = 1) {
        try {
            compose.waitUntil(10_000) { stub.count(key) >= times }
        } catch (error: Throwable) {
            throw AssertionError("No $key request; saw ${stub.operationNames}\n${screen()}", error)
        }
    }

    /** Scrolls whichever list holds a node with `text` (or that content description) until it's composed. */
    protected fun scrollTo(text: String) {
        val target = androidx.compose.ui.test.hasText(text, substring = true) or hasContentDescription(text, substring = true)
        val lists = compose.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction())
        for (index in 0 until lists.fetchSemanticsNodes().size) {
            if (runCatching { lists[index].performScrollToNode(target) }.isSuccess) {
                compose.waitForIdle()
                return
            }
        }
        throw AssertionError("No list scrolls to \"$text\":\n${screen()}")
    }

    protected fun screen(): String {
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        return (0 until roots.fetchSemanticsNodes().size).joinToString("\n") { roots[it].printToString() }
    }
}
