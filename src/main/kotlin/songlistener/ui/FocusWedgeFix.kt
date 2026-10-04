package songlistener.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.FrameWindowScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Canvas
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.WindowEvent

private const val FOCUS_SETTLE_MS = 300L

/**
 * AWT on macOS can miss a window re-activation entirely: after switching to another app and
 * clicking back, the window is key and mouse events flow, but WINDOW_ACTIVATED /
 * WINDOW_GAINED_FOCUS never arrive, so the KeyboardFocusManager discards every key press and the
 * text fields cannot be typed into. Plain Swing on this JDK does the same.
 *
 * On any press while AWT still thinks the window is unfocused, wait for the real event to win the
 * race, then repost the missing activation events and hand focus back to the Compose canvas.
 * Ported from ChurchPresenter's `FocusLostRescueState`.
 */
@Composable
fun FrameWindowScope.FocusWedgeFix(content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Press || window.isFocused) continue
                    scope.launch {
                        delay(FOCUS_SETTLE_MS)
                        if (window.isFocused) return@launch
                        val queue = Toolkit.getDefaultToolkit().systemEventQueue
                        queue.postEvent(WindowEvent(window, WindowEvent.WINDOW_ACTIVATED))
                        queue.postEvent(WindowEvent(window, WindowEvent.WINDOW_GAINED_FOCUS))
                        // Key events reach Compose only when its canvas is the AWT focus owner.
                        EventQueue.invokeLater {
                            val target = findCanvas(window) ?: window.mostRecentFocusOwner ?: window
                            if (!target.requestFocusInWindow()) target.requestFocus()
                        }
                    }
                }
            }
        }
    ) { content() }
}

private fun findCanvas(c: Component): Component? = when (c) {
    is Canvas -> c
    is Container -> c.components.firstNotNullOfOrNull { findCanvas(it) }
    else -> null
}
