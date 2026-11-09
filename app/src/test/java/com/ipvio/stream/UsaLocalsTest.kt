package com.ipvio.stream

import org.junit.Assert.*
import org.junit.Test

class UsaLocalsTest {
    private fun mk(name: String) = LocalMarkets.markets.values.first { it.name == name }
    private val chi get() = mk("Chicago")
    private val lex get() = mk("Lexington")

    @Test fun nielsenTopFivePerState() {
        assertEquals(51, LocalMarkets.states.size)
        assertTrue(LocalMarkets.states.all { it.home.size <= 5 && it.markets.isNotEmpty() })
        assertEquals(listOf("Champaign–Urbana & Springfield–Decatur", "Chicago", "Peoria–Bloomington", "Quincy–Hannibal–Keokuk", "Rockford"),
            UsaLocals.markets("IL").map { it.name })
        assertEquals(listOf("Philadelphia"), UsaLocals.markets("DE").map { it.name })   // no home market: nearby
    }

    @Test fun matcherFromMrTv13() {
        assertEquals(LocalMarkets.Net.NBC, LocalMarkets.matchOne(lex, "US| WLEX 18 HD"))
        assertEquals(LocalMarkets.Net.ABC, LocalMarkets.matchOne(chi, "US: ABC 7 CHICAGO"))
        assertNull(LocalMarkets.matchOne(chi, "CBS Sports Network"))
        assertNull(LocalMarkets.matchOne(chi, "NBC Sports Chicago"))
        assertNull(LocalMarkets.matchOne(chi, "FOX News"))
    }

    @Test fun cityChannelsInNetworkOrder() {
        data class Ch(val id: String, val name: String)
        val all = listOf(Ch("1", "USA General Sports ESPN"), Ch("2", "CHICAGO CBS 2 HD"), Ch("3", "US: ABC 7 CHICAGO"), Ch("4", "WGN 9 Chicago"), Ch("5", "ABC 7 New York"))
        assertEquals(listOf("3", "2", "4"), UsaLocals.channels(chi, all, { it.id }, { it.name }).map { it.id })
    }

    @Test fun cityKeys() {
        assertEquals("CITY:3", UsaLocals.cityKey(3))
        assertEquals(3, UsaLocals.rankOf("CITY:3"))
        assertNull(UsaLocals.rankOf("CITY:99999"))
        assertNull(UsaLocals.rankOf("USA General Sports"))
        assertFalse(UsaLocals.isCity(UsaLocals.KEY))
        assertEquals("IL", UsaLocals.stateOf(3))
    }

    @Test fun savedCitiesStorage() {
        var l = emptyList<Int>()
        l = UsaLocals.toggle(l, 3); l = UsaLocals.toggle(l, 1)
        assertEquals(listOf(1, 3), l)                         // newest first
        assertEquals(listOf(1, 3), UsaLocals.decode(UsaLocals.encode(l)))
        assertEquals(listOf(3), UsaLocals.toggle(l, 1))       // remove
        assertEquals(l, UsaLocals.toggle(l, 99999))           // unknown rank ignored
        assertEquals(listOf(3, 1), UsaLocals.decode("3\n99999\nx\n1\n3"))  // garbage and duplicates dropped
        assertEquals(emptyList<Int>(), UsaLocals.decode(null))
    }

    @Test fun favoritesGroupedByCity() {
        val favs = listOf("ESPN", "US: ABC 7 CHICAGO", "ABC 7 New York", "WGN 9 Chicago")
        val g = UsaLocals.groupFavorites(listOf(3, 1), favs) { it }
        assertEquals(listOf<Int?>(3, 1, null), g.map { it.first })
        assertEquals(listOf("US: ABC 7 CHICAGO", "WGN 9 Chicago"), g[0].second)
        assertEquals(listOf("ABC 7 New York"), g[1].second)
        assertEquals(listOf("ESPN"), g[2].second)
        assertEquals(listOf<Int?>(null), UsaLocals.groupFavorites(listOf(2), listOf("ESPN")) { it }.map { it.first })
    }
}
