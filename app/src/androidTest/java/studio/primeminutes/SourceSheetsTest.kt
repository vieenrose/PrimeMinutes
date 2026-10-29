package studio.primeminutes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.primeminutes.ui.PodcastPanel

/**
 * Tests the podcast source UI. Static state (render, button enablement) is asserted offline; a live-network smoke test confirms a real search
 * completes (the busy indicator clears) rather than hanging. Requires network for the @* live tests.
 */
@RunWith(AndroidJUnit4::class)
class SourceSheetsTest {

    @get:Rule val compose = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int) = ctx.getString(id)

    private fun searchCleared() = compose.waitUntil(30_000) {
        compose.onAllNodesWithText(str(R.string.dl_searching), substring = true).fetchSemanticsNodes().isEmpty()
    }

    // --- PodcastPanel ------------------------------------------------------------------------

    @Test fun podcastSearchButtonDisabledUntilQueryTyped() {
        compose.setContent { PodcastPanel(onStarted = {}) }
        compose.onNodeWithText(str(R.string.podcast_search_hint)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.podcast_search)).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("news")
        compose.onNodeWithText(str(R.string.podcast_search)).assertIsEnabled()
    }

    @Test fun podcastSearchCompletesOverNetwork() {
        compose.setContent { PodcastPanel(onStarted = {}) }
        compose.onNode(hasSetTextAction()).performTextInput("BBC")
        compose.onNodeWithText(str(R.string.podcast_search)).performClick()
        searchCleared()   // the busy "searching" bar must clear within 30 s (not hang)
        compose.onNodeWithText(str(R.string.podcast_search)).assertIsEnabled()
    }
}
