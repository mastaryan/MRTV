package com.ipvio.stream

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import com.ipvio.stream.locals.LocalsFeature
import com.ipvio.stream.locals.LocalsStore

/**
 * MR TV 2: touch in Live TV, driven like a finger (taps and long-presses on the rows), at Samsung Galaxy Z Fold 7 sizes
 * (cover 904 x 2316 px, inner 1968 x 2184 px, 420 dpi). Screenshots go to app/build/screens/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class LiveTabTouchTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun ch(id: Int, name: String, group: String) =
        Item("t$id", Kind.LIVE, name, null, "http://test.invalid/ch$id.m3u8", null, group, tvgId = "c$id")

    private val sports = listOf("US: ESPN", "US: ESPN 2", "US: FS1", "US: NBA TV", "US: Golf Channel").mapIndexed { i, n -> ch(100 + i, n, "USA General Sports") }
    private val news = listOf("US: CNN", "US: FOX News", "US: MSNBC").mapIndexed { i, n -> ch(200 + i, n, "USA News") }
    private val locals = listOf("US: ABC 7 CHICAGO", "US: NBC 5 Chicago WMAQ", "CHICAGO CBS 2 HD", "WGN 9 Chicago", "US: ABC 7 New York WABC", "US: NBC 4 New York WNBC")
        .mapIndexed { i, n -> ch(300 + i, n, "USA Entertainment") }
    private val live = sports + news + locals
    private val groups = linkedMapOf("USA General Sports" to sports, "USA News" to news, "USA Entertainment" to locals)
    private var opened: Item? = null
    private var inputMode: androidx.compose.ui.input.InputModeManager? = null
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun setUp() {
        MobilePrefs.mobile = true          // a phone (touch), as on the Fold
        LiveMemory.setSel(ctx, "ALL")
        LocalsFeature.set(ctx, true)
        resetMemory()
    }

    private fun texts(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
        (if (n.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)) n.config[androidx.compose.ui.semantics.SemanticsProperties.Text] else emptyList()).map { it.text }
    /** A row whose first text is [name] (category / city / state rows). */
    private fun row(name: String) = rule.onAllNodes(SemanticsMatcher("row '$name'") {
        texts(it).firstOrNull() == name &&
            it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }).onFirst()
    /** A channel row (logo initials, then the name). */
    private fun chan(name: String) = rule.onAllNodes(SemanticsMatcher("channel '$name'") {
        texts(it).getOrNull(1) == name
    }).onFirst()
    private fun chanCount(name: String) = rule.onAllNodes(SemanticsMatcher("channel '$name'") {
        texts(it).getOrNull(1) == name
    }).fetchSemanticsNodes().size
    /** Scroll the list that contains [inList] until [name] is composed, then return its row. */
    private fun scrollRow(tag: String, name: String): SemanticsNodeInteraction {
        rule.onNodeWithTag(tag).performScrollToNode(hasText(name))
        rule.waitForIdle()
        return row(name)
    }

    /** Same display scaling as App(): the TV design drawn to fit the phone. */
    private fun show(items: List<Item> = live, g: Map<String, List<Item>> = groups) {
        rule.setContent {
            val cfg = LocalConfiguration.current
            val base = LocalDensity.current
            val f = ThemeMath.uiFactor(Look.uiScale, cfg.screenWidthDp, cfg.screenHeightDp)
            inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * f, base.fontScale)) {
                val byId = remember(items) { items.associateBy { it.id } }
                val favs = Library.favs.mapNotNull { byId[it] }
                LiveTab(items, g, favs, onOpen = { opened = it })
            }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun minTouchPx() = 48f * ctx.resources.displayMetrics.density

    private fun assertTouchTarget(text: String) {
        val h = row(text).fetchSemanticsNode().size.height
        assertTrue("touch target for '$text' is ${h}px, needs >= ${minTouchPx()}px (48 dp)", h >= minTouchPx() - 1)
    }

    @Test @Config(qualifiers = "w882dp-h344dp-land-420dpi")
    fun coverLandscape_tapCategorySelectsIt_andChannelsAreTappable() {
        show()
        shot("cover-land-1-start")
        scrollRow("live-cats", "USA General Sports").performTouchInput { click() }
        rule.waitForIdle()
        chan("US: ESPN 2").assertExists()
        assertEquals(0, chanCount("US: CNN"))
        shot("cover-land-2-general-sports")
        assertTouchTarget("USA General Sports")
        scrollRow("live-cats", "USA News").performTouchInput { click() }
        rule.waitForIdle()
        chan("US: CNN").assertExists()
        assertEquals(0, chanCount("US: ESPN 2"))
        chan("US: CNN").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals("US: CNN", opened?.title)
    }

    @Test @Config(qualifiers = "w882dp-h344dp-land-420dpi")
    fun longPressChannelOpensMenu_andStarSavesFavorite_persisted() {
        show()
        scrollRow("live-cats", "USA General Sports").performTouchInput { click() }
        rule.waitForIdle()
        chan("US: FS1").performTouchInput { longClick() }
        rule.waitForIdle()
        shot("cover-land-3-channel-menu")
        rule.onNodeWithText("Add to favorites", substring = true, ignoreCase = true).performTouchInput { click() }
        rule.waitForIdle()
        assertTrue(Library.isFav("t102"))
        // persisted: a fresh read of the preferences (as after a restart) still has it
        val stored = Profiles.sp(ctx, "ipvio_lib").getString("favs", "") ?: ""
        assertTrue(stored.split("\n").contains("t102"))
        Library.toggleFav(ctx, "t102")
        assertFalse(Library.isFav("t102"))
    }

    @Test @Config(qualifiers = "w882dp-h344dp-land-420dpi")
    fun usaLocals_saveWholeMarket_andChannelInside_recalledAfterRestartAndReload() {
        show()
        scrollRow("live-cats", "My Locals").performTouchInput { click() }
        rule.waitForIdle()
        shot("cover-land-4-locals-states")
        scrollRow("locals-picker", "Illinois").performTouchInput { click() }
        rule.waitForIdle()
        row("Chicago").performTouchInput { click() }
        rule.waitForIdle()
        rule.waitUntil(5_000) { chanCount("US: ABC 7 CHICAGO") > 0 }
        assertEquals(0, chanCount("US: ABC 7 New York WABC"))   // only Chicago's stations
        shot("cover-land-5-chicago")
        rule.onNodeWithText("Save market").performTouchInput { click() }
        rule.waitForIdle()
        assertTrue(LocalsStore.isSaved(602))
        rule.onNodeWithText("Locals – Chicago").assertExists()                   // pinned in the category list
        // a single channel inside the city: long-press -> Add to favorites
        rule.onNodeWithTag("live-channels").performScrollToNode(hasText("WGN 9 Chicago")); rule.waitForIdle()
        chan("WGN 9 Chicago").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Add to favorites", substring = true, ignoreCase = true).performTouchInput { click() }
        rule.waitForIdle()
        assertTrue(Library.isFav("t303"))
        // persisted per playlist
        val sp = Profiles.sp(ctx, "ipvio_locals")
        assertTrue(sp.all.values.any { (it as? String)?.split("\n")?.contains("602") == true })
    }

    @Test @Config(qualifiers = "w882dp-h344dp-land-420dpi")
    fun favorites_showSavedMarketAndGroupedChannels_afterRestartAndReload() {
        LocalsStore.init(ctx); if (!LocalsStore.isSaved(602)) LocalsStore.toggleSaved(ctx, 602)
        if (!Library.isFav("t303")) Library.toggleFav(ctx, "t303")
        if (!Library.isFav("t100")) Library.toggleFav(ctx, "t100")
        // "restart": in-memory state dropped, read back from storage
        resetMemory()
        // "reload": new item objects with the same stable ids (fresh playlist download)
        val reloaded = live.map { it.copy() }
        LiveMemory.setSel(ctx, FAV_CAT)
        show(reloaded, reloaded.groupBy { it.group })
        scrollRow("live-cats", "Favorites").performTouchInput { click() }
        rule.waitForIdle()
        shot("cover-land-6-favorites")
        rule.onNodeWithText("Saved markets").assertExists()
        rule.onAllNodesWithText("Locals – Chicago").onFirst().assertExists()
        rule.onNodeWithTag("live-channels").performScrollToNode(hasText("WGN 9 Chicago")); rule.waitForIdle()
        chan("WGN 9 Chicago").assertExists()
        rule.onNodeWithTag("live-channels").performScrollToNode(hasText("Other favorites")); rule.waitForIdle()
        rule.onNodeWithText("Other favorites").assertExists()
        rule.onNodeWithTag("live-channels").performScrollToNode(hasText("US: ESPN")); rule.waitForIdle()
        chan("US: ESPN").assertExists()
        rule.onNodeWithTag("live-channels").performScrollToIndex(0); rule.waitForIdle()
        // open the saved city from Favorites
        rule.onAllNodes(SemanticsMatcher("city row") { texts(it).firstOrNull() == "Locals – Chicago" && it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) })[1].performTouchInput { click() }
        rule.waitForIdle()
        rule.waitUntil(5_000) { chanCount("CHICAGO CBS 2 HD") > 0 }
        // remove the city the same way (long-press the pinned row)
        row("Locals – Chicago").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Remove market from favorites").performTouchInput { click() }
        rule.waitForIdle()
        assertFalse(LocalsStore.isSaved(602))
        Library.toggleFav(ctx, "t303"); Library.toggleFav(ctx, "t100")
    }

    @Test @Config(qualifiers = "w344dp-h882dp-port-420dpi")
    fun coverPortrait_categoriesThenChannelsPage() {
        show()
        shot("cover-port-1-categories")
        assertTouchTarget("USA General Sports")
        scrollRow("live-cats", "USA General Sports").performTouchInput { click() }
        rule.waitForIdle()
        chan("US: ESPN 2").assertExists()
        shot("cover-port-2-channels")
        row("Categories").performTouchInput { click() }
        rule.waitForIdle()
        rule.onNodeWithText("USA News").assertExists()
    }

    @Test @Config(qualifiers = "w750dp-h832dp-port-420dpi")
    fun innerPortrait_tapCategory() {
        show()
        scrollRow("live-cats", "USA General Sports").performTouchInput { click() }
        rule.waitForIdle()
        chan("US: ESPN 2").assertExists()
        shot("inner-port-1-general-sports")
        assertTouchTarget("USA General Sports")
    }

    @Test @Config(qualifiers = "w832dp-h750dp-land-420dpi")
    fun innerLandscape_tapCategory_andLocalsBrowser() {
        show()
        scrollRow("live-cats", "USA General Sports").performTouchInput { click() }
        rule.waitForIdle()
        chan("US: ESPN 2").assertExists()
        shot("inner-land-1-general-sports")
        scrollRow("live-cats", "My Locals").performTouchInput { click() }
        rule.waitForIdle()
        scrollRow("locals-picker", "Illinois").performTouchInput { click() }
        rule.waitForIdle()
        shot("inner-land-2-illinois")
        assertTouchTarget("Chicago")
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun tvDpad_unchanged_focusSelects_rightGoesToChannels_menuKeyOpensMenuOnce() {
        MobilePrefs.mobile = false
        show()
        // remote: focus selects (as before); no tap anywhere
        rule.runOnIdle { inputMode!!.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
        scrollRow("live-cats", "USA News")
        row("USA News").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        rule.waitForIdle()
        row("USA News").assertIsFocused()
        chan("US: CNN").assertExists()
        assertEquals(0, chanCount("US: ESPN 2"))
        rule.onNode(isFocused()).performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
        rule.waitForIdle()
        chan("US: CNN").assertIsFocused()
        rule.onNode(isFocused()).performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Menu) }
        rule.waitForIdle()
        rule.onAllNodesWithText("Add to favorites", substring = true, ignoreCase = true).assertCountEquals(1)
        shot("tv-dpad-menu")
        // OK on the channel opens it (no menu, no double action)
        rule.onNode(isFocused()).performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Back) }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Add to favorites", substring = true, ignoreCase = true).fetchSemanticsNodes().size <= 1)
    }

    private fun resetMemory() {
        Library.favs.clear(); Library.watch.clear(); Library.init(ctx)
        LocalsStore.resetForTest(); LocalsStore.init(ctx)
    }
}
