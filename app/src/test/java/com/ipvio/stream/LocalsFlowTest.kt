package com.ipvio.stream

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.ipvio.stream.locals.LocalsFeature
import com.ipvio.stream.locals.LocalsStore
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * US locals end to end: channels come from the fake Xtream panel (tools/fake-xtream, started here on :8090) through the
 * app's own Xtream download and M3U parser, then Live TV > My Locals: state → market → all of the market's locals,
 * Back up one level at a time, the choice kept per playlist. Fold 7 cover / inner and TV sizes; screenshots in
 * app/build/screens/locals-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class LocalsFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    companion object {
        private const val PORT = 8090
        private var server: Process? = null
        private lateinit var live: List<Item>

        private fun up() = runCatching { java.net.Socket("127.0.0.1", PORT).close() }.isSuccess

        @BeforeClass @JvmStatic fun startServer() {
            if (!up()) {
                server = ProcessBuilder("python3", File("../tools/fake-xtream/server.py").absolutePath, PORT.toString(), "1")
                    .redirectErrorStream(true).redirectOutput(File("build/fake-xtream-locals.log")).start()
                repeat(50) { if (!up()) Thread.sleep(100) }
            }
            assertTrue("fake Xtream server on :$PORT", up())
            val out = java.io.StringWriter()
            XtreamApi(okhttp3.OkHttpClient(), "test").apply { parallel = false }.write(XtreamLogic.Acct("http://127.0.0.1:$PORT", "u", "p"), out)
            live = M3uParser.parse(out.toString()).filter { it.kind == Kind.LIVE }
            assertTrue(live.size > 100)
        }

        @AfterClass @JvmStatic fun stopServer() { server?.destroy() }
    }

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var opened: Item? = null
    private var inputMode: androidx.compose.ui.input.InputModeManager? = null

    @Before fun setUp() {
        MobilePrefs.mobile = true
        LiveMemory.setSel(ctx, "ALL")
        LocalsFeature.set(ctx, true)
        LocalsStore.resetForTest(); LocalsStore.init(ctx)
    }

    private fun title(part: String) = live.first { it.title.contains(part, ignoreCase = true) }.title
    private fun texts(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
        (if (n.config.contains(SemanticsProperties.Text)) n.config[SemanticsProperties.Text] else emptyList()).map { it.text }
    private fun row(name: String, prefix: Boolean = false) = rule.onAllNodes(SemanticsMatcher("row '$name'") {
        val t = texts(it).firstOrNull() ?: return@SemanticsMatcher false
        (if (prefix) t.startsWith(name) else t == name) && it.config.contains(SemanticsActions.OnClick)
    }).onFirst()
    private fun chanCount(name: String) = rule.onAllNodes(SemanticsMatcher("channel '$name'") { texts(it).getOrNull(1) == name }).fetchSemanticsNodes().size
    private fun chan(name: String) = rule.onAllNodes(SemanticsMatcher("channel '$name'") { texts(it).getOrNull(1) == name && it.config.contains(SemanticsActions.OnClick) }).onFirst()
    private fun scrollTo(tag: String, name: String): SemanticsNodeInteraction {
        rule.onNodeWithTag(tag).performScrollToNode(hasText(name)); rule.waitForIdle(); return row(name)
    }
    private fun channelsShown(): List<String> =
        rule.onAllNodes(SemanticsMatcher("channel") { it.config.contains(SemanticsActions.OnClick) && texts(it).size >= 2 && it.parent?.parent?.let { _ -> true } == true })
            .fetchSemanticsNodes().mapNotNull { texts(it).getOrNull(1) }
    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }

    private fun show() {
        rule.setContent {
            val cfg = LocalConfiguration.current
            val base = LocalDensity.current
            val f = ThemeMath.uiFactor(Look.uiScale, cfg.screenWidthDp, cfg.screenHeightDp)
            inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * f, base.fontScale)) {
                LiveTab(live, live.groupBy { it.group }, emptyList(), onOpen = { opened = it })
            }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "locals-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun assertChan(part: String) {
        val t = title(part)
        rule.onNodeWithTag("live-channels").performScrollToNode(hasText(t)); rule.waitForIdle()
        assertTrue(part, chanCount(t) > 0)
        rule.onNodeWithTag("live-channels").performScrollToIndex(0); rule.waitForIdle()
    }
    private fun waitChannel(name: String) = rule.waitUntil(5_000) { chanCount(name) > 0 }

    @Test @Config(qualifiers = "w344dp-h882dp-port-420dpi")
    fun coverPortrait_stateMarketChannels_backUpOneLevel_keptPerPlaylist() {
        show()
        scrollTo("live-cats", "My Locals").performTouchInput { click() }
        // states A–Z (time-zone suggestions only shown on top, nothing picked for the user)
        rule.onNodeWithText("Choose your market").assertDoesNotExist()
        rule.onNodeWithTag("locals-picker").performScrollToNode(hasText("Alabama")); rule.waitForIdle()
        shot("cover-port-1-states")
        scrollTo("locals-picker", "Illinois").performTouchInput { click() }
        rule.waitForIdle()
        rule.onNodeWithText("Choose your market").assertExists()
        shot("cover-port-2-illinois")
        scrollTo("locals-picker", "Chicago").performTouchInput { click() }
        waitChannel(title("NBC 5 CHICAGO WMAQ"))
        for (p in listOf("ABC 7 CHICAGO", "CBS 2", "FOX 32", "WGN 9", "WTTW", "TELEMUNDO CHICAGO")) assertChan(p)
        assertEquals(0, chanCount(title("NBC SPORTS CHICAGO")))
        assertEquals(0, chanCount(title("WNBC")))
        rule.onNodeWithText("Chicago").assertExists()
        shot("cover-port-3-chicago")
        // a channel plays by tap
        rule.onAllNodes(SemanticsMatcher("ch") { texts(it).getOrNull(1) == title("FOX 32") }).onFirst().performTouchInput { click() }
        rule.waitForIdle()
        assertTrue(opened!!.title.contains("FOX 32"))
        // Back: market → Illinois' markets → states → categories
        back(); row("‹ Illinois").assertExists(); rule.onNodeWithText("Choose your market").assertExists()
        back(); rule.onNodeWithText("Choose your market").assertDoesNotExist(); scrollTo("locals-picker", "Illinois").assertExists()
        back(); rule.onNodeWithTag("locals-picker").assertDoesNotExist(); rule.onNodeWithTag("live-cats").assertExists()
        // kept per playlist: after a restart "My Locals" opens Chicago directly
        assertEquals(602, LocalsStore.myMarket)
        LocalsStore.resetForTest(); LocalsStore.init(ctx)
        assertEquals(602, LocalsStore.myMarket)
        scrollTo("live-cats", "My Locals: Chicago").performTouchInput { click() }
        waitChannel(title("NBC 5 CHICAGO WMAQ"))
        shot("cover-port-4-reopen-chicago")
        // another playlist has its own (none yet)
        LocalsStore.scopeOf = { "other-playlist" }
        try { LocalsStore.init(ctx); assertNull(LocalsStore.myMarket) } finally { LocalsStore.scopeOf = { PlaylistStore.lists.getOrNull(PlaylistStore.active)?.url?.trim()?.hashCode()?.toString() ?: "0" } }
    }

    @Test @Config(qualifiers = "w832dp-h750dp-land-420dpi")
    fun innerLandscape_multiStateMarket_underEachState_andCategoryLocals() {
        show()
        scrollTo("live-cats", "My Locals").performTouchInput { click() }
        scrollTo("locals-picker", "Virginia").performTouchInput { click() }
        scrollTo("locals-picker", "Washington, D.C.").assertExists()
        shot("inner-land-1-virginia")
        row("Washington, D.C.").performTouchInput { click() }
        waitChannel(title("WJLA"))
        for (p in listOf("WJLA", "NBC 4 WASHINGTON", "WUSA", "WTTG", "WDCW")) assertChan(p)
        shot("inner-land-2-washington")
        back(); back()
        for (st in listOf("Maryland", "West Virginia", "District of Columbia")) {
            scrollTo("locals-picker", st).performTouchInput { click() }
            scrollTo("locals-picker", "Washington, D.C.").assertExists()
            back()
        }
        // Dallas: the provider's "US LOCALS | DALLAS" category names the city, its "ABC 8 HD" rows count
        scrollTo("locals-picker", "Texas").performTouchInput { click() }
        scrollTo("locals-picker", "Dallas–Fort Worth").performTouchInput { click() }
        waitChannel(title("ABC 8"))
        assertChan("KERA")
        shot("inner-land-3-dallas")
    }

    @Test @Config(qualifiers = "w750dp-h832dp-port-420dpi")
    fun innerPortrait_emptyMarketFriendlyMessage_andSaveMarketToFavorites() {
        show()
        scrollTo("live-cats", "My Locals").performTouchInput { click() }
        scrollTo("locals-picker", "Montana").performTouchInput { click() }
        scrollTo("locals-picker", "Glendive").performTouchInput { click() }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("No local channels found for this market").fetchSemanticsNodes().isNotEmpty() }
        shot("inner-port-1-glendive-empty")
        back()
        // the star on a market row saves it to Favorites (shown as a category and under Favorites)
        rule.onAllNodesWithTag("star", useUnmergedTree = true).onFirst().performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(1, LocalsStore.saved.size)
        val code = LocalsStore.saved.first()
        assertTrue(rule.onAllNodes(SemanticsMatcher("saved") { texts(it).firstOrNull()?.startsWith("Locals – ") == true }).fetchSemanticsNodes().isNotEmpty())
        shot("inner-port-2-saved")
        LocalsStore.resetForTest(); LocalsStore.init(ctx)
        assertEquals(listOf(code), LocalsStore.saved)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun tvDpad_pickStateAndMarket_withRemote_backUp() {
        MobilePrefs.mobile = false
        show()
        rule.runOnIdle { inputMode!!.requestInputMode(InputMode.Keyboard) }
        scrollTo("live-cats", "My Locals")
        row("My Locals").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        rule.onNodeWithTag("locals-picker").assertExists()          // focus on the category shows the picker (as other categories)
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        assertTrue(texts(rule.onNode(isFocused()).fetchSemanticsNode()).isNotEmpty())   // focus moved into the list
        scrollTo("locals-picker", "Kentucky").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNode(isFocused()).assertTextContains("Kentucky")
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        row("‹ Kentucky").assertIsFocused()                         // new level: focus on its first row
        shot("tv-1-kentucky")
        scrollTo("locals-picker", "Lexington").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        waitChannel(title("WLEX"))
        // remote: the four stations fit on the screen; no scrolling here (the focused row would be pulled back into view)
        for (p in listOf("WLEX", "WKYT", "WTVQ", "WDKY")) assertTrue(p, chanCount(title(p)) > 0)
        rule.waitUntil(5_000) { chan(title("WTVQ")).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.Focused) { null } == true }                       // the market's first channel (ABC first) has the remote focus
        shot("tv-2-lexington")
        back(); rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        row("‹ Kentucky").assertIsFocused()                         // Back: up to the state's markets, focus stays in the list
        back(); rule.waitForIdle()
        rule.onNodeWithText("Choose your market").assertDoesNotExist()
        assertEquals(541, LocalsStore.myMarket)
    }
}
