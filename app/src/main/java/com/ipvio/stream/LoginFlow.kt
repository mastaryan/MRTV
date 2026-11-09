package com.ipvio.stream

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Pure rules of the TV guide step after sign-in (unit-tested). */
object EpgGateLogic {
    /** The guide never traps the user: after this the app opens and the guide finishes in the background. */
    const val TIMEOUT_MS = 75_000L
    /** How long a guide error stays readable before the app opens by itself. */
    const val ERROR_SHOW_MS = 4_000L
    enum class Step { WAIT, ENTER, SHOW_ERROR }
    fun step(running: Boolean, elapsedMs: Long, error: String?): Step = when {
        running && elapsedMs < TIMEOUT_MS -> Step.WAIT
        running -> Step.ENTER                       // timeout: continue, guide keeps loading
        error != null -> Step.SHOW_ERROR
        else -> Step.ENTER
    }
    /** 0..1 when the size is known, else null (indeterminate). */
    fun fraction(bytes: Long, total: Long): Float? = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * MR TV 2: branded loading step right after a successful sign-in. Starts the TV guide (XMLTV) download at once and shows
 * its progress; enters the app when it is done, after [EpgGateLogic.TIMEOUT_MS], or with Skip / Back. Never shown on an
 * automatic start (no auto-resume after a crash), only after the user signed in.
 */
@Composable
fun EpgGateScreen(onEnter: () -> Unit) {
    val ctx = LocalContext.current
    var entered by remember { mutableStateOf(false) }
    fun enter() { if (!entered) { entered = true; onEnter() } }
    var bytes by remember { mutableLongStateOf(0L) }
    var total by remember { mutableLongStateOf(0L) }
    var shows by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val skipReq = remember { FocusRequester() }
    BackHandler { enter() }
    LaunchedEffect(Unit) {
        // Now instead of after the 6 s start delay (restarts the pending run)
        runCatching { Xmltv.start(ctx, PlaylistStore.epgUrls, PlaylistStore.channels) }
        delay(100); runCatching { skipReq.requestFocus() }
        var waited = 0L   // counted in steps of the loop (follows the frame clock, so UI tests can run the timeout)
        while (!entered) {
            bytes = Xmltv.bytes; total = Xmltv.total; shows = Xmltv.programmes
            when (EpgGateLogic.step(Xmltv.running, waited, Xmltv.lastError)) {
                EpgGateLogic.Step.WAIT -> { delay(250); waited += 250 }
                EpgGateLogic.Step.ENTER -> enter()
                EpgGateLogic.Step.SHOW_ERROR -> { error = Lang.tr("Fehler") + ": " + Xmltv.lastError; delay(EpgGateLogic.ERROR_SHOW_MS); enter() }
            }
        }
    }
    val frac = EpgGateLogic.fraction(bytes, total)
    val channels = remember { PlaylistStore.channels.count { it.kind == Kind.LIVE } }
    Box(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(lerp(Bg, Accent1, 0.14f), Bg), radius = 1400f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BrandMark(Modifier.size(96.dp))
            Text(Rebrand.APP_NAME, raw = true, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            Text(Lang.tr("Angemeldet") + " · " + channels + " " + Lang.tr("Sender"), raw = true, color = Muted, fontSize = 15.sp)
            Text(
                if (error != null) "TV-Programm nicht geladen" else "TV-Programm wird geladen …",
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold
            )
            val track = Color.White.copy(alpha = 0.10f)
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(track)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac ?: indeterminate(shows)).clip(RoundedCornerShape(50)).background(Accent1))
            }
            Text(
                raw = true,
                text = (if (frac != null) "${(frac * 100).toInt()} %  ·  " else "") +
                    (if (bytes > 0) mbText(bytes) + "  ·  " else "") + shows + " " + Lang.tr("Sendungen"),
                color = Muted, fontSize = 14.sp
            )
            if (error != null) Text(raw = true, text = error!!, color = DS.Danger, fontSize = 14.sp, textAlign = TextAlign.Center)
            Text("Die App öffnet sich automatisch. Das Programm lädt sonst im Hintergrund weiter.", color = Muted.copy(alpha = 0.7f), fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            PillButton("Überspringen", Modifier.focusRequester(skipReq).fillMaxWidth().heightIn(min = maxOf(realDp(48f), 48.dp))) { enter() }
        }
    }
}

/** Size unknown (gzip, chunked): a bar that keeps filling with the shows read, never reaching the end. */
private fun indeterminate(shows: Int): Float = (1f - 1f / (1f + shows / 20_000f)).coerceIn(0.03f, 0.95f)

private fun mbText(b: Long): String = if (b >= 1_000_000) String.format(java.util.Locale.US, "%.1f MB", b / 1_000_000f) else "${b / 1000} KB"
