package com.ipvio.stream.locals

import androidx.test.core.app.ApplicationProvider
import com.ipvio.stream.Profiles
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LocalsStoreTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var scope = "A"

    @Before fun setUp() {
        Profiles.sp(ctx, LocalsStore.PREFS).edit().clear().commit()
        LocalsStore.scopeOf = { scope }
        LocalsStore.legacySaved = null
        LocalsStore.resetForTest()
    }

    @Test fun codesEncodeDecodeToggle() {
        var l = emptyList<Int>()
        l = LocalsCodes.toggle(l, 602); l = LocalsCodes.toggle(l, 501)
        assertEquals(listOf(501, 602), l)
        assertEquals(l, LocalsCodes.decode(LocalsCodes.encode(l)) { true })
        assertEquals(listOf(602), LocalsCodes.toggle(l, 501))
        assertEquals(listOf(602, 501), LocalsCodes.decode("602\nx\n99999\n501\n602") { it != 99999 })
    }

    @Test fun myMarketAndSavedArePerPlaylist_andSurviveRestart() {
        LocalsStore.init(ctx)
        LocalsStore.setMyMarket(ctx, 602)
        assertTrue(LocalsStore.toggleSaved(ctx, 501))
        scope = "B"; LocalsStore.init(ctx)
        assertNull(LocalsStore.myMarket); assertEquals(emptyList<Int>(), LocalsStore.saved)
        LocalsStore.setMyMarket(ctx, 511)
        // "restart": memory dropped, read back
        scope = "A"; LocalsStore.resetForTest(); LocalsStore.init(ctx)
        assertEquals(602, LocalsStore.myMarket); assertEquals(listOf(501), LocalsStore.saved)
        scope = "B"; LocalsStore.init(ctx)
        assertEquals(511, LocalsStore.myMarket)
        // unknown codes (e.g. from an older data file) are dropped
        Profiles.sp(ctx, LocalsStore.PREFS).edit().putInt("my_C", 12345).commit()
        scope = "C"; LocalsStore.init(ctx); assertNull(LocalsStore.myMarket)
    }

    @Test fun legacySavedMigratedOnce() {
        LocalsStore.legacySaved = { _, s -> if (s == "A") listOf(602, 99999) else emptyList() }
        LocalsStore.init(ctx)
        assertEquals(listOf(602), LocalsStore.saved)
        LocalsStore.legacySaved = { _, _ -> listOf(501) }      // not asked again
        LocalsStore.resetForTest(); LocalsStore.init(ctx)
        assertEquals(listOf(602), LocalsStore.saved)
    }

    @Test fun featureToggleDefaultsToHostDefault() {
        LocalsFeature.resetForTest(); LocalsFeature.defaultOn = false
        Profiles.sp(ctx, LocalsStore.PREFS).edit().remove("on").commit()
        LocalsFeature.init(ctx); assertFalse(LocalsFeature.enabled)
        LocalsFeature.resetForTest(); LocalsFeature.defaultOn = true
        LocalsFeature.init(ctx); assertTrue(LocalsFeature.enabled)
        LocalsFeature.set(ctx, false)
        LocalsFeature.resetForTest(); LocalsFeature.init(ctx); assertFalse(LocalsFeature.enabled)
        LocalsFeature.defaultOn = false; LocalsFeature.resetForTest()
    }

    @Test fun suggestionsFromTimeZone() {
        assertEquals(listOf("MI"), LocalsSuggest.states("America/Detroit"))
        assertEquals(listOf("IN"), LocalsSuggest.states("America/Indiana/Indianapolis"))
        assertEquals(listOf("KY"), LocalsSuggest.states("America/Kentucky/Louisville"))
        assertEquals(4, LocalsSuggest.states("America/New_York").size)
        assertEquals(emptyList<String>(), LocalsSuggest.states("Europe/Berlin"))
    }
}
