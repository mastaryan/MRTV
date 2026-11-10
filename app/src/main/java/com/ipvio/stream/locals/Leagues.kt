package com.ipvio.stream.locals

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Teams by home market ("My teams"): assets/locals/dma_leagues.json (schema "ipvio.locals.leagues/1", built by
 * tools/locals/gen_leagues.py). US teams only; `dma` is the Nielsen code of the home market (see [DmaCatalog]).
 * Pro leagues (NFL, NBA, NHL, MLB, MLS) and college leagues (`"college": true`, e.g. NCAAB = men's college basketball,
 * where `name` is school + mascot, `short` the school, plus `mascot`, `conf` and `aliases` such as "UK" or "UConn").
 * Entries marked `verify` are uncertain (a team between homes, a recent rename). An optional override with the same
 * schema in the files dir ([OVERRIDE_FILE]) is used only if it parses and validates.
 */
data class Team(
    val league: String, val id: String, val name: String, val short: String, val dma: Int, val verify: Boolean = false, val note: String? = null,
    val college: Boolean = false, val mascot: String? = null, val conf: String? = null, val aliases: List<String> = emptyList(),
)

class LeagueConfig(val version: Int, val teams: List<Team>, private val names: Map<String, String> = emptyMap()) {
    val leagues: List<String> = teams.map { it.league }.distinct()
    /** Display name of a league ("College Basketball"; pro leagues: their id). */
    fun leagueName(id: String): String = names[id] ?: id
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
        val names = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val l = arr.getJSONObject(i)
            val lg = l.optString("id").trim().uppercase()
            if (lg.isEmpty()) throw CatalogException("league #$i: id missing")
            l.optString("name").trim().takeIf { it.isNotEmpty() }?.let { names[lg] = it }
            val college = l.optBoolean("college", false)
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
                val aliases = t.optJSONArray("aliases")?.let { a -> (0 until a.length()).map { a.getString(it).trim() }.filter { it.isNotEmpty() } }.orEmpty()
                out.add(Team(lg, id, name, t.optString("short").trim().ifEmpty { name }, dma, t.optBoolean("verify", false), t.optString("note").ifBlank { null },
                    college, t.optString("mascot").trim().ifEmpty { null }, t.optString("conf").trim().ifEmpty { null }, aliases))
            }
        }
        return LeagueConfig(version, out, names)
    }
}

/** Words that mark men's college basketball, in show titles, channel names and categories ("NCAAB", "March Madness" …). */
object CollegeHoops {
    /** Case-insensitive pattern (no flags inside, so it can be embedded in other patterns). */
    const val PATTERN = """\b(ncaab|ncaa\s?m?bb|ncaa\s?(men'?s\s?)?basketball|(men'?s\s?)?college\s?basketball|college\s?hoops|march\s?madness|final\s?four)\b"""
    val REGEX = Regex(PATTERN, RegexOption.IGNORE_CASE)
    fun matches(text: String) = REGEX.containsMatchIn(text)
}

/** Finds teams in a guide title or channel name. Only the visible text is used (no outside lookups). */
object TeamFinder {
    private val LEAGUE_WORD = Regex(" (NFL|NBA|WNBA|NHL|MLB|MLS|FOOTBALL|BASKETBALL|HOCKEY|BASEBALL|SOCCER) ")
    private val PRO_WORD = Regex(" (NFL|NBA|WNBA|NHL|MLB|MLS) ")
    /** Another sport (or the women's game): school names alone then do not mean men's basketball. */
    private val OTHER_SPORT = Regex(" (FOOTBALL|NCAAF|HOCKEY|BASEBALL|SOFTBALL|SOCCER|VOLLEYBALL|LACROSSE|WRESTLING|WOMEN|WOMEN S|WOMENS|NCAAW|WBB) ")
    private val GAME = Regex(" (AT|VS|V) ")

    /** [text] is normalized (see [LocalMatcher.norm]): words separated by single spaces, padded. */
    private fun has(text: String, phrase: String): Boolean = LocalMatcher.norm(phrase).trim().let { it.isNotEmpty() && text.contains(" $it ") }

    /**
     * Teams mentioned in [text].
     * Pro: the full name ("Chicago Bears"), the short name when it is more than one word, or a one-word short name
     * with its league word or in a game line ("Bears at Packers"); one-word names alone ("Heat", "Kings") do not count.
     * College ([Team.college]): school + mascot ("Kentucky Wildcats") always; the school, mascot or an alias
     * ("Kentucky", "Wildcats", "UK") only when the text is clearly college basketball ("NCAA Basketball", "March
     * Madness" …, see [CollegeHoops]) or is a game line with two named sides ("Kentucky vs. Duke"), and not when it
     * names a pro league or another sport. The longest name wins: "Indiana State" is not also "Indiana", "Miami (OH)"
     * is not "Miami". A shared mascot ("Wildcats") stands for every school with it; narrow down by market if needed.
     */
    fun find(teams: List<Team>, text: String): List<Team> {
        val t = LocalMatcher.norm(text)
        if (t.isBlank()) return emptyList()
        val collegeHint = CollegeHoops.matches(text)
        val pro = teams.filter { !it.college }
        val col = teams.filter { it.college }
        val found = HashSet<Team>()
        if (pro.isNotEmpty()) {
            val league = LEAGUE_WORD.find(t)?.groupValues?.get(1)
            val game = '@' in text || GAME.containsMatchIn(t)
            for (tm in pro) {
                // one-word names need a hint: the league word if there is one (so "Rangers" in an NHL title is not Texas), else a game line;
                // "Basketball" in a college title is not the NBA
                val hint = if (league != null) leagueFits(league, tm.league) && !(collegeHint && league == "BASKETBALL") else game && !collegeHint
                if ((tm.name.contains(' ') && has(t, tm.name)) || (tm.short.contains(' ') && has(t, tm.short)) || (hint && (has(t, tm.short) || has(t, tm.name))))
                    found.add(tm)
            }
        }
        if (col.isNotEmpty()) found.addAll(findCollege(col, pro, t, text, collegeHint))
        return teams.filter { it in found }
    }

    private class Hit(val team: Team, val start: Int, val end: Int, val strong: Boolean)

    private fun findCollege(col: List<Team>, pro: List<Team>, t: String, raw: String, collegeHint: Boolean): List<Team> {
        val hits = ArrayList<Hit>()
        for (tm in col) {
            val names = listOf(tm.name to true) + (listOf(tm.short) + listOfNotNull(tm.mascot) + tm.aliases).map { it to false }
            for ((n, strong) in names) {
                val p = " " + LocalMatcher.norm(n).trim() + " "
                if (p.isBlank()) continue
                var at = t.indexOf(p)
                while (at >= 0) { hits.add(Hit(tm, at + 1, at + p.length - 1, strong)); at = t.indexOf(p, at + 1) }
            }
        }
        if (hits.isEmpty()) return emptyList()
        // the longest name wins: a hit inside a longer one ("INDIANA" in "INDIANA STATE") is dropped
        val kept = hits.filter { h -> hits.none { o -> o.end - o.start > h.end - h.start && o.start <= h.start && o.end >= h.end } }
        val proNames = pro.flatMap { listOf(it.short, it.name) }.map { LocalMatcher.norm(it).trim() }.toSet()
        val blocked = PRO_WORD.containsMatchIn(t) || OTHER_SPORT.containsMatchIn(t)
        // "Basketball" without a pro league word (blocked above) counts as college here
        val clear = collegeHint || t.contains(" BASKETBALL ") || t.contains(" HOOPS ")
        val weakOk = !blocked && (clear || (('@' in raw || GAME.containsMatchIn(t)) && kept.map { it.start to it.end }.distinct().size >= 2))
        return kept.filter { h ->
            h.strong || (weakOk && (clear || t.substring(h.start, h.end) !in proNames))   // "Cardinals at Rams" without a college word is the NFL
        }.map { it.team }.distinct()
    }

    private fun leagueFits(word: String, league: String) = word == league || when (league) {
        "NFL" -> word == "FOOTBALL"; "NBA" -> word == "BASKETBALL"; "NHL" -> word == "HOCKEY"
        "MLB" -> word == "BASEBALL"; "MLS" -> word == "SOCCER"; else -> false
    }
}
