package com.ipvio.stream.locals

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Pro teams by home market ("My teams"): assets/locals/dma_leagues.json (schema "ipvio.locals.leagues/1", built by
 * tools/locals/gen_leagues.py). US teams only; `dma` is the Nielsen code of the home market (see [DmaCatalog]).
 * Entries marked `verify` are uncertain (a team between homes, a recent rename). An optional override with the same
 * schema in the files dir ([OVERRIDE_FILE]) is used only if it parses and validates.
 */
data class Team(val league: String, val id: String, val name: String, val short: String, val dma: Int, val verify: Boolean = false, val note: String? = null)

class LeagueConfig(val version: Int, val teams: List<Team>) {
    val leagues: List<String> = teams.map { it.league }.distinct()
    /** Teams whose home is the market, in file order (league, then team). */
    fun teamsIn(dma: Int): List<Team> = teams.filter { it.dma == dma }
}

object Leagues {
    const val SCHEMA = "ipvio.locals.leagues/1"
    const val ASSET = "locals/dma_leagues.json"
    const val OVERRIDE_FILE = "locals/dma_leagues.override.json"

    @Volatile private var cached: LeagueConfig? = null
    @Volatile var overrideError: String? = null
        private set

    fun get(c: Context): LeagueConfig = cached ?: synchronized(this) { cached ?: load(c).also { cached = it } }
    fun set(cfg: LeagueConfig?) { cached = cfg }

    private fun load(c: Context): LeagueConfig {
        val f = File(c.applicationContext.filesDir, OVERRIDE_FILE)
        overrideError = null
        if (f.isFile) {
            try { return parse(f.readText()) } catch (e: Exception) { overrideError = e.message ?: e.javaClass.simpleName }
        }
        return parse(c.applicationContext.assets.open(ASSET).bufferedReader().use { it.readText() })
    }

    /** Parses and validates; [knownDmas] (optional) = market codes that exist, e.g. from the [Catalog]. */
    fun parse(json: String, knownDmas: Set<Int>? = null): LeagueConfig {
        val o = try { JSONObject(json) } catch (e: Exception) { throw CatalogException("not JSON: " + e.message) }
        val schema = o.optString("schema")
        if (schema != SCHEMA) throw CatalogException("schema must be $SCHEMA, is '$schema'")
        val version = o.optInt("version", 0)
        if (version < 1) throw CatalogException("version missing")
        val arr = o.optJSONArray("leagues") ?: throw CatalogException("leagues missing")
        val out = ArrayList<Team>()
        for (i in 0 until arr.length()) {
            val l = arr.getJSONObject(i)
            val lg = l.optString("id").trim().uppercase()
            if (lg.isEmpty()) throw CatalogException("league #$i: id missing")
            val ids = HashSet<String>()
            val ts = l.optJSONArray("teams") ?: throw CatalogException("$lg: teams missing")
            for (j in 0 until ts.length()) {
                val t = ts.getJSONObject(j)
                val id = t.optString("id").trim()
                val name = t.optString("name").trim()
                val dma = t.optInt("dma", -1)
                if (id.isEmpty() || name.isEmpty()) throw CatalogException("$lg #$j: id or name missing")
                if (!ids.add(id)) throw CatalogException("$lg $id: duplicate id")
                if (dma <= 0) throw CatalogException("$lg $id: dma missing")
                if (knownDmas != null && dma !in knownDmas) throw CatalogException("$lg $id: unknown dma $dma")
                out.add(Team(lg, id, name, t.optString("short").trim().ifEmpty { name }, dma, t.optBoolean("verify", false), t.optString("note").ifBlank { null }))
            }
        }
        return LeagueConfig(version, out)
    }
}

/** Finds teams in a guide title or channel name. Only the visible text is used (no outside lookups). */
object TeamFinder {
    private val LEAGUE_WORD = Regex("\\b(NFL|NBA|NHL|MLB|MLS|FOOTBALL|BASKETBALL|HOCKEY|BASEBALL|SOCCER)\\b")

    /** [text] is normalized (see [LocalMatcher.norm]): words separated by single spaces, padded. */
    private fun has(text: String, phrase: String): Boolean = LocalMatcher.norm(phrase).trim().let { it.isNotEmpty() && text.contains(" $it ") }

    /**
     * Teams mentioned in [text]: the full name ("Chicago Bears"), the short name when it is more than one word or
     * a typical game line ("Bears at Packers", "Bears vs. Lions"), or the short name next to its league word.
     * One-word names alone ("Heat", "Kings", "Magic") are too common to count.
     */
    fun find(teams: List<Team>, text: String): List<Team> {
        val t = LocalMatcher.norm(text)
        if (t.isBlank()) return emptyList()
        val league = LEAGUE_WORD.find(t)?.value
        val game = '@' in text || Regex(" (AT|VS|V) ").containsMatchIn(t)
        return teams.filter { tm ->
            // one-word names need a hint: the league word if there is one (so "Rangers" in an NHL title is not Texas), else a game line
            val hint = if (league != null) leagueFits(league, tm.league) else game
            (tm.name.contains(' ') && has(t, tm.name)) || (tm.short.contains(' ') && has(t, tm.short)) ||
                (hint && (has(t, tm.short) || has(t, tm.name)))
        }
    }

    private fun leagueFits(word: String, league: String) = word == league || when (league) {
        "NFL" -> word == "FOOTBALL"; "NBA" -> word == "BASKETBALL"; "NHL" -> word == "HOCKEY"
        "MLB" -> word == "BASEBALL"; "MLS" -> word == "SOCCER"; else -> false
    }
}
