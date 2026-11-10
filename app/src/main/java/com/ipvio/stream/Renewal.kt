package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.TimeZone

/**
 * MR TV 2 (2.0.6): subscription reminder, like MR TV 1.3.1.
 *
 * At every app start the account (player_api.php user_info) is read once: exp_date and status. In the last 5 days
 * a popup shows at app open only, at most once per calendar day, never over playback; renewing moves exp_date forward,
 * so it stops by itself. Reminder only: nothing opens a browser. After the end date (or status Expired/Disabled) the
 * "subscription ended" screen shows the same payment info with "Change login" and "Retry".
 */
object RenewLogic {
    const val DAYS = 5

    /** What the panel says about the login. [expSec] null = unlimited / not given. */
    class Account(val auth: Boolean?, val status: String?, val expSec: Long?)

    /** Reads user_info from a player_api.php answer (exp_date as text or number, "null"/""/0 = unlimited). */
    fun parse(json: String): Account? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val ui = o.optJSONObject("user_info") ?: return null
        val exp = ui.opt("exp_date")?.toString()?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        val auth = when (ui.opt("auth")?.toString()?.trim()) { "1" -> true; "0" -> false; else -> null }
        return Account(auth, ui.optString("status").takeIf { it.isNotBlank() }, exp)
    }

    fun dayKey(nowMs: Long, tz: TimeZone): String = UsDates.format("yyyy-MM-dd", nowMs / 1000, tz)

    /** Popup now? Within 5 days of the end (and not past it), not yet today. */
    fun shouldShow(expSec: Long?, nowMs: Long, lastShownDay: String?, tz: TimeZone): Boolean {
        if (expSec == null || expSec <= 0) return false
        val left = expSec * 1000 - nowMs
        if (left <= 0 || left > DAYS * 86_400_000L) return false
        return lastShownDay != dayKey(nowMs, tz)
    }

    /** The subscription has ended: rejected login, status expired/disabled/banned, or the end date is past. */
    fun ended(a: Account, nowMs: Long): Boolean {
        if (a.auth == false) return true
        val st = a.status?.trim()?.lowercase()
        if (st != null && st in setOf("expired", "disabled", "banned")) return true
        return a.expSec != null && a.expSec * 1000 <= nowMs
    }

    /** "11/14" */
    fun shortDate(expSec: Long, tz: TimeZone): String = UsDates.format(UsDates.SHORT, expSec, tz)
    /** "Expires: 11/14/2026" (Settings > Account), "Expires: Never" for unlimited. */
    fun expiresLabel(expSec: Long?, tz: TimeZone): String = "Expires: " + (expSec?.takeIf { it > 0 }?.let { UsDates.format(UsDates.FULL, it, tz) } ?: "Never")

    const val TITLE = "Your MR TV subscription ends "
    const val WARNING = "In the payment note, put only your name. Never write \"TV\" or your order will be canceled."
    const val HINT = "Scan or send from your phone, then contact MR TV to renew."
    const val BUTTON = "OK, watch TV"
    /** Longest wait for the account check before a startup channel plays anyway (the popup is then skipped). */
    const val STARTUP_WAIT_MS = 6_000L
}

/** Payment options of the reminder (bundled defaults, optionally replaced by watchmrtv.com/mrtv2-pay.json). */
object PayInfo {
    const val REMOTE_URL = "https://watchmrtv.com/mrtv2-pay.json"
    const val ASSET = "mrtv2-pay.json"
    private const val CACHE = "mrtv2-pay.json"

    /** [qr] = link encoded as QR on the TV (null = show [big] instead, e.g. the Zelle phone number). */
    class Option(val id: String, val name: String, val label: String, val qr: String?, val big: String?, val color: Long)
    class Info(val version: Int, val options: List<Option>, val warning: String, val hint: String)

    val DEFAULT = Info(1, listOf(
        Option("cashapp", "Cash App", "Cash App \$ApexKingDigital", "https://cash.app/\$ApexKingDigital", null, 0xFF00D64F),
        Option("venmo", "Venmo", "Venmo", "https://venmo.com/code?user_id=2750865173643264152&created=1794338203", null, 0xFF008CFF),
        Option("zelle", "Zelle", "Zelle 606-657-1545", null, "606-657-1545", 0xFF6D1ED4)
    ), RenewLogic.WARNING, RenewLogic.HINT)

    @Volatile var info: Info = DEFAULT
        private set

    fun parse(json: String): Info? = runCatching {
        val o = JSONObject(json)
        val a = o.getJSONArray("options")
        val l = (0 until a.length()).map { i ->
            val x = a.getJSONObject(i)
            Option(
                x.getString("id"), x.getString("name"), x.optString("label", x.getString("name")),
                x.optString("qr").takeIf { it.startsWith("https://") }, x.optString("big").takeIf { it.isNotBlank() },
                x.optString("color").removePrefix("#").takeIf { it.length == 6 }?.let { 0xFF000000 or it.toLong(16) } ?: 0xFF2A2E34
            )
        }.filter { it.qr != null || it.big != null }.take(4)
        if (l.isEmpty()) null else Info(o.optInt("version", 1), l, o.optString("warning").ifBlank { RenewLogic.WARNING }, o.optString("hint").ifBlank { RenewLogic.HINT })
    }.getOrNull()

    /** Bundled file, then the cached download; refresh in the background (failures keep what is there). */
    fun init(c: Context) {
        runCatching { c.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()?.let { parse(it) }?.let { info = it }
        val f = File(c.filesDir, CACHE)
        runCatching { if (f.exists()) parse(f.readText())?.let { if (it.version >= info.version) info = it } }
        Thread {
            runCatching {
                val req = okhttp3.Request.Builder().url(REMOTE_URL).build()
                Net.base.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }.getOrNull()?.let { body -> parse(body)?.let { p -> runCatching { f.writeText(body) }; info = p } }
        }.apply { isDaemon = true; name = "mrtv-pay" }.start()
    }
}

/** State of the check at this app start. */
object Renewal {
    /** "Expires: 11/14/2026", "Expires: Never" (unlimited) or "Expires: not known yet" before the first answer. */
    fun label(): String = if (!known) "Expires: not known yet" else RenewLogic.expiresLabel(expSec, General.zone())

    private const val PREFS = "mrtv_renew"
    /** End date of the popup that is showing (null = none). */
    var popupExp by mutableStateOf<Long?>(null)
    var ended by mutableStateOf(false)
    /** Last known end date (Settings > Account), seconds; null = unknown / unlimited. */
    var expSec by mutableStateOf<Long?>(null)
        internal set
    /** false until the panel answered once on this device (then expSec null = unlimited). */
    var known by mutableStateOf(false)
        internal set
    /** Completed when the startup check is done (or failed). */
    val done = CompletableDeferred<Unit>()
    @Volatile var playbackStarted = false
    private var started = false

    fun loadSaved(c: Context) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        known = p.contains("exp")
        expSec = p.getLong("exp", 0L).takeIf { it > 0 }
    }

    /** Once per app start: read the account, decide popup / ended screen. Offline or not an Xtream login: nothing happens. */
    suspend fun checkAtStart(c: Context) {
        if (started) return
        started = true
        try { check(c, atStart = true) } finally { done.complete(Unit) }
    }

    /** Retry on the ended screen: true = the subscription is active again. */
    suspend fun recheck(c: Context): Boolean { check(c, atStart = false); return !ended }

    private suspend fun check(c: Context, atStart: Boolean) {
        val url = PlaylistStore.savedUrl(c) ?: return
        val acct = XtreamLogic.fromUrl(url) ?: return
        val t0 = System.currentTimeMillis()
        val body = withContext(Dispatchers.IO) {
            runCatching { Net.base.newCall(okhttp3.Request.Builder().url(XtreamLogic.api(acct)).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } }.getOrNull()
        } ?: return
        val a = RenewLogic.parse(body) ?: return
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        expSec = a.expSec
        known = true
        p.edit().putLong("exp", a.expSec ?: 0L).apply()
        val now = System.currentTimeMillis()
        ended = RenewLogic.ended(a, now)
        if (ended || !atStart) { if (!ended) popupExp = null; return }
        // Only at app open: not when the check took too long or a channel already plays
        if (playbackStarted || now - t0 > RenewLogic.STARTUP_WAIT_MS) return
        val tz = General.zone()
        if (RenewLogic.shouldShow(a.expSec, now, p.getString("day", null), tz)) {
            p.edit().putString("day", RenewLogic.dayKey(now, tz)).apply()
            popupExp = a.expSec
        }
    }
}
