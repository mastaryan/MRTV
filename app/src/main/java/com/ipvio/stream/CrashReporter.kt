package com.ipvio.stream

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * MR TV 2 crash reporter (fork addition).
 *
 * Uncaught exception -> JSON report in filesDir/crash/ -> POSTed to the crash webhook on the next app start.
 * Webhook URL and key are set at build time (MRTV_CRASH_WEBHOOK_URL / MRTV_CRASH_WEBHOOK_KEY, sent as
 * "Authorization: Bearer <key>", see
 * app/build.gradle.kts); without a URL the reporter only keeps reports on the device and sends nothing.
 *
 * Privacy: no logins, passwords or stream addresses. Web addresses and user/password query values are removed from
 * messages and stack traces ([scrub]); breadcrumbs are fixed words (screen names, app start).
 */
object CrashReporter {
    private const val MAX_FILES = 10
    private const val MAX_CRUMBS = 30
    private val crumbs = ArrayDeque<String>()
    @Volatile var screen: String = "start"
        private set
    private var dir: File? = null

    val enabled: Boolean get() = BuildConfig.CRASH_WEBHOOK_URL.isNotBlank()

    @Synchronized
    fun crumb(msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        crumbs.addLast("$t $msg")
        while (crumbs.size > MAX_CRUMBS) crumbs.removeFirst()
    }

    fun screen(name: String) {
        if (name == screen) return
        screen = name
        crumb("screen $name")
    }

    /** Call once at start, after other handlers are installed: this one runs first, then hands over to them. */
    fun install(ctx: Context) {
        if (dir != null) return
        val app = ctx.applicationContext
        dir = File(app.filesDir, "crash").apply { mkdirs() }
        crumb("app start")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write(t, e) }
            prev?.uncaughtException(t, e)
        }
        if (enabled) CoroutineScope(Dispatchers.IO).launch {
            delay(8_000)
            runCatching { uploadPending() }
        }
    }

    private fun write(t: Thread, e: Throwable) {
        val d = dir ?: return
        val o = JSONObject()
            .put("kind", "crash")
            .put("app", Rebrand.APP_NAME)
            .put("applicationId", BuildConfig.APPLICATION_ID)
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("buildType", BuildConfig.BUILD_TYPE)
            .put("manufacturer", Build.MANUFACTURER ?: "?")
            .put("model", Build.MODEL ?: "?")
            .put("device", Build.DEVICE ?: "?")
            .put("android", Build.VERSION.RELEASE ?: "?")
            .put("sdk", Build.VERSION.SDK_INT)
            .put("thread", t.name)
            .put("exception", e.javaClass.name)
            .put("message", scrub(e.message.orEmpty()))
            .put("stack", scrub(e.stackTraceToString()).take(16_000))
            .put("screen", screen)
            .put("breadcrumbs", JSONArray(synchronized(this) { crumbs.toList() }))
            .put("timestamp", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).format(java.util.Date()))
        File(d, "r-${System.currentTimeMillis()}.json").writeText(o.toString())
        // Keep only the newest reports
        d.listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.name }?.dropLast(MAX_FILES)?.forEach { it.delete() }
    }

    private val URL_RE = Regex("""(?i)\b(?:https?|rtmp|rtsp|udp)://\S+""")
    private val CRED_RE = Regex("""(?i)\b(username|user|password|pass|pwd|token|mac)=([^&\s]+)""")

    /** Removes web addresses (Xtream links contain user and password) and credential parameters. */
    fun scrub(s: String): String = CRED_RE.replace(URL_RE.replace(s, "<url>"), "$1=<hidden>")

    private fun post(body: String): Boolean {
        if (!enabled) return false
        val rb = Request.Builder().url(BuildConfig.CRASH_WEBHOOK_URL)
            .header("User-Agent", "MRTV2/" + BuildConfig.VERSION_NAME)
            .post(body.toRequestBody("application/json".toMediaType()))
        if (BuildConfig.CRASH_WEBHOOK_KEY.isNotBlank()) rb.header("Authorization", "Bearer " + BuildConfig.CRASH_WEBHOOK_KEY)
        return runCatching { Net.base.newCall(rb.build()).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** Sends saved reports; each accepted one is deleted, the rest stays for the next start. */
    fun uploadPending() {
        val d = dir ?: return
        if (!enabled) return
        for (f in d.listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()) {
            val body = runCatching { f.readText() }.getOrNull() ?: continue
            if (post(body)) f.delete() else break
        }
    }
}
