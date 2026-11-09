package com.ipvio.stream

/**
 * MR TV 2 branding (fork of IPVIO Stream by Emrodev, GPL-3.0).
 *
 * Kept in one small file on purpose: upstream UI texts (German source strings and their translations in Dict*.kt)
 * still say "IPVIO Stream". Instead of editing hundreds of upstream lines, every UI text runs through [apply]
 * (see Lang.tr, PhoneImport/RemoteLogic tr), so upstream updates merge without conflicts.
 */
object Rebrand {
    const val APP_NAME = "MR TV 2"
    const val SHORT_NAME = "MR TV"
    const val WEBSITE = "https://watchmrtv.com"

    /** Credit required by the fork (shown in Settings > About). */
    const val UPSTREAM_CREDIT = "Based on IPVIO Stream by Emrodev (GPL-3.0)"
    const val UPSTREAM_URL = "https://github.com/Emrodev/IPVIO-Stream"
    const val SOURCE_URL = "https://github.com/mastaryan/MRTV"

    /** User-Agent for every request to the panel (API, guide, catch-up, playback), like MR TV 1.3's "MRTV/<version>". */
    val USER_AGENT: String = "MRTV2/" + BuildConfig.VERSION_NAME.filter { it.isLetterOrDigit() || it in ".-_" }.ifEmpty { "2" }

    /**
     * MR TV 2: the MR TV Xtream server, built in at build time (BuildConfig.SERVER_URL from the git-ignored
     * server.properties, -PMRTV2_SERVER_URL or the MRTV2_SERVER_URL environment variable). Empty = normal IPVIO login.
     * When set, the login screen only asks for username and password and the address is not shown in the app.
     */
    val SERVER_URL: String = BuildConfig.SERVER_URL.trim().trimEnd('/')
    val SERVER_LOCKED: Boolean get() = SERVER_URL.isNotEmpty()

    /** True if [url] (playlist/Xtream address) points to the built-in MR TV server. */
    fun isBuiltInServer(url: String): Boolean {
        if (!SERVER_LOCKED) return false
        return try {
            val a = android.net.Uri.parse(SERVER_URL)
            val b = android.net.Uri.parse(url.trim())
            a.host != null && a.host.equals(b.host, true) && a.port == b.port
        } catch (e: Exception) { false }
    }

    fun apply(s: String): String =
        if (s.indexOf("IPVIO") < 0) s
        else s.replace("IPVIO Stream", APP_NAME).replace("IPVIO", SHORT_NAME)
}
