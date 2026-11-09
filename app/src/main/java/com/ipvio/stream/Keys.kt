package com.ipvio.stream

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

@Composable
fun Modifier.favKeys(onFav: () -> Unit): Modifier {
    var longDone by remember { mutableStateOf(false) }
    return this.onPreviewKeyEvent { e ->
        when {
            e.key == Key.Menu && e.type == KeyEventType.KeyDown -> { onFav(); true }
            // Long OK: trigger only on release. If the action opens a menu, that menu would otherwise receive the release
            // as a click on the first entry.
            e.key == Key.DirectionCenter && e.type == KeyEventType.KeyDown && (longDone || e.nativeKeyEvent.isLongPress) -> { longDone = true; true }
            e.key == Key.DirectionCenter && e.type == KeyEventType.KeyUp && longDone -> { longDone = false; onFav(); true }
            else -> false
        }
    }
}

/**
 * Menu key opens a menu ([onMenu]), OK held long does [onLong] (on release).
 * Many remotes (Fire TV) have no menu key: then a long OK opens the same menu.
 */
@Composable
fun Modifier.menuKeys(onMenu: () -> Unit, onLong: () -> Unit): Modifier {
    var longDone by remember { mutableStateOf(false) }
    return this.onPreviewKeyEvent { e ->
        when {
            e.key == Key.Menu && e.type == KeyEventType.KeyDown -> { onMenu(); true }
            e.key == Key.DirectionCenter && e.type == KeyEventType.KeyDown && (longDone || e.nativeKeyEvent.isLongPress) -> { longDone = true; true }
            e.key == Key.DirectionCenter && e.type == KeyEventType.KeyUp && longDone -> { longDone = false; onLong(); true }
            else -> false
        }
    }
}

/**
 * MR TV 2: wraps a long-press action so it only runs for a finger. combinedClickable also reports a long OK on the
 * remote; that one is already handled by [favKeys] / [menuKeys] (on release), and must not run a second time.
 */
@androidx.compose.runtime.Composable
fun touchOnly(f: () -> Unit): () -> Unit {
    val im = androidx.compose.ui.platform.LocalInputModeManager.current
    return { if (im.inputMode == androidx.compose.ui.input.InputMode.Touch) f() }
}
