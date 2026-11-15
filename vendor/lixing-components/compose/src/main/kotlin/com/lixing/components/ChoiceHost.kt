package com.lixing.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo

private val LocalChoiceHost = staticCompositionLocalOf<ChoiceHostState?> { null }

/** Wraps a desktop window's Compose content to arbitrate C12 and C21 pointer dismissal. */
@Composable
fun LixingChoiceHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val host = remember { ChoiceHostState() }
    val window = LocalWindowInfo.current
    LaunchedEffect(host, window) {
        snapshotFlow { window.isWindowFocused }.collect { focused ->
            if (focused) host.observedWindowFocus = true
            else if (host.observedWindowFocus) host.close()
        }
    }
    CompositionLocalProvider(LocalChoiceHost provides host) {
        Box(modifier.fillMaxSize()
            .onGloballyPositioned { host.coordinates = it }
            .pointerInput(host) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val press = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() }
                        if (press != null) host.press(press.position)
                    }
                }
            }) { content() }
    }
}

internal class ChoiceHostState {
    var coordinates: LayoutCoordinates? = null
    var observedWindowFocus = false
    private var active: ChoiceSession? = null

    fun activate(session: ChoiceSession) {
        if (active === session) return
        close()
        active = session
    }

    fun detach(session: ChoiceSession) {
        if (active === session) active = null
    }

    fun close() = active?.close()

    fun press(localPosition: Offset) {
        val session = active ?: return
        val layout = coordinates?.takeIf { it.isAttached } ?: return
        if (!session.trigger.contains(layout.localToWindow(localPosition))) session.close()
    }
}

@Composable
internal fun rememberChoiceSession(): ChoiceSession {
    val host = LocalChoiceHost.current
    val session = remember(host) { ChoiceSession(host) }
    DisposableEffect(session) { onDispose { session.close() } }
    return session
}
