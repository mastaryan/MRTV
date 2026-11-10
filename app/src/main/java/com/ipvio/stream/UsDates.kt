package com.ipvio.stream

/** MR TV 2: US date patterns used everywhere (month before day, no "dd.MM."). */
object UsDates {
    /** "Wed 11/11" */
    const val DAY = "EEE M/d"
    /** "Wed, Nov 11" */
    const val HEADER = "EEE, MMM d"
    /** "Wednesday, November 11" */
    const val LONG = "EEEE, MMMM d"
    /** "11/14/2026" */
    const val FULL = "MM/dd/yyyy"
    /** "11/14" */
    const val SHORT = "M/d"

    fun format(pattern: String, sec: Long, tz: java.util.TimeZone = java.util.TimeZone.getDefault(), loc: java.util.Locale = java.util.Locale.US): String =
        java.text.SimpleDateFormat(pattern, loc).apply { timeZone = tz }.format(java.util.Date(sec * 1000))
}
