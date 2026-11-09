package com.ipvio.stream

import android.app.Application
import android.os.Build

/**
 * MR TV 2 (fork addition): installs the crash handlers as early as possible – before any screen, service or
 * receiver runs – and sends reports saved by an earlier crash right away (see [CrashReporter]).
 */
class MrtvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Only the main process sends saved reports (the short-lived ":restart" helper process must not send them twice)
        val main = Build.VERSION.SDK_INT < 28 || getProcessName() == packageName
        CrashReporter.install(this, upload = main)
    }
}
