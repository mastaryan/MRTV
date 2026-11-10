package com.ipvio.stream.locals

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LocalMatcherTest {
    private val cat by lazy { DmaCatalog.parse(File("src/main/assets/locals/dmas.json").readText()) }
    private fun m(code: Int) = cat.market(code)!!

    @Test fun callSignOrCityPlusNetwork() {
        val chi = m(602)
        assertEquals(LocalNet.NBC, LocalMatcher.matchOne(chi, "US| NBC 5 CHICAGO WMAQ"))
        assertEquals(LocalNet.ABC, LocalMatcher.matchOne(chi, "US: ABC 7 CHICAGO"))
        assertEquals(LocalNet.CW, LocalMatcher.matchOne(chi, "US: WGN 9 HD"))
        assertEquals(LocalNet.TEL, LocalMatcher.matchOne(chi, "US: TELEMUNDO CHICAGO (WSNS)"))
        assertEquals(LocalNet.NBC, LocalMatcher.matchOne(m(541), "US: WLEX 18 NBC LEXINGTON"))
        assertEquals(LocalNet.ABC, LocalMatcher.matchOne(m(524), "US: WSB-TV ABC 2 ATLANTA"))
    }

    @Test fun nationalFeedsAreNotLocals() {
        val chi = m(602)
        for (n in listOf("US: CBS SPORTS NETWORK", "US: NBC SPORTS CHICAGO", "US: FOX NEWS", "US: NBC NEWS NOW", "US: ABC NEWS LIVE", "US: FOX SPORTS 1", "US: ESPN"))
            assertNull(n, LocalMatcher.matchOne(chi, n))
    }

    @Test fun wordLikeCallSignsNeedMoreProof() {
        val sea = m(819)
        assertNull(LocalMatcher.matchOne(sea, "US: KING OF QUEENS 24/7"))
        assertEquals(LocalNet.NBC, LocalMatcher.matchOne(sea, "US: KING 5 NBC SEATTLE"))
        assertEquals(LocalNet.NBC, LocalMatcher.matchOne(sea, "KING-TV"))
        assertEquals(LocalNet.NBC, LocalMatcher.matchOne(sea, "US: KING"))
    }

    @Test fun ambiguousCitiesNeedTheState() {
        val cmh = m(535); val csg = m(522); val hou = m(618)
        assertEquals(LocalNet.NBC, LocalMatcher.matchOne(cmh, "US: NBC 4 COLUMBUS OHIO"))
        assertNull(LocalMatcher.matchOne(cmh, "US: NBC COLUMBUS"))
        assertNull(LocalMatcher.matchOne(cmh, "US: WTVM ABC 9 COLUMBUS GA"))
        assertEquals(LocalNet.ABC, LocalMatcher.matchOne(csg, "US: WTVM ABC 9 COLUMBUS GA"))
        assertEquals(LocalNet.FOX, LocalMatcher.matchOne(hou, "US: FOX 26 HOUSTON TX"))
        assertNull(LocalMatcher.matchOne(hou, "US: FOX 26 HOUSTON"))
        assertEquals(LocalNet.ABC, LocalMatcher.matchOne(hou, "US: ABC 13 HOUSTON KTRK"))
    }

    @Test fun localsCategoryNamesTheCity() {
        val dfw = m(623)
        assertNull(LocalMatcher.matchOne(dfw, "ABC 8 HD"))
        assertEquals(LocalNet.ABC, LocalMatcher.matchIn(dfw, "ABC 8 HD", "US LOCALS | DALLAS"))
        assertNull(LocalMatcher.matchIn(dfw, "ABC 8 HD", "USA Networks"))
        assertNull(LocalMatcher.matchIn(m(602), "ABC 8 HD", "US LOCALS | DALLAS"))
    }

    @Test fun groupedInNetworkOrder_bestQualityFirst_allLocalsIncluded() {
        val chi = m(602)
        val names = listOf("US: ESPN", "US: WGN 9 HD", "US: ABC 7 CHICAGO (WLS) SD", "US| NBC 5 CHICAGO WMAQ", "US: ABC 7 CHICAGO (WLS) HD", "US: WTTW PBS CHICAGO", "CHICAGO CBS 2 HD", "US: FOX 32 CHICAGO", "US: WCIU THE U")
        val out = LocalMatcher.channels(chi, names, { it })
        assertEquals(listOf("US: ABC 7 CHICAGO (WLS) HD", "US: ABC 7 CHICAGO (WLS) SD", "US| NBC 5 CHICAGO WMAQ", "CHICAGO CBS 2 HD", "US: FOX 32 CHICAGO", "US: WGN 9 HD", "US: WTTW PBS CHICAGO", "US: WCIU THE U"), out)
    }

    @Test fun favoritesGroupedByMarket() {
        val g = LocalMatcher.groupFavorites(listOf(m(602), m(501)), listOf("US: ESPN", "US: WGN 9 HD", "US: NBC 4 NEW YORK WNBC")) { it }
        assertEquals(listOf(602, 501, null), g.map { it.first?.code })
        assertEquals(listOf("US: ESPN"), g.last().second)
    }

    /** The fake server's fixture: every market gets exactly its own channels, national look-alikes none. */
    @Test fun fixtureAcrossSeveralMarkets() {
        val fx = org.json.JSONObject(File("../tools/fake-xtream/locals_channels.json").readText())
        val cats = fx.getJSONObject("categories")
        val ch = fx.getJSONArray("channels").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { r -> r.getString(1) to cats.getString(r.getString(0)) } } }
        fun names(code: Int) = LocalMatcher.channels(m(code), ch, { it.first }, { it.second }).map { it.first }
        assertEquals(8, names(602).size)
        assertEquals(5, names(501).size)
        assertEquals(5, names(511).size)                          // Washington, D.C.
        assertEquals(4, names(541).size)                          // Lexington
        assertEquals(4, names(819).size)                          // Seattle (not "King of Queens")
        assertEquals(3, names(618).size)                          // Houston
        assertEquals(3, names(535).size)                          // Columbus, OH
        assertEquals(2, names(522).size)                          // Columbus, GA
        assertEquals(5, names(623).size)                          // Dallas: by category
        assertEquals(emptyList<String>(), names(798))             // Glendive: none on this panel
        val all = listOf(602, 501, 803, 524, 511, 541, 819, 618, 535, 522, 504, 623).flatMap { names(it) }
        assertEquals("a channel matched two markets", all.size, all.toSet().size)
        assertTrue(all.none { it.startsWith("US: FOX SPORTS") || it.contains("KING OF QUEENS") || it.contains("NEWS") })
    }
}
