package com.ipvio.stream

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.TimeZone

/**
 * MR TV 2 2.0.6: sports rail, US dates, Live TV categories (against the real panel category names), phone-search
 * IP choice, renewal reminder and the scrambled server address.
 * The full panel export (/workspace/mrtv2-data) is used when it is on this machine; it is never committed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class Release206Test {
    private val ny = TimeZone.getTimeZone("America/New_York")
    /** Tue Nov 10 2026, 12:00 New York */
    private val now = 1794330000_000L
    private val data = File("/workspace/mrtv2-data")

    @Before fun load() {
        LiveCats.use(LiveCats.parse(File("src/main/assets/mrtv2-categories.json").readText()))
    }

    private fun team(n: String) = SportData.Team(n, n, n.take(3).uppercase(), null, null, false)
    private fun game(id: String, league: String) = SportData.Game(id, league, SportData.league(league)!!.catName, now / 1000, 0, "", team("Home $id"), team("Away $id"))

    // ───── sports rail ─────

    @Test fun rail_az_withAll_andNewLeagues() {
        assertEquals(listOf("All", "CBB", "CFB", "F1", "Golf", "MLB", "MLS", "NBA", "NFL", "NHL", "Soccer", "Tennis", "UFC", "WCBB", "WNBA"),
            SportRailLogic.entries().map { SportRailLogic.label(it) })
        assertEquals("basketball/womens-college-basketball", SportData.league("Women's College Basketball")!!.path)
        assertEquals("https://site.api.espn.com/apis/site/v2/sports/basketball/womens-college-basketball/scoreboard?dates=20261110&groups=50",
            SportData.urlFor(SportData.league("Women's College Basketball")!!, "20261110"))
        assertEquals("basketball/wnba", SportData.league("WNBA")!!.path)
        assertEquals("soccer/usa.1", SportData.league("MLS")!!.path)
        assertEquals("mls", SportData.league("MLS")!!.sport)
        // Teams rail: team sports only
        val ts = SportRailLogic.teamSports()
        assertTrue(ts.containsAll(listOf("cbb", "cfb", "mlb", "mls", "nba", "nfl", "nhl", "soccer", "wcbb", "wnba")))
        assertFalse(ts.any { it in setOf("f1", "golf", "tennis", "ufc") })
    }

    @Test fun rail_filtersAndCounts_nothingGrouped() {
        val games = listOf(game("1", "NBA"), game("2", "NBA"), game("3", "WNBA"), game("4", "College Basketball"),
            game("5", "Women's College Basketball"), game("6", "MLS"), game("7", "Premier League"), game("8", "NFL"))
        val c = SportRailLogic.counts(games)
        assertEquals(8, c[null]); assertEquals(2, c["nba"]); assertEquals(1, c["wnba"]); assertEquals(1, c["cbb"])
        assertEquals(1, c["wcbb"]); assertEquals(1, c["mls"]); assertEquals(1, c["soccer"]); assertEquals(1, c["nfl"])
        assertEquals(listOf("1", "2"), SportRailLogic.filter(games, "nba").map { it.id })      // no WNBA / college mixed in
        assertEquals(listOf("7"), SportRailLogic.filter(games, "soccer").map { it.id })        // MLS is its own entry
        assertEquals(games, SportRailLogic.filter(games, null))
    }

    @Test fun schedule_todayPlusSixDays_usLabels() {
        assertEquals(0..6, SportRailLogic.DAYS)
        assertEquals(0, SportRailLogic.clampDay(-3)); assertEquals(6, SportRailLogic.clampDay(9))
        assertEquals(listOf("Today", "Wed 11/11", "Thu 11/12", "Fri 11/13", "Sat 11/14", "Sun 11/15", "Mon 11/16"),
            SportRailLogic.DAYS.map { SportRailLogic.dayLabel(it, ny, now) })
        assertEquals("Tuesday, 11/10 · All sports", SportRailLogic.dayHeading(0, null, ny, now))
        assertEquals("Wednesday, 11/11 · College Basketball", SportRailLogic.dayHeading(1, "cbb", ny, now))
    }

    // ───── US dates ─────

    @Test fun usDates_noDayFirstFormats() {
        val s = now / 1000
        assertEquals("Tue 11/10", UsDates.format(UsDates.DAY, s, ny))
        assertEquals("Tue, Nov 10", UsDates.format(UsDates.HEADER, s, ny))
        assertEquals("Tuesday, November 10", UsDates.format(UsDates.LONG, s, ny))
        assertEquals("11/10/2026", UsDates.format(UsDates.FULL, s, ny))
        assertEquals("11/10", UsDates.format(UsDates.SHORT, s, ny))
        // no DD.MM patterns left in the app sources
        val bad = Regex(""""(?:dd\.MM|d\.M\.|dd\.MM\.yyyy|EE dd\.MM|EEE dd\.MM)""")
        val hits = File("src/main/java/com/ipvio/stream").listFiles()!!.filter { it.name.endsWith(".kt") && it.name != "UsDates.kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if (bad.containsMatchIn(l)) "${f.name}:${i + 1}" else null }
        }
        assertEquals(emptyList<String>(), hits)
    }

    // ───── Live TV categories ─────

    private val rawTag = Regex("""\|\s*[A-Za-z]{2,4}\s*\|""")

    @Test fun categories_everyTableEntryClean() {
        val m = LiveCats.current()!!
        assertTrue(m.cats.size >= 363)
        for ((k, i) in m.cats) {
            assertFalse("raw tag in label of $k: ${i.label}", rawTag.containsMatchIn(i.label))
            assertTrue("unknown country ${i.country} for $k", m.countries.containsKey(i.country))
            assertTrue(i.label.isNotBlank())
        }
        assertEquals(listOf("US", "WORLD"), m.first)
        assertEquals(listOf("ADULT"), m.last)
    }

    @Test fun categories_realPanel_all363_mapped_noTagsShown_usFirstAdultsLast() {
        val f = File(data, "livecats.json")
        assumeTrue("panel export not on this machine", f.exists())
        val a = JSONArray(f.readText())
        val names = (0 until a.length()).map { a.getJSONObject(it).getString("category_name").trim() }
        assertEquals(363, names.size)
        val m = LiveCats.current()!!
        val missing = names.filter { LiveCats.norm(it) !in m.cats }
        assertEquals("every panel category is in the table", emptyList<String>(), missing)
        for (n in names) {
            assertFalse("tag shown for $n", rawTag.containsMatchIn(LiveCats.title(n)))
        }
        val groups = LinkedHashMap<String, List<Item>>()
        names.forEachIndexed { i, n -> groups[n] = listOf(Item("x$i", Kind.LIVE, "Ch $i", null, "http://t/$i", null, n)) }
        val secs = LiveCats.sections(groups, emptyMap())
        assertEquals("US", secs.first().country.id)
        assertEquals("ADULT", secs.last().country.id)
        // other countries A–Z between International and Adults
        val mid = secs.drop(2).dropLast(1).map { it.country.name.lowercase() }
        assertEquals(mid.sorted(), mid)
        assertTrue(secs.size > 40)
    }

    @Test fun categories_fallbackForUnknownNames() {
        val a = LiveCats.info("|MX| NOTICIAS NUEVAS")
        assertEquals("MX", a.country)
        assertFalse(rawTag.containsMatchIn(a.label))
        val b = LiveCats.info("|XX| SOMETHING")
        assertTrue(LiveCats.current()!!.countries.containsKey(b.country))
        assertEquals("ADULT", LiveCats.info("|EN| XXX NIGHT").country)
    }

    @Test fun espnVirtualGroup() {
        val l = listOf("USA - ESPN HD", "USA - ESPN 2", "USA - ESPNU", "USA - ESPNEWS", "USA - SEC NETWORK", "USA - ACC NETWORK", "USA - LONGHORN NETWORK",
            "USA - ESPN+ 01", "USA - ESPN PLAY 3", "USA - ESPN HD")
            .mapIndexed { i, n -> Item("e$i", Kind.LIVE, n, null, "http://t/$i", null, "|US| USA GENERAL SPORTS") }
        val v = LiveCats.virtuals(l)
        assertEquals(1, v.size)
        val names = v.values.first().map { it.title }
        assertEquals(7, names.size)                               // no ESPN+ / ESPN PLAY, the duplicate ESPN HD once
        assertFalse(names.any { it.contains("+") || it.contains("PLAY") })
        val secs = LiveCats.sections(mapOf("|US| USA GENERAL SPORTS" to l), v)
        assertTrue(secs.first { it.country.id == "US" }.rows.any { LiveCats.isVirtualKey(it.key) && it.label.contains("ESPN") })
    }

    @Test fun espnVirtualGroup_realPanel() {
        val f = File(data, "livestreams.json"); val c = File(data, "livecats.json")
        assumeTrue("panel export not on this machine", f.exists() && c.exists())
        val ca = JSONArray(c.readText())
        val cat = (0 until ca.length()).associate { ca.getJSONObject(it).let { o -> o.getString("category_id") to o.getString("category_name").trim() } }
        val sa = JSONArray(f.readText())
        val items = (0 until sa.length()).map { sa.getJSONObject(it) }.map { o ->
            Item("s" + o.optString("stream_id"), Kind.LIVE, o.optString("name"), null, "http://t/", null, cat[o.optString("category_id")] ?: "")
        }
        val v = LiveCats.virtuals(items).values.firstOrNull()
        assertNotNull(v)
        val t = v!!.map { LiveCats.cleanName(it.title).uppercase() }
        for (want in listOf("ESPN", "ESPN2|ESPN 2", "ESPNU|ESPN U", "ESPNEWS|ESPN NEWS", "SEC", "ACC", "LONGHORN"))
            assertTrue("$want in ESPN group: $t", t.any { n -> want.split('|').any { n.contains(it) } })
        assertFalse(t.any { it.contains("ESPN+") || it.contains("ESPN +") || it.contains("ESPN PLAY") })
    }

    @Test fun sections_oneRowCountryIsSingleRow_headersForBigger() {
        val g = linkedMapOf(
            "|US| USA NEWS" to listOf(Item("1", Kind.LIVE, "CNN", null, "u", null, "|US| USA NEWS")),
            "|US| USA GENERAL SPORTS" to listOf(Item("2", Kind.LIVE, "FS1", null, "u", null, "|US| USA GENERAL SPORTS")),
            "|AM| MEXICO" to listOf(Item("3", Kind.LIVE, "X", null, "u", null, "|AM| MEXICO"))
        )
        val s = LiveCats.sections(g, emptyMap())
        val keys = LiveCatsUi.flatten(s, LiveCatsUi.initialExpanded(s, null))
        assertEquals("H:US", keys.first())
        assertTrue(keys.contains("|AM| MEXICO"))                   // single row, flag + country name
        assertEquals("Mexico", LiveCatsUi.rowOf(s, "|AM| MEXICO")!!.second)
        assertEquals(listOf("H:US"), LiveCatsUi.flatten(s, emptySet()).filter { it.startsWith("H:") })
    }

    @Test fun countryHide_defaultHidesNothing() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        IgnoredGroups.init(ctx)
        IgnoredGroups.clear(ctx)
        val mx = Item("3", Kind.LIVE, "X", null, "u", null, "|AM| MEXICO")
        assertTrue(IgnoredGroups.isEmpty)
        assertFalse(IgnoredGroups.isIgnored(mx))
        IgnoredGroups.set(ctx, IgnoredGroups.countryKey("MX"), true)
        assertTrue(IgnoredGroups.isIgnored(mx))
        assertFalse(IgnoredGroups.isIgnored(Item("1", Kind.LIVE, "CNN", null, "u", null, "|US| USA NEWS")))
        // movies / series are never hidden by a Live TV country switch
        assertFalse(IgnoredGroups.isIgnored(Item("m", Kind.MOVIE, "Film", null, "u", null, "|AM| MEXICO")))
        IgnoredGroups.clear(ctx)
        assertFalse(IgnoredGroups.isIgnored(mx))
    }

    // ───── phone search QR address ─────

    @Test fun qrAddress_prefersWifiEthernet_skipsVpn() {
        fun c(vararg p: Pair<String, String>) = p.map { LocalIp.Cand(it.first, it.second) }
        assertEquals("192.168.1.20", LocalIp.pick(c("tun0" to "10.8.0.2", "wlan0" to "192.168.1.20")))
        assertEquals("192.168.0.5", LocalIp.pick(c("ppp0" to "10.0.0.9", "eth0" to "192.168.0.5")))
        assertEquals("192.168.0.7", LocalIp.pick(c("rmnet_data0" to "10.1.2.3", "wlan1" to "192.168.0.7")))
        assertEquals("172.16.0.4", LocalIp.pick(c("tun0" to "10.8.0.2", "bridge0" to "172.16.0.4")))
        assertNull(LocalIp.pick(c("tun0" to "10.8.0.2", "wg0" to "10.6.0.1", "ipsec0" to "10.9.0.1", "nordvpn" to "10.5.0.2")))
    }

    @Test fun remotePage_listImportSendsCode() {
        val p = RemoteLogic.page(1)
        assertTrue(p.contains("/listimport?code='+encodeURIComponent(code)"))
    }

    // ───── renewal ─────

    @Test fun renewal_parsesExpDate_andShowsOncePerDay_inLast5Days() {
        val a = RenewLogic.parse("""{"user_info":{"auth":1,"status":"Active","exp_date":"1796570709"}}""")!!
        assertEquals(1796570709L, a.expSec)
        assertEquals(true, a.auth)
        assertNull(RenewLogic.parse("""{"user_info":{"auth":1,"exp_date":null}}""")!!.expSec)
        assertNull(RenewLogic.parse("""{"user_info":{"auth":1,"exp_date":"0"}}""")!!.expSec)
        assertEquals(1796570709L, RenewLogic.parse("""{"user_info":{"exp_date":1796570709}}""")!!.expSec)
        assertNull(RenewLogic.parse("not json"))

        val exp = 1794700800L     // Sat 11/14/2026 00:00 UTC (Fri evening in New York)
        val day = 86_400_000L
        assertFalse(RenewLogic.shouldShow(exp, exp * 1000 - 6 * day, null, ny))        // 6 days left: no
        assertTrue(RenewLogic.shouldShow(exp, exp * 1000 - 5 * day, null, ny))         // 5 days
        assertTrue(RenewLogic.shouldShow(exp, exp * 1000 - day, "2026-11-11", ny))     // new day
        val nowMs = exp * 1000 - 2 * day
        assertFalse(RenewLogic.shouldShow(exp, nowMs, RenewLogic.dayKey(nowMs, ny), ny)) // already today
        assertFalse(RenewLogic.shouldShow(exp, exp * 1000 + 1000, null, ny))           // ended: the ended screen instead
        assertFalse(RenewLogic.shouldShow(null, nowMs, null, ny))                      // unlimited
        // renewed: the end date moved forward, it stops by itself
        assertFalse(RenewLogic.shouldShow(exp + 30 * 86400, nowMs, null, ny))
    }

    @Test fun renewal_endedAndLabels() {
        val nowMs = 1794330000_000L
        assertTrue(RenewLogic.ended(RenewLogic.Account(true, "Active", nowMs / 1000 - 10), nowMs))
        assertTrue(RenewLogic.ended(RenewLogic.Account(true, "Expired", null), nowMs))
        assertTrue(RenewLogic.ended(RenewLogic.Account(false, null, null), nowMs))
        assertFalse(RenewLogic.ended(RenewLogic.Account(true, "Active", nowMs / 1000 + 100), nowMs))
        val nov14 = 1794675600L   // 11/14/2026 12:00 New York
        assertEquals("11/14", RenewLogic.shortDate(nov14, ny))
        assertEquals("Expires: 11/14/2026", RenewLogic.expiresLabel(nov14, ny))
        assertEquals("Expires: 12/06/2026", RenewLogic.expiresLabel(1796570709L, ny))   // the test login's exp_date
        assertEquals("Expires: Never", RenewLogic.expiresLabel(null, ny))
        assertEquals("OK, watch TV", RenewLogic.BUTTON)
    }

    @Test fun payInfo_bundledFile_matchesDefaults_andQrFits() {
        val p = PayInfo.parse(File("src/main/assets/mrtv2-pay.json").readText())!!
        assertEquals(listOf("cashapp", "venmo", "zelle"), p.options.map { it.id })
        assertEquals("https://cash.app/\$ApexKingDigital", p.options[0].qr)
        assertEquals("https://venmo.com/code?user_id=2750865173643264152&created=1794338203", p.options[1].qr)
        assertNull(p.options[2].qr); assertEquals("606-657-1545", p.options[2].big)
        assertEquals(PayInfo.DEFAULT.options.map { it.qr }, p.options.map { it.qr })
        assertTrue(p.warning.contains("Never write \"TV\""))
        for (o in p.options) o.qr?.let { assertNotNull("QR for $it", QrCode.encode(it)) }
        // no http links, no browser: a non-https qr is dropped
        assertNull(PayInfo.parse("""{"options":[{"id":"x","name":"X","qr":"http://example.com"}]}"""))
    }

    // ───── server address ─────

    @Test fun serverAddress_scrambled_roundTrip() {
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val plain = "http://example.invalid:8080"
        val enc = ServerObf.encode(plain, key)
        assertFalse(enc.contains("example"))
        assertEquals(plain, ServerObf.decode(enc, ServerObf.hex(key)))
        assertEquals("", ServerObf.decode("", ""))
        // the build stores the address only scrambled
        assertTrue(BuildConfig.SERVER_K.length == 64)
        val fields = BuildConfig::class.java.declaredFields.map { it.name }
        assertFalse(fields.contains("SERVER_URL"))
        if (BuildConfig.SERVER_X.isNotEmpty()) {
            assertTrue(Rebrand.SERVER_URL.startsWith("http"))
            assertFalse(BuildConfig.SERVER_X.contains(ServerObf.hex(Rebrand.SERVER_URL.toByteArray()).take(16)))
        }
    }
}
