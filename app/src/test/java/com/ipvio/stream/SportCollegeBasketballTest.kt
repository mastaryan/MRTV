package com.ipvio.stream

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.zip.GZIPInputStream

/** College Basketball in the Sports Hub: league entry, ESPN address, real scoreboard / standings answers (Nov 10, 2026). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SportCollegeBasketballTest {
    private fun res(name: String) = GZIPInputStream(javaClass.getResourceAsStream("/espn/$name")!!).bufferedReader().use { it.readText() }
    private val cbb get() = SportData.league("College Basketball")!!

    @Test fun leagueNextToNba_underBasketball_popular_onByDefault() {
        val l = cbb
        assertEquals("basketball/mens-college-basketball", l.path)
        assertEquals("basketball", l.sport)
        assertEquals("College-Basketball", l.catName)
        assertTrue(SportLogic.catIndex(l.catName) >= 0)
        assertEquals(SportData.LEAGUES.indexOfFirst { it.name == "NBA" } + 1, SportData.LEAGUES.indexOf(l))
        assertEquals(listOf("NBA", "College Basketball"), SportData.LEAGUES.filter { it.sport == "basketball" }.map { it.name })
        assertEquals(SportData.POPULAR.indexOf("NBA") + 1, SportData.POPULAR.indexOf("College Basketball"))
        assertTrue(SportLeagues.on(l))                       // not in the hidden list unless the user hides it
        assertFalse(l.leaders)                               // ESPN's college statistics answer is > 20 MB
        assertEquals("https://site.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/scoreboard?dates=20261110&groups=50",
            SportData.urlFor(l, "20261110"))
        assertEquals("https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard?dates=20261110",
            SportData.urlFor(SportData.league("NBA")!!, "20261110"))
    }

    @Test fun realScoreboard_allDivisionIGames_parsed() {
        val games = SportData.parse(res("ncaab_scoreboard_20261110_groups50.json.gz"), cbb)
        assertEquals(69, games.size)                         // groups=50: all of Division I (without it ESPN sends 8)
        assertTrue(games.all { it.league == "College Basketball" && it.catName == "College-Basketball" && it.start > 0 })
        assertTrue(games.all { it.home.name.isNotBlank() && it.away.name.isNotBlank() && it.home.logo != null })
        val g = games.first { it.home.abbr == "FSU" && it.away.abbr == "FLA" }
        assertEquals("Florida State Seminoles", g.home.name)
        assertEquals("basketball", SportData.sportOf(g))
        assertNotNull(SportData.leagueLogos[cbb.path])
        // "On your channels": a guide title naming both schools
        assertTrue(SportData.matches(g, "NCAA Basketball: Florida at Florida State"))
        assertFalse(SportData.matches(g, "NBA: Magic at Heat"))
        assertEquals("College-Basketball", SportLogic.catName(SportLogic.catIndex(g.catName)))
    }

    @Test fun realStandings_conferencesAndTeams() {
        val groups = SportStandings.parse(res("ncaab_standings_3conf.json.gz"), cbb)
        assertEquals(listOf("Atlantic Coast Conference", "Big 12 Conference", "Pac-12 Conference"), groups.map { it.name })
        assertEquals(listOf(18, 16, 9), groups.map { it.rows.size })
        assertTrue(groups.flatMap { it.rows }.any { it.team.name == "Gonzaga Bulldogs" && it.team.id.isNotBlank() })
    }
}
