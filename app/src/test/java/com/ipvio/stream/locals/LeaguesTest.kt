package com.ipvio.stream.locals

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** League config (teams -> home market) and the team finder for guide titles / channel names. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LeaguesTest {
    private val dmas by lazy { DmaCatalog.parse(File("src/main/assets/locals/dmas.json").readText()) }
    private val cfg by lazy { Leagues.parse(File("src/main/assets/locals/dma_leagues.json").readText(), dmas.markets.map { it.code }.toSet()) }

    @Test fun bundledFile_usTeamsOnly_everyTeamInAKnownMarket() {
        assertEquals(listOf("NFL", "NBA", "NHL", "MLB", "MLS", "NCAAB"), cfg.leagues)
        val n = cfg.teams.groupingBy { it.league }.eachCount()
        assertEquals(32, n["NFL"]); assertEquals(29, n["NBA"]); assertEquals(25, n["NHL"]); assertEquals(29, n["MLB"]); assertEquals(27, n["MLS"])
        for (c in listOf("Toronto", "Montreal", "Vancouver", "Calgary", "Edmonton", "Ottawa", "Winnipeg"))
            assertTrue("$c is not a US team", cfg.teams.none { it.name.contains(c) })
        assertEquals(setOf("Chicago Bears", "Chicago Bulls", "Chicago Blackhawks", "Chicago Cubs", "Chicago White Sox", "Chicago Fire FC"),
            cfg.teamsIn(602).filter { !it.college }.map { it.name }.toSet())
        assertTrue(cfg.teamsIn(658).any { it.name == "Green Bay Packers" })
        val ath = cfg.teams.first { it.league == "MLB" && it.id == "ATH" }
        assertTrue(ath.verify); assertNotNull(ath.note)
        assertEquals(798, dmas.market(798)!!.code); assertTrue(cfg.teamsIn(798).isEmpty())   // small market: no teams
    }

    @Test fun validation() {
        fun bad(j: String, msg: String, known: Set<Int>? = null) {
            val e = runCatching { Leagues.parse(j, known) }.exceptionOrNull()
            assertTrue("expected '$msg', got $e", e is CatalogException && e.message!!.contains(msg))
        }
        bad("[", "not JSON")
        bad("""{"schema":"x","version":1}""", "schema")
        bad("""{"schema":"ipvio.locals.leagues/1"}""", "version")
        bad("""{"schema":"ipvio.locals.leagues/1","version":1}""", "leagues missing")
        val t = """{"id":"CHI","name":"Chicago Bears","short":"Bears","dma":602}"""
        bad("""{"schema":"ipvio.locals.leagues/1","version":1,"leagues":[{"id":"NFL","teams":[$t,$t]}]}""", "duplicate")
        bad("""{"schema":"ipvio.locals.leagues/1","version":1,"leagues":[{"id":"NFL","teams":[{"id":"X","name":"X"}]}]}""", "dma missing")
        bad("""{"schema":"ipvio.locals.leagues/1","version":1,"leagues":[{"id":"NFL","teams":[$t]}]}""", "unknown dma", setOf(501))
        assertEquals(1, Leagues.parse("""{"schema":"ipvio.locals.leagues/1","version":3,"leagues":[{"id":"nfl","teams":[$t]}]}""").teams.size)
    }

    @Test fun overrideUsedOnlyWhenValid() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val f = File(ctx.filesDir, Leagues.OVERRIDE_FILE).apply { parentFile!!.mkdirs() }
        try {
            f.writeText("""{"schema":"ipvio.locals.leagues/1","version":9,"leagues":[]}""")
            Leagues.set(null); assertEquals(9, Leagues.get(ctx).version); assertNull(Leagues.overrideError)
            f.writeText("broken")
            Leagues.set(null); assertEquals(142, Leagues.get(ctx).teams.count { !it.college }); assertNotNull(Leagues.overrideError)
        } finally { f.delete(); Leagues.set(null) }
    }

    @Test fun finder_fullNames_gameLines_leagueWords_notCommonWords() {
        fun f(s: String) = TeamFinder.find(cfg.teams, s).map { it.league + ":" + it.id }.toSet()
        assertEquals(setOf("NFL:CHI", "NFL:GB"), f("NFL Football: Chicago Bears at Green Bay Packers"))
        assertEquals(setOf("NFL:CHI", "NFL:GB"), f("Bears @ Packers"))
        assertEquals(setOf("NBA:MIA"), f("NBA Basketball: Heat"))
        assertEquals(emptySet<String>(), f("The Heat Is On"))                     // one common word alone
        assertEquals(emptySet<String>(), f("King of Queens"))
        assertEquals(setOf("MLB:CWS"), f("White Sox Pregame"))                      // two-word short name
        assertEquals(setOf("MLS:DC"), f("MLS: D.C. United vs. Inter Miami CF").filter { it == "MLS:DC" }.toSet())
        assertTrue("MLS:MIA" in f("MLS: D.C. United vs. Inter Miami CF"))
        assertTrue("NHL:VGK" in f("Golden Knights Hockey"))
        assertEquals(setOf("NHL:NYR"), f("NHL: Rangers vs. Devils").filter { it.endsWith("NYR") }.toSet())
        assertFalse("MLB:TEX" in f("NHL: Rangers vs. Devils"))                   // league word decides
        assertTrue("MLB:TEX" in f("Rangers at Astros"))                           // no league word: both Rangers
        assertEquals(emptySet<String>(), f("College Athletics Today"))
        assertEquals(emptySet<String>(), f(""))
    }
}
