package com.ipvio.stream

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.StringWriter

/**
 * MR TV 2 2.0.6 smoke test: login + live channel list against the fake Xtream server (tools/fake-xtream, port 8088),
 * with the server address going through the same scrambling the release build uses. Skipped when the server is not running.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FakeXtreamSmoke206Test {
    private fun serverUp(port: Int) = runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess

    @Test fun loginAndLiveChannels_viaScrambledServerAddress() {
        assumeTrue("fake Xtream server on :8088", serverUp(8088))
        LiveCats.use(LiveCats.parse(File("src/main/assets/mrtv2-categories.json").readText()))
        // as app/build.gradle.kts: random key, XOR, hex; decoded like Rebrand.SERVER_URL
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val x = ServerObf.encode("http://127.0.0.1:8088/", key)
        assertFalse(x.contains("127.0.0.1"))
        val base = ServerObf.decode(x, ServerObf.hex(key)).trim().trimEnd('/')
        assertEquals("http://127.0.0.1:8088", base)

        val url = PlaylistStore.xtreamUrl(base, "demo", "demo")!!
        val acct = XtreamLogic.fromUrl(url)!!
        val api = PlaylistStore.xtreamApi()
        api.verify(acct)                                     // throws on a rejected login
        val w = StringWriter()
        val n = api.write(acct, w)
        assertTrue("entries written: $n", n > 20)
        val items = M3uParser.parse(w.toString())
        val live = items.filter { it.kind == Kind.LIVE }
        assertTrue(live.size > 20)
        assertTrue(live.any { it.title == "US: ESPN" && it.url.startsWith("http://127.0.0.1:8088/live/demo/demo/") })
        // categories cleaned (no raw tags), ESPN group built
        assertTrue(live.all { !Regex("""\|[A-Z]{2,4}\|""").containsMatchIn(LiveCats.title(it.group)) })
        assertEquals("United States", LiveCats.country(LiveCats.info("USA News").country).name)
        assertTrue(LiveCats.virtuals(live).values.first().any { it.title == "US: ESPN 2" })

        // renewal check reads the same login's user_info (exp_date 300 days ahead: no popup, not ended)
        val body = Net.base.newCall(okhttp3.Request.Builder().url(XtreamLogic.api(acct)).build()).execute().use { it.body!!.string() }
        val a = RenewLogic.parse(body)!!
        assertNotNull(a.expSec)
        assertFalse(RenewLogic.ended(a, System.currentTimeMillis()))
        assertFalse(RenewLogic.shouldShow(a.expSec, System.currentTimeMillis(), null, java.util.TimeZone.getTimeZone("America/New_York")))
    }
}
