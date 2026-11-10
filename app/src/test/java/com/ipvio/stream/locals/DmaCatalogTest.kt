package com.ipvio.stream.locals

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The bundled Nielsen market file and its parser / validator (org.json needs the Android runtime: Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DmaCatalogTest {
    private val json get() = File("src/main/assets/locals/dmas.json").readText()
    private val cat by lazy { DmaCatalog.parse(json) }

    @Test fun all210MarketsAndEveryStatePlusDcHasOne() {
        assertEquals(210, cat.markets.size)
        assertEquals(210, cat.markets.map { it.code }.toSet().size)
        assertEquals((1..210).toList(), cat.markets.map { it.rank }.sorted())
        assertEquals(51, cat.states.size)                      // 50 states + DC
        for (s in cat.states) assertTrue("${s.name} has no market", cat.marketsIn(s.code).isNotEmpty())
        assertEquals(501, cat.markets.first { it.rank == 1 }.code)
        assertEquals("New York", cat.market(501)!!.name)
    }

    @Test fun multiStateMarketsAppearUnderEachState() {
        val dc = cat.market(511)!!
        assertEquals("Washington, D.C.", dc.name)
        for (s in listOf("DC", "MD", "VA", "WV")) assertTrue(s, dc in cat.marketsIn(s))
        val ny = cat.market(501)!!
        for (s in listOf("NY", "NJ", "CT", "PA")) assertTrue(s, ny in cat.marketsIn(s))
        // states without a market of their own still list their neighbours' markets
        assertTrue(cat.marketsIn("NJ").any { it.code == 504 })   // Philadelphia
        assertTrue(cat.marketsIn("DE").any { it.code == 504 })
    }

    @Test fun marketsAreAlphabeticalAndStatesAlphabetical() {
        for (s in cat.states) { val n = cat.marketsIn(s.code).map { it.name.lowercase() }; assertEquals(s.code, n.sorted(), n) }
        assertEquals(cat.states.map { it.name.lowercase() }.sorted(), cat.states.map { it.name.lowercase() })
        assertEquals("Alabama", cat.states.first().name)
        assertEquals("Wyoming", cat.states.last().name)
        // markets every state needs, all of them, not just a top 5
        assertTrue(cat.marketsIn("TX").size >= 19)
        assertTrue(cat.marketsIn("IL").map { it.name }.containsAll(listOf("Chicago", "Rockford", "Peoria–Bloomington")))
    }

    @Test fun sameCityNamesAreTellApart() {
        val names = cat.markets.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue("Columbus, OH" in names); assertTrue("Portland, OR" in names); assertTrue("Jackson, MS" in names); assertTrue("Jackson, TN" in names)
    }

    @Test fun stationsParsed_unknownNetworksSkipped_callSignsValid() {
        assertTrue(cat.markets.sumOf { it.stations.size } > 1500)
        val chi = cat.market(602)!!
        assertEquals(LocalNet.ABC, chi.stations.first { it.call == "WLS" }.net)
        assertTrue(cat.markets.all { m -> m.stations.all { Regex("[KW][A-Z0-9]{2,4}").matches(it.call) } })
        val withOdd = json.replace("\"net\": \"ABC\"", "\"net\": \"SOMETHING\"")
        assertTrue(DmaCatalog.parse(withOdd).market(602)!!.stations.none { it.call == "WLS" })
    }

    private fun bad(json: String, msg: String) {
        val e = runCatching { DmaCatalog.parse(json) }.exceptionOrNull()
        assertTrue("expected error with '$msg', got $e", e is CatalogException && e.message!!.contains(msg))
    }

    @Test fun validationRejectsBrokenFiles() {
        bad("{", "not JSON")
        bad("""{"schema":"other/1","version":1}""", "schema")
        bad("""{"schema":"ipvio.locals.dmas/1"}""", "version")
        bad("""{"schema":"ipvio.locals.dmas/1","version":1,"states":{"NY":"New York"},"markets":[]}""", "no markets")
        val one = """{"code":501,"rank":1,"name":"New York","states":["NY"],"home":"NY"}"""
        bad("""{"schema":"ipvio.locals.dmas/1","version":1,"states":{"NY":"New York"},"markets":[$one,$one]}""", "duplicate")
        bad("""{"schema":"ipvio.locals.dmas/1","version":1,"states":{"NY":"New York"},"markets":[{"code":5,"name":"X","states":["ZZ"]}]}""", "unknown state")
        bad("""{"schema":"ipvio.locals.dmas/1","version":1,"states":{"NY":"New York"},"markets":[{"code":5,"name":"X","states":["NY"],"home":"NJ"}]}""", "home")
        bad("""{"schema":"ipvio.locals.dmas/1","version":1,"states":{"NY":"New York"},"markets":[{"code":5,"name":"X","states":["NY"],"stations":[{"call":"abc","net":"ABC"}]}]}""", "call sign")
        val ok = DmaCatalog.parse("""{"schema":"ipvio.locals.dmas/1","version":2,"states":{"NY":"New York"},"markets":[$one]}""")
        assertEquals(2, ok.version); assertEquals(listOf("New York"), ok.marketsIn("NY").map { it.name })
    }

    @Test fun overrideFileUsedOnlyWhenValid() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val f = File(ctx.filesDir, DmaCatalog.OVERRIDE_FILE).apply { parentFile!!.mkdirs() }
        try {
            f.writeText("""{"schema":"ipvio.locals.dmas/1","version":7,"states":{"NY":"New York"},"markets":[{"code":501,"rank":1,"name":"New York","states":["NY"]}]}""")
            DmaCatalog.set(null)
            assertEquals(7, DmaCatalog.get(ctx).version)
            assertNull(DmaCatalog.overrideError)
            f.writeText("""{"schema":"nope"}""")
            DmaCatalog.set(null)
            assertEquals(210, DmaCatalog.get(ctx).markets.size)   // bundled file
            assertNotNull(DmaCatalog.overrideError)
        } finally { f.delete(); DmaCatalog.set(null) }
    }
}
