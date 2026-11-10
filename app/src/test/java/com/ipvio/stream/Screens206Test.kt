package com.ipvio.stream

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * MR TV 2 2.0.6 screenshots (Robolectric, no emulator). Written to build/screens-2.0.6 and, when it exists,
 * /workspace/mrtv2-release/screens-2.0.6. Sports screens use ESPN's live answers (empty lists when offline).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class Screens206Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val data = File("/workspace/mrtv2-data")

    private fun scaled(content: @Composable () -> Unit) {
        rule.setContent {
            val cfg = LocalConfiguration.current
            val base = LocalDensity.current
            val f = ThemeMath.uiFactor(Look.uiScale, cfg.screenWidthDp, cfg.screenHeightDp)
            CompositionLocalProvider(LocalDensity provides Density(base.density * f, base.fontScale)) {
                Box(Modifier.fillMaxSize().background(Bg)) { content() }
            }
        }
    }

    private fun pass(ms: Long, until: () -> Boolean = { false }): Boolean {
        var t = 0L
        while (t < ms) { Thread.sleep(200); rule.mainClock.advanceTimeBy(200); rule.waitForIdle(); t += 200; if (until()) return true }
        return until()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dirs = listOf(File("build/screens-2.0.6"), File("/workspace/mrtv2-release/screens-2.0.6")).filter { it.parentFile?.exists() == true }
        for (d in dirs) { d.mkdirs(); File(d, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun has(t: String) = rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** Live channels of the real panel export (names and categories only), or a small made-up list. */
    private fun liveItems(): List<Item> {
        val f = File(data, "livestreams.json"); val c = File(data, "livecats.json")
        if (!f.exists() || !c.exists()) return listOf("|US| USA NEWS", "|US| USA GENERAL SPORTS", "|US| USA ENTERTAINMENT", "|AM| MEXICO", "|CA| CANADA", "FOR ADULTS")
            .flatMapIndexed { g, n -> (1..4).map { Item("i$g-$it", Kind.LIVE, "Channel $g-$it", null, "http://t.invalid/$g/$it.ts", null, n) } }
        val ca = JSONArray(c.readText())
        val cat = (0 until ca.length()).map { ca.getJSONObject(it) }.associate { it.getString("category_id") to it.getString("category_name").trim() }
        val sa = JSONArray(f.readText())
        return (0 until sa.length()).map { sa.getJSONObject(it) }.filter { !it.optString("name").startsWith("#") }.map { o ->
            Item("s" + o.optString("stream_id"), Kind.LIVE, o.optString("name"), null, "http://t.invalid/" + o.optString("stream_id") + ".ts", null, cat[o.optString("category_id")] ?: "")
        }
    }

    private fun setPlaylist(items: List<Item>) {
        val m = PlaylistStore::class.java.getDeclaredMethod("setItems", List::class.java, List::class.java)
        m.isAccessible = true
        m.invoke(PlaylistStore, items, null)
    }

    // ───── Sports ─────

    private fun hub(tab: Int, sport: String?, name: String, wait: String?) {
        HubMemory.tab = tab; HubMemory.sport = sport; HubMemory.day = 0
        scaled { SportHub(emptyList()) { _, _ -> } }
        pass(25_000) { wait == null || has(wait) }
        pass(3_000)
        shot(name)
    }

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun sportsScheduleTv() {
        MobilePrefs.mobile = false
        hub(2, null, "sports-schedule-tv", "All sports")
        rule.onNodeWithTag("sport-rail").assertExists()
        rule.onNodeWithTag("date-strip").assertExists()
        assertTrue(has("Today")); assertTrue(has("WCBB")); assertTrue(has("WNBA")); assertTrue(has("MLS"))
    }

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun sportsLiveTv() { MobilePrefs.mobile = false; hub(1, null, "sports-live-tv", null); rule.onNodeWithTag("sport-rail").assertExists() }

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun sportsTeamsTv() { MobilePrefs.mobile = false; hub(4, "nfl", "sports-teams-tv", null); rule.onNodeWithTag("sport-rail").assertExists() }

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun sportsOverviewTv() { MobilePrefs.mobile = false; hub(0, null, "sports-overview-tv", null); rule.onNodeWithTag("sport-rail").assertExists() }

    @Test @Config(qualifiers = "w411dp-h891dp-port-420dpi")
    fun sportsPhoneChips() {
        MobilePrefs.mobile = true
        hub(2, null, "sports-schedule-phone-chips", "All sports")
        rule.onNodeWithTag("sport-chips").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("sport-rail").fetchSemanticsNodes().size)
    }

    // ───── Renewal ─────

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun renewalPopupTv() {
        MobilePrefs.mobile = false
        scaled { RenewPopup(1794675600L) {} }   // 11/14/2026
        pass(600)
        assertTrue(has("OK, watch TV")); assertTrue(has("606-657-1545")); assertTrue(has("Cash App \$ApexKingDigital"))
        shot("renewal-popup-tv")
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-420dpi")
    fun renewalPopupPhone() { MobilePrefs.mobile = true; scaled { RenewPopup(1794675600L) {} }; pass(600); shot("renewal-popup-phone") }

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun subscriptionEndedTv() {
        MobilePrefs.mobile = false
        scaled { SubscriptionEnded({}, {}) }
        pass(600)
        assertTrue(has("Change login")); assertTrue(has("Retry"))
        shot("subscription-ended-tv")
    }

    // ───── Live TV categories ─────

    private fun liveTab(name: String) {
        val items = liveItems()
        val groups = LinkedHashMap<String, List<Item>>()
        for (i in items) groups[i.group] = (groups[i.group] ?: emptyList()) + i
        LiveMemory.setSel(ctx, "ALL")
        scaled { LiveTab(items, groups, emptyList(), onOpen = {}) }
        pass(1500)
        val tag = Regex("""\|[A-Z]{2,4}\|""")
        assertFalse("raw |XX| tag on screen", rule.onAllNodes(SemanticsMatcher("raw tag") { n ->
            n.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text) &&
                n.config[androidx.compose.ui.semantics.SemanticsProperties.Text].any { tag.containsMatchIn(it.text) }
        }).fetchSemanticsNodes().isNotEmpty())
        assertTrue(has("United States"))
        shot(name)
        // further down: the other countries A–Z with flags, Adults last
        runCatching {
            rule.onNodeWithTag("live-cats").performScrollToNode(hasText("Canada", substring = true)); pass(600)
            shot("$name-countries")
        }
    }

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun liveTvCategoriesTv() { MobilePrefs.mobile = false; liveTab("live-tv-categories-tv") }

    @Test @Config(qualifiers = "w411dp-h891dp-port-420dpi")
    fun liveTvCategoriesPhone() { MobilePrefs.mobile = true; liveTab("live-tv-categories-phone") }

    // ───── Settings ─────

    private fun settings(page: Int, name: String, scrollTo: String? = null) {
        SettingsNav.openPage = page
        scaled { SettingsHub({}, {}) }
        pass(2500)
        if (scrollTo != null) {
            val clickable = rule.onAllNodes(hasText(scrollTo, substring = true) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
            runCatching {
                (if (clickable) rule.onAllNodes(hasText(scrollTo, substring = true) and hasClickAction()) else rule.onAllNodesWithText(scrollTo, substring = true)).onFirst().performScrollTo()
                pass(400)
            }
        }
        shot(name)
    }

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun countryHideSettingsTv() {
        MobilePrefs.mobile = false
        setPlaylist(liveItems())
        settings(7, "settings-hide-content-groups-tv", "Countries in Live TV")
        assertTrue(has("United States"))
    }

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun settingsMenuTv() {
        MobilePrefs.mobile = false
        settings(-1, "settings-menu-tv")
        assertTrue(has("Hide content groups"))
        assertFalse(has("Letterboxd"))
    }

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun aboutTv() {
        MobilePrefs.mobile = false
        Renewal.expSec = 1794675600L; Renewal.known = true
        settings(18, "settings-about-tv", "Renew MR TV")
        assertTrue(has("Renew MR TV")); assertFalse(has("GitHub. "))
    }

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun accountTv() {
        MobilePrefs.mobile = false
        Renewal.expSec = 1794675600L; Renewal.known = true      // as read from the panel: 11/14/2026
        settings(9, "settings-account-tv")
        assertTrue(has("Expires: 11/14/2026"))
    }

    /** No picture: the send button sits below the fold and the page does not scroll in Robolectric. */
    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun diagnosticsTv() {
        MobilePrefs.mobile = false
        SettingsNav.openPage = 14
        scaled { SettingsHub({}, {}) }
        pass(2500)
        assertTrue(has("Send report to MR TV"))
    }
}
