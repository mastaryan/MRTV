package com.ipvio.stream

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.view.OrientationEventListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Phone and tablet (round 20). The UI is built for the TV (landscape).
 *
 * MR TV 2 orientation on phones / touch devices:
 * - first sign-in and the TV guide loading step after it: portrait (until the first successful sign-in);
 * - entering the app: landscape first, then released to FULL_USER as soon as the device is physically turned to landscape
 *   (follows the device freely from then on, respecting the system rotation lock);
 * - later starts: FULL_USER right away. Settings > General "Always landscape on the phone" locks landscape instead.
 * Fire TV / Android TV: never touched (always landscape). Rotation and fold/unfold never recreate the activity
 * (manifest configChanges), so nothing reloads.
 */
object MobilePrefs {
    private const val PREFS = "ipvio_mobile"
    /** Settings toggle: always landscape on the phone (off by default since MR TV 2 2.0.3). */
    var landscape by mutableStateOf(false)
        private set
    /** Cached [isMobile] of the running device (set in [apply]). */
    var mobile = false
        internal set
    private var listener: OrientationEventListener? = null

    /** Phone/tablet instead of TV (no Leanback, touch present). */
    fun isMobile(c: Context): Boolean {
        val pm = c.packageManager
        val tv = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature("android.software.leanback_only") ||
            pm.hasSystemFeature("amazon.hardware.fire_tv")
        return !tv && pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun firstLoginDone(c: Context) = sp(c).getBoolean("firstLoginDone", false)
    fun markFirstLoginDone(c: Context) { if (!firstLoginDone(c)) sp(c).edit().putBoolean("firstLoginDone", true).apply() }

    /** Activity start. An existing install with a saved playlist counts as signed in (no portrait lock after the update). */
    fun apply(a: Activity) {
        landscape = sp(a).getBoolean("lockLandscape", false)
        mobile = isMobile(a)
        if (!mobile) return
        if (!firstLoginDone(a) && PlaylistStore.savedUrl(a) != null) markFirstLoginDone(a)
        a.requestedOrientation = OrientationLogic.atStart(firstLoginDone(a), landscape)
    }

    /** First sign-in screen / guide loading step showing (portrait) or not. */
    fun loginPhase(a: Activity?, on: Boolean) {
        if (a == null || !mobile) return
        if (on && !firstLoginDone(a)) { stopListener(); a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }

    /** Leaving the first sign-in into the app: landscape as the entry, released to FULL_USER once the device is turned. */
    fun enterApp(a: Activity?) {
        if (a == null || !mobile) return
        stopListener()
        if (landscape) { a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; return }
        a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val l = object : OrientationEventListener(a.applicationContext) {
            override fun onOrientationChanged(deg: Int) {
                if (OrientationLogic.physicallyLandscape(deg)) { a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER; stopListener() }
            }
        }
        if (l.canDetectOrientation()) { listener = l; l.enable() } else a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    }

    fun stopListener() { listener?.disable(); listener = null }

    fun set(a: Activity, v: Boolean) {
        landscape = v
        sp(a).edit().putBoolean("lockLandscape", v).apply()
        if (!mobile) return
        stopListener()
        a.requestedOrientation = if (v) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    }
}

/** Pure orientation decisions (unit-tested). */
object OrientationLogic {
    fun atStart(firstLoginDone: Boolean, lockLandscape: Boolean): Int = when {
        !firstLoginDone -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        lockLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    }
    /** OrientationEventListener degrees: device held sideways (either way). */
    fun physicallyLandscape(deg: Int): Boolean = deg >= 0 && (deg in 60..120 || deg in 240..300)
}

/**
 * Minimum touch target on phones: 48 real dp, independent of the app's UI scaling (the TV design is drawn smaller on a
 * phone, so a 48 "design" dp row would only be ~35 dp on the finger). 0 on TV (no change there).
 */
@Composable
fun touchMin(v: Float = 48f): Dp = if (!MobilePrefs.mobile) 0.dp else realDp(v)

/** [v] real (screen) dp expressed in the current, scaled density. */
@Composable
fun realDp(v: Float): Dp {
    val scaled = LocalDensity.current.density
    val real = LocalContext.current.resources.displayMetrics.density
    return (v * real / scaled).dp
}
