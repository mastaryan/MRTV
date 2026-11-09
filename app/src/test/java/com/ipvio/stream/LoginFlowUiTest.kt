package com.ipvio.stream

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * MR TV 2 login + TV guide step, at Fold 7 sizes. The guide tests use a local fake Xtream/XMLTV server
 * (127.0.0.1:8088 streams the guide in ~12 s, :8089 takes minutes); they are skipped when it is not running.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class LoginFlowUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun setUp() { MobilePrefs.mobile = true }

    private fun scaled(content: @Composable () -> Unit) {
        rule.setContent {
            val cfg = LocalConfiguration.current
            val base = LocalDensity.current
            val f = ThemeMath.uiFactor(Look.uiScale, cfg.screenWidthDp, cfg.screenHeightDp)
            CompositionLocalProvider(LocalDensity provides Density(base.density * f, base.fontScale)) { content() }
        }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun texts(n: SemanticsNode): List<String> =
        (if (n.config.contains(SemanticsProperties.Text)) n.config[SemanticsProperties.Text] else emptyList()).map { it.text }
    private fun button(t: String) = rule.onAllNodes(SemanticsMatcher("button '$t'") { texts(it).firstOrNull() == t && it.config.contains(SemanticsActions.OnClick) }).onFirst()

    private fun checkLogin(name: String) {
        assumeTrue("built-in server login (server.properties) needed", Rebrand.SERVER_LOCKED)
        scaled { AddPlaylistScreen {} }
        rule.waitForIdle()
        shot(name)
        val w = ctx.resources.displayMetrics.widthPixels
        val min = 48f * ctx.resources.displayMetrics.density
        val third = if (rule.onAllNodesWithText("Cancel").fetchSemanticsNodes().isNotEmpty()) "Cancel" else "Continue without playlist"
        for (b in listOf("Sign in", "Show password", third)) {
            runCatching { button(b).performScrollTo() }; rule.waitForIdle()
            val n = button(b).fetchSemanticsNode()
            assertTrue("$b is ${n.size.height}px tall, needs >= ${min}px (48 dp)", n.size.height >= min - 1)
            val cx = n.boundsInRoot.center.x
            if (!(w > 1400 && ctx.resources.configuration.orientation == 2 && !MobilePrefs.mobile)) // TV keeps the phone card on the right
                assertEquals("$b centered", w / 2f, cx, w * 0.06f)
        }
        // block does not stretch: Sign in at most ~460 dp (in the screen's own dp, after the app's scaling)
        val signW = button("Sign in").fetchSemanticsNode().size.width / ctx.resources.displayMetrics.density
        assertTrue("Sign in is $signW dp wide", signW <= 480f)
    }

    @Test @Config(qualifiers = "w344dp-h882dp-port-420dpi") fun loginCoverPortrait() = checkLogin("login-cover-port")
    @Test @Config(qualifiers = "w750dp-h832dp-port-420dpi") fun loginInnerPortrait() = checkLogin("login-inner-port")
    @Test @Config(qualifiers = "w882dp-h344dp-land-420dpi") fun loginCoverLandscape() = checkLogin("login-cover-land")

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun loginTv() { MobilePrefs.mobile = false; checkLogin("login-tv") }

    // ---- TV guide step ----
    private fun serverUp(port: Int) = runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess

    private fun setChannels() {
        val items = (1..60).map { Item("e$it", Kind.LIVE, "Ch $it", null, "http://test.invalid/$it.ts", null, "G", tvgId = "x") } +
            listOf("sports0.us", "news0.us", "wls.us", "wgn.us").mapIndexed { i, id -> Item("g$i", Kind.LIVE, "G$i", null, "http://test.invalid/g$i.ts", null, "G", tvgId = id) }
        val m = PlaylistStore::class.java.getDeclaredMethod("setItems", List::class.java, List::class.java)
        m.isAccessible = true
        m.invoke(PlaylistStore, items, null)
    }

    /** Lets real time pass (the download runs on IO threads) while the screen's clock follows. */
    private fun pass(ms: Long, until: () -> Boolean = { false }): Boolean {
        var t = 0L
        while (t < ms) { Thread.sleep(200); rule.mainClock.advanceTimeBy(200); t += 200; if (until()) return true }
        return until()
    }

    @Test @Config(qualifiers = "w344dp-h882dp-port-420dpi")
    fun epgStepWaitsForGuideShowsProgressThenEnters_andFailedRefreshKeepsGuide() {
        assumeTrue("fake XMLTV server on :8088", serverUp(8088))
        setChannels()
        PlaylistStore.epgUrls = listOf("http://127.0.0.1:8088/xmltv.php?username=a&password=b")
        Xmltv.setCustom(ctx, "")
        var entered = false
        rule.mainClock.autoAdvance = false
        scaled { EpgGateScreen { entered = true } }
        pass(3_000)
        assertTrue("guide is loading", Xmltv.running)
        assertFalse("must wait for the guide", entered)
        rule.onNodeWithText("Loading TV guide…").assertExists()
        rule.onNodeWithText("Skip").assertExists()
        pass(2_000); shot("epg-cover-port-progress")
        assertTrue("progress visible", Xmltv.bytes > 0)
        assertTrue("enters when the guide is done", pass(40_000) { entered })
        assertFalse(Xmltv.running)
        assertTrue(Xmltv.channelCount >= 4)
        assertTrue(Xmltv.has("wgn.us"))
        // a refresh that fails keeps the previous guide
        Xmltv.start(ctx, listOf("http://127.0.0.1:8088/nothing-here"), PlaylistStore.channels)
        pass(5_000) { !Xmltv.running }
        assertTrue("previous guide kept", Xmltv.has("wgn.us"))
        assertEquals("HTTP 404", Xmltv.lastError)
    }

    @Test @Config(qualifiers = "w344dp-h882dp-port-420dpi")
    fun epgErrorShowsRealMessageThenEnters() {
        assumeTrue("fake server on :8088", serverUp(8088))
        setChannels()
        PlaylistStore.epgUrls = listOf("http://127.0.0.1:8088/broken-guide")
        var entered = false
        rule.mainClock.autoAdvance = false
        scaled { EpgGateScreen { entered = true } }
        pass(1_500) { rule.onAllNodesWithText("HTTP 404", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("HTTP 404", substring = true).onFirst().assertExists()
        shot("epg-cover-port-error")
        assertFalse(entered)
        assertTrue("continues into the app after the error", pass(6_000) { entered })
    }

    @Test @Config(qualifiers = "w344dp-h882dp-port-420dpi")
    fun epgTimeoutContinuesInBackground_andSkipWorks() {
        assumeTrue("slow fake server on :8089", serverUp(8089))
        setChannels()
        PlaylistStore.epgUrls = listOf("http://127.0.0.1:8089/xmltv.php?username=a&password=b")
        var entered = false
        rule.mainClock.autoAdvance = false
        scaled { EpgGateScreen { entered = true } }
        pass(2_000)
        assertTrue(Xmltv.running); assertFalse(entered)
        // the screen's own clock runs to the timeout (real download still going)
        rule.mainClock.advanceTimeBy(EpgGateLogic.TIMEOUT_MS + 1_000)
        pass(1_000) { entered }
        assertTrue("timeout enters the app", entered)
        assertTrue("guide keeps loading in the background", Xmltv.running)
        Xmltv.start(ctx, emptyList(), emptyList())   // stop using the slow server
    }

    @Test @Config(qualifiers = "w344dp-h882dp-port-420dpi")
    fun epgSkipButton() {
        assumeTrue("slow fake server on :8089", serverUp(8089))
        setChannels()
        PlaylistStore.epgUrls = listOf("http://127.0.0.1:8089/xmltv.php?username=a&password=b")
        var entered = false
        rule.mainClock.autoAdvance = false
        scaled { EpgGateScreen { entered = true } }
        pass(1_000)
        assertTrue(48f * ctx.resources.displayMetrics.density <= button("Skip").fetchSemanticsNode().size.height + 1)
        button("Skip").performTouchInput { click() }
        pass(400) { entered }
        assertTrue(entered)
    }
}
