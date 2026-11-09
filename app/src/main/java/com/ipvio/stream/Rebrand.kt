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

    fun apply(s: String): String =
        if (s.indexOf("IPVIO") < 0) s
        else s.replace("IPVIO Stream", APP_NAME).replace("IPVIO", SHORT_NAME)
}
