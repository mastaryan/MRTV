package com.ipvio.stream.locals

import com.ipvio.stream.SportLogic
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** NCAAB in the league config, the college rules of the team finder and the sport keyword. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CollegeBasketballTest {
    private val dmas by lazy { DmaCatalog.parse(File("src/main/assets/locals/dmas.json").readText()) }
    private val cfg by lazy { Leagues.parse(File("src/main/assets/locals/dma_leagues.json").readText(), dmas.markets.map { it.code }.toSet()) }
    private val ncaab get() = cfg.teams.filter { it.league == "NCAAB" }
    private fun f(s: String) = TeamFinder.find(cfg.teams, s).map { (if (it.college) "" else it.league + ":") + it.id }.toSet()

    @Test fun league_conferences_2025_26_andMarkets() {
        assertEquals("College Basketball", cfg.leagueName("NCAAB"))
        assertEquals("NFL", cfg.leagueName("NFL"))
        assertTrue(ncaab.all { it.college && it.mascot != null && it.conf != null })
        val conf = ncaab.groupingBy { it.conf!! }.eachCount()
        assertEquals(18, conf["ACC"]); assertEquals(18, conf["Big Ten"]); assertEquals(16, conf["Big 12"])
        assertEquals(16, conf["SEC"]); assertEquals(11, conf["Big East"])
        for (id in listOf("GONZ", "MEM", "SDSU", "DAY", "VCU", "SMC")) assertNotNull(id, ncaab.firstOrNull { it.id == id })
        assertEquals(ncaab.size, ncaab.map { it.id }.toSet().size)
        fun dma(id: String) = ncaab.first { it.id == id }.dma
        assertEquals(541, dma("UK")); assertEquals(529, dma("LOU")); assertEquals(560, dma("DUKE")); assertEquals(560, dma("UNC"))
        assertEquals(533, dma("CONN")); assertEquals(881, dma("GONZ")); assertEquals(581, dma("INST")); assertEquals(515, dma("M-OH"))
        assertEquals(setOf("UK"), cfg.teamsIn(541).filter { it.college }.map { it.id }.toSet())
        assertTrue(ncaab.first { it.id == "WVU" }.verify)
        assertTrue(ncaab.first { it.id == "GONZ" }.note!!.contains("Pac-12"))
    }

    @Test fun gameLines_schoolsAndMascots() {
        assertEquals(setOf("UK", "DUKE"), f("Kentucky vs. Duke"))
        val w = f("NCAA Basketball: Wildcats at Blue Devils")
        assertTrue("DUKE" in w); assertTrue("UK" in w); assertTrue("ARIZ" in w)   // a shared mascot: every Wildcats school
        assertTrue(w.none { it.startsWith("NBA:") })
        assertEquals(setOf("UNC", "CONN", "UK"), f("College Hoops: UNC vs UConn, then UK"))
        assertEquals(setOf("UK", "LOU"), f("Kentucky at Louisville"))
        assertEquals(setOf("UK"), f("Kentucky Wildcats Pregame"))                        // full name: always
    }

    @Test fun placeNames_needBasketball_longestNameWins() {
        assertEquals(emptySet<String>(), f("Kentucky Derby"))
        assertEquals(emptySet<String>(), f("Kentucky"))
        assertEquals(emptySet<String>(), f("Discover Indiana"))
        assertEquals(setOf("UK"), f("Kentucky Basketball Show"))                        // "basketball", no pro league word
        assertEquals(setOf("IND", "INST"), f("Indiana vs. Indiana State"))
        assertEquals(setOf("INST", "DRKE"), f("Indiana State at Drake"))
        assertEquals(setOf("M-OH"), f("NCAAB: Miami (OH) RedHawks"))
        assertEquals(setOf("MIA", "M-OH"), f("Miami (FL) vs. Miami (OH)"))
        assertEquals(setOf("MIA", "DUKE"), f("Miami at Duke"))
        assertEquals(setOf("KSU", "KU"), f("Kansas State at Kansas"))
        assertEquals(emptySet<String>(), f("Miami at Boston"))                          // one side only
    }

    @Test fun otherSportsAndProLeaguesDoNotCount() {
        assertEquals(emptySet<String>(), f("College Football: Kentucky vs. Louisville"))
        assertEquals(emptySet<String>(), f("NCAA Women's Basketball: UConn vs. South Carolina"))
        assertEquals(setOf("NBA:MEM", "NBA:HOU"), f("NBA: Grizzlies at Rockets"))
        assertEquals(emptySet<String>(), f("NBA: Memphis at Houston"))
        assertEquals(setOf("NFL:ARI", "NFL:LAR", "MLB:STL"), f("Cardinals at Rams"))     // pro teams, not Louisville / VCU / Colorado State
        assertEquals(setOf("NBA:MIA", "NBA:BOS"), f("NBA Basketball: Heat at Celtics"))
        assertEquals(emptySet<String>(), f("March Madness: Bulls Highlights").filter { it.startsWith("NBA:") }.toSet())
    }

    @Test fun sportKeyword_categoriesAndChannels() {
        fun cat(title: String, ch: String = "") = SportLogic.catName(SportLogic.categoryOf(title, ch))
        for (s in listOf("NCAAB: Duke vs. UNC", "NCAA Basketball", "NCAA Men's Basketball", "College Basketball Tonight", "College Hoops Live", "March Madness 2026"))
            assertEquals(s, "College-Basketball", cat(s))
        assertEquals("College-Basketball", cat("Kentucky vs. Duke", "US: ESPN COLLEGE BASKETBALL"))
        assertEquals("Basketball", cat("NBA: Lakers vs Celtics"))
        assertTrue(SportLogic.isSportEvent("March Madness: First Round"))
        assertFalse(SportLogic.isSportEvent("Kentucky Derby Preview".replace("Derby", "Fair")))
        assertTrue(CollegeHoops.matches("US | NCAAB 01"))
        assertFalse(CollegeHoops.matches("College Station News"))
    }
}
