package studio.primeminutes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.primeminutes.ui.AddSourceSheet

/**
 * Isolated tests for the "Add audio" bottom sheet: all five sources render, and tapping each invokes
 * its callback (the row fires onDismiss + the source action).
 */
@RunWith(AndroidJUnit4::class)
class AddSourceSheetTest {

    @get:Rule val compose = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class Spies {
        var file = false; var record = false; var podcast = false; var dismiss = false
    }

    private fun host(s: Spies) {
        compose.setContent {
            AddSourceSheet(
                onPickFile = { s.file = true }, onRecord = { s.record = true },
                onPodcast = { s.podcast = true },
                onDismiss = { s.dismiss = true },
            )
        }
    }

    // Recording is the Studio's big button now; the sheet only adds existing audio.
    @Test fun rendersImportAndPodcastAndShows() {
        host(Spies())
        listOf(R.string.source_audio_file, R.string.source_audio_file_desc, R.string.source_podcast, R.string.sub_source).forEach {
            compose.onNodeWithText(ctx.getString(it)).assertIsDisplayed()
        }
    }

    @Test fun tappingPodcastFiresPodcast() {
        val s = Spies(); host(s)
        compose.onNodeWithText(ctx.getString(R.string.source_podcast)).performClick()
        assertTrue(s.podcast)
    }

    @Test fun noVideoSiteRow() {
        host(Spies())
        compose.onAllNodesWithText("YouTube", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("PeerTube", substring = true).assertCountEquals(0)
    }

    @Test fun noSeparateOpenSessionRow() {
        host(Spies())
        compose.onAllNodesWithText(".ogg", substring = true).assertCountEquals(0)
    }

    @Test fun tappingFileFiresPickFile() {
        val s = Spies(); host(s)
        compose.onNodeWithText(ctx.getString(R.string.source_audio_file)).performClick()
        assertTrue(s.file)
    }
}
