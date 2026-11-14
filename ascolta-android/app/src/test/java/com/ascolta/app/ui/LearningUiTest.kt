package com.ascolta.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.activity.compose.setContent
import com.ascolta.app.data.Segment
import com.ascolta.app.data.VocabEntity
import com.ascolta.app.work.TranscriptionPhase
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class LearningUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun setContent(content: @Composable () -> Unit) {
        val activity = org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        activity.setContent(content = content)
    }
    private fun card(word: String = "écouter", sentence: Boolean = false) = VocabEntity(
        id = if (sentence) 2 else 1, word = word, translation = "listen", sentence = "Nous écoutons un podcast.",
        sentenceTranslation = "We listen to a podcast.", language = "fr", episodeId = 1, episodeTitle = "Apprendre au quotidien", createdAt = 0,
        isSentence = sentence,
    )
    @Test fun filtersSearchMeaningAndContextWithoutChangingCollection() {
        val cards = listOf(card(), card("Nous écoutons.", true))
        assertEquals(2, filterLearningCards(cards, 0, " LISTEN ").size)
        assertEquals(1, filterLearningCards(cards, 1, "podcast").size)
        assertEquals(1, filterLearningCards(cards, 2, "nous").size)
        assertTrue(filterLearningCards(cards, 0, "missing").isEmpty())
        assertEquals(2, cards.size)
        assertEquals(TranscriptionPhase.WAITING, TranscriptionPhase.from("unknown"))
    }
    @Test fun detailsRevealRestartOnlyAfterTap() {
        var paused = false
        var restarted = false
        setContent { MaterialTheme { TranscriptionStatusCard(TranscriptionPhase.PROCESSING, "Whisper Small", "Pause", { paused = true }, onRestart = { restarted = true }) } }
        compose.onNodeWithText("Whisper Small").assertDoesNotExist()
        compose.onNodeWithText("Pause").performClick()
        assertTrue(paused)
        compose.onNodeWithText("Show details").performClick()
        compose.onNodeWithText("Whisper Small").assertExists()
        compose.onNodeWithText("Restart", substring = true).performClick()
        assertTrue(restarted)
    }
    @Test fun selectionAndPlaybackActionsStayAccessible() {
        var saved = 0; var listened = 0; var played = 0
        setContent { MaterialTheme { Column {
            SelectionBar("écouter", false, false, {}, { saved++ }, {}, { listened++ }, {}, {}, {})
            Controls(false, false, 30_000, 300_000, 1f, { played++ }, {}, {}, {}, {}, {})
        } } }
        compose.onNodeWithText("Listen", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Save", substring = true).performClick()
        compose.onNodeWithContentDescription("Play/pause").performClick()
        assertEquals(1, saved); assertEquals(1, listened); assertEquals(1, played)
    }
    @Test fun transcriptSearchFindsTranslationAndSeeksItsSentence() {
        var sought = -1L
        setContent { MaterialTheme {
            var query by remember { mutableStateOf("") }
            TranscriptSearchContent(
                listOf(Segment(0, 12_000, "Bonjour.", "Hello."), Segment(12_000, 23_000, "Écoutez à votre rythme.", "Listen at your own pace.")),
                query, { query = it }, { sought = it },
            )
        } }
        compose.onNodeWithText("Search transcript").performTextInput("PACE")
        compose.onNodeWithText("Bonjour.").assertDoesNotExist()
        compose.onNodeWithText("Écoutez à votre rythme.").performClick()
        assertEquals(12_000L, sought)
    }
    @Test fun savingDisablesRepeatedSaveButKeepsListeningAvailable() {
        var saves = 0; var listens = 0
        setContent { MaterialTheme { SelectionBar("écouter", false, false, {}, { saves++ }, {}, { listens++ }, {}, {}, {}, saving = true) } }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Listen").performClick()
        assertEquals(0, saves); assertEquals(1, listens)
    }
    @Test fun savedCardDeletionRequiresConfirmation() {
        var deleted = false; var episode: Long? = null; var spoken = false
        setContent { MaterialTheme { SavedCardDetails(card(), onEpisode = { episode = it }, onAudio = {}, onSpeak = { spoken = true }, onDelete = { deleted = true }) } }
        compose.onNodeWithText("Clear voice").performScrollTo().performClick()
        assertTrue(spoken)
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        assertFalse(deleted)
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(deleted)
        compose.onNodeWithText("Apprendre au quotidien").performClick()
        assertEquals(1L, episode)
        compose.onNodeWithText("Delete").performClick()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        assertTrue(deleted)
    }
    @Composable private fun Player(phase: TranscriptionPhase, selection: Boolean = false, expanded: Boolean = false) {
        MaterialTheme(colorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF8CD4BE), surface = androidx.compose.ui.graphics.Color(0xFF101413))) {
            Surface { Column(Modifier.fillMaxSize()) {
                Text("Apprendre au quotidien", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                TranscriptionStatusCard(phase, "Creating subtitles on your phone · Whisper Small", if (phase == TranscriptionPhase.PAUSED) "Continue" else if (phase == TranscriptionPhase.FAILED) "Retry" else "Pause", {}, bufferedMs = 147_000, error = if (phase == TranscriptionPhase.FAILED) "The speech model could not start. Your saved subtitles are kept." else null, onRestart = {}, initiallyExpanded = expanded)
                Column(Modifier.weight(1f).androidxVerticalScroll()) {
                    SegmentItem(Segment(0, 12_000, "Aujourd’hui, on découvre une nouvelle façon d’apprendre.", "Today, we discover a new way to learn."), null, "fr", active = true, activeWord = -1, showOriginal = true, showTranslation = true, textScale = 1f, onSeek = {}, onWord = {})
                    SegmentItem(Segment(12_000, 23_000, "Écoutez à votre rythme et gardez les mots utiles.", "Listen at your own pace and save useful words."), null, "fr", active = false, activeWord = -1, showOriginal = true, showTranslation = true, textScale = 1f, onSeek = {}, onWord = {})
                }
                if (selection) SelectionBar("une nouvelle façon d’apprendre", false, false, {}, {}, {}, {}, {}, {}, {}, translation = "a new way to learn")
                Controls(false, false, 30_000, 300_000, 1f, {}, {}, {}, {}, {}, {})
            } }
        }
    }
    private fun shot(name: String, content: @Composable () -> Unit) = captureRoboImage("build/screens/ui-$name.png", content = content)
    @Test fun readyScreenshot() = shot("ready-412") { Player(TranscriptionPhase.READY_AHEAD) }
    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi") fun processingScreenshot() = shot("processing-360") { Player(TranscriptionPhase.PROCESSING) }
    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi") fun selectedLargeFontScreenshot() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.6f)
        shot("selection-large-font") { Player(TranscriptionPhase.READY_AHEAD, selection = true) }
    }
    @Test fun pausedScreenshot() = shot("paused") { Player(TranscriptionPhase.PAUSED) }
    @Test fun failedScreenshot() = shot("failed") { Player(TranscriptionPhase.FAILED, expanded = true) }
    @Test fun selectionScreenshot() = shot("selection") { Player(TranscriptionPhase.READY_AHEAD, selection = true) }
    @Test @Config(qualifiers = "w915dp-h412dp-land-xxhdpi") fun landscapeScreenshot() = shot("landscape") { Player(TranscriptionPhase.PROCESSING) }
    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi") fun largeFontScreenshot() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.6f)
        shot("large-font") { Player(TranscriptionPhase.FAILED, expanded = true) }
    }
    @Composable private fun Words(empty: Boolean = false) {
        MaterialTheme(colorScheme = darkColorScheme()) { Surface { SavedWordsContent(
            words = if (empty) emptyList() else listOf(card(), card("Nous écoutons.", true)),
            due = 2, query = "", kind = 0, exporting = false, onQuery = {}, onKind = {}, onReview = {}, onExport = {}, onWord = {}, onListen = {},
        ) } }
    }
    @Test fun savedScreenshot() = shot("words") { Words() }
    @Test fun emptyScreenshot() = shot("empty") { Words(true) }
    @Test @Config(qualifiers = "w915dp-h412dp-land-xxhdpi") fun wordsLandscapeScreenshot() = shot("words-landscape") { Words() }
    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi") fun wordsLargeFontScreenshot() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.6f)
        shot("words-large-font") { Words() }
    }
}
