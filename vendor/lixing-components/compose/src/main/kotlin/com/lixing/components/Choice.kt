package com.lixing.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

internal val selectDefinition = LixingDesign.component("C12")
private val maxRows = selectDefinition.number("visibleRows").toInt()

internal class ChoiceSession(private val host: ChoiceHostState?) {
    var open by mutableStateOf(false)
        private set
    var activeId by mutableStateOf<String?>(null)
    var anchor by mutableStateOf(Rect.Zero)
    var trigger = Rect.Zero
    val hosted get() = host != null
    private var activation: Key? = null
    private var typed = ""
    private var lastTyped = 0L
    private val prefixTimeout = selectDefinition.interaction.getInt("prefixMilliseconds")

    fun open(options: List<ChoiceOption>, selectedId: String?, reverse: Boolean = false): Boolean {
        val available = options.filter(ChoiceOption::enabled)
        activeId = available.firstOrNull { it.id == selectedId }?.id
            ?: (if (reverse) available.lastOrNull() else available.firstOrNull())?.id
        open = activeId != null
        if (open) host?.activate(this)
        return open
    }

    fun close() {
        host?.detach(this)
        open = false
        activeId = null
        typed = ""
        lastTyped = 0L
    }

    fun acceptsEditorBlur(windowFocused: Boolean): Boolean =
        host?.observedWindowFocus != true || windowFocused

    fun consumedActivation(event: KeyEvent): Boolean {
        if (activation != event.key) return false
        if (event.type == KeyEventType.KeyUp) activation = null
        return true
    }

    fun adopt(event: KeyEvent, action: () -> Unit) {
        activation = event.key
        action()
    }

    fun key(
        event: KeyEvent,
        options: List<ChoiceOption>,
        onSelect: (ChoiceOption) -> Unit,
        allowPrefix: Boolean,
    ): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val available = options.filter(ChoiceOption::enabled)
        if (available.isEmpty()) {
            close()
            return false
        }
        val index = available.indexOfFirst { it.id == activeId }
        val key = event.key
        val next = when (key) {
            Key.DirectionDown -> (index + 1).coerceAtMost(available.lastIndex)
            Key.DirectionUp -> (index - 1).coerceAtLeast(0)
            Key.MoveHome -> if (allowPrefix) 0 else return false
            Key.MoveEnd -> if (allowPrefix) available.lastIndex else return false
            Key.PageDown -> (index + maxRows).coerceAtMost(available.lastIndex)
            Key.PageUp -> (index - maxRows).coerceAtLeast(0)
            Key.Escape, Key.F4 -> { adopt(event, ::close); return true }
            Key.Tab -> { close(); return false }
            Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                if (key == Key.Spacebar && !allowPrefix) return false
                available.getOrNull(index)?.let { option ->
                    adopt(event) {
                        close()
                        onSelect(option)
                    }
                }
                return true
            }
            else -> {
                if (!allowPrefix || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
                    return false
                }
                val code = event.utf16CodePoint
                if (code <= 0 || !Character.isValidCodePoint(code) || Character.isISOControl(code)) return false
                val now = System.nanoTime() / 1_000_000
                val character = String(Character.toChars(code))
                typed = if (now - lastTyped > prefixTimeout) character else typed + character
                lastTyped = now
                val start = if (typed.length == character.length) index + 1 else index.coerceAtLeast(0)
                val match = (available.indices).firstOrNull { offset ->
                    available[(start + offset) % available.size].label.startsWith(typed, ignoreCase = true)
                }
                if (match != null) activeId = available[(start + match) % available.size].id
                return true
            }
        }
        activeId = available[next].id
        return true
    }
}

private fun Key.isOpenKey(): Boolean =
    this == Key.DirectionDown || this == Key.DirectionUp || this == Key.F4

/**
 * C12. Focus remains on the trigger and only adoption calls onSelect.
 * Put the window content inside [LixingChoiceHost] for single-toggle and outside-click behavior.
 */
@Composable
fun LixingSelect(
    label: String,
    options: List<ChoiceOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "请选择",
    unavailableLabel: String = "没有可用选项",
    expandedLabel: String = "已展开",
) {
    val session = rememberChoiceSession()
    val selected = options.firstOrNull { it.id == selectedId }
    val canOpen = enabled && options.any(ChoiceOption::enabled)
    val value = selected?.label ?: if (canOpen) placeholder else unavailableLabel

    LaunchedEffect(canOpen) { if (!canOpen) session.close() }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(selectDefinition.dp("gap"))) {
        LixingText(label, size = LixingDesign.textSize("sm"))
        Box(Modifier.fillMaxWidth().onGloballyPositioned { session.anchor = it.boundsInWindow() }) {
            Control(
                selectDefinition,
                { if (session.open) session.close() else session.open(options, selectedId) },
                Modifier.fillMaxWidth().onGloballyPositioned { session.trigger = it.boundsInWindow() }.semantics {
                    contentDescription = label
                    val active = options.firstOrNull { it.id == session.activeId }?.label
                    stateDescription = if (session.open) listOfNotNull(value, expandedLabel, active)
                        .distinct().joinToString(" · ") else value
                }.onPreviewKeyEvent { event ->
                    if (session.consumedActivation(event)) true
                    else if (!canOpen) false
                    else if (session.open) session.key(event, options, { onSelect(it.id) }, allowPrefix = true)
                    else if (event.type == KeyEventType.KeyDown && event.key.isOpenKey()) {
                        session.open(options, selectedId, event.key == Key.DirectionUp)
                    } else false
                },
                enabled = canOpen,
                role = Role.DropdownList,
                keyboardFocusOnly = true,
            ) { text ->
                ControlText(value, text, Modifier.weight(1f))
                ChoiceChevron(text, session.open)
            }
            if (canOpen) ChoicePopup(session, options, selectedId) { onSelect(it.id) }
        }
    }
}

@Composable
internal fun ChoicePopup(
    session: ChoiceSession,
    options: List<ChoiceOption>,
    selectedId: String?,
    onSelect: (ChoiceOption) -> Unit,
) {
    if (!session.open || session.anchor.width <= 0 || session.anchor.height <= 0) return
    val density = LocalDensity.current
    val viewport = LocalWindowInfo.current.containerSize
    val edgePadding = selectDefinition.dp("workMargin")
    val gap = selectDefinition.dp("anchorGap")
    val padding = selectDefinition.dp("popupPadding")
    val border = selectDefinition.dp("border")
    val marginPx = with(density) { edgePadding.roundToPx() }
    val gapPx = with(density) { gap.roundToPx() }
    val availableAbove = session.anchor.top - gapPx - marginPx
    val availableBelow = viewport.height - session.anchor.bottom - gapPx - marginPx
    val height = selectDefinition.dp("height") * maxRows + (padding + border) * 2
    val maxHeight = minOf(height, with(density) { maxOf(availableAbove, availableBelow, 1f).toDp() })
    val width = with(density) {
        minOf(session.anchor.width, (viewport.width - marginPx * 2).coerceAtLeast(1).toFloat()).toDp()
    }
    val provider = remember(marginPx, gapPx) { ChoicePosition(marginPx, gapPx) }
    val style = selectDefinition.frame(LixingTheme.colors, state = "popup")
    val state = rememberLazyListState()

    LaunchedEffect(options, session.activeId) {
        val active = options.indexOfFirst { it.id == session.activeId && it.enabled }
        if (active < 0) {
            session.activeId = options.firstOrNull(ChoiceOption::enabled)?.id
            if (session.activeId == null) session.close()
        } else {
            val layout = state.layoutInfo
            val row = layout.visibleItemsInfo.firstOrNull { it.index == active }
            if (row == null || row.offset < layout.viewportStartOffset ||
                row.offset + row.size > layout.viewportEndOffset) {
                state.scrollToItem(active)
            }
        }
    }

    Popup(
        popupPositionProvider = provider,
        onDismissRequest = session::close,
        properties = PopupProperties(focusable = false, dismissOnClickOutside = !session.hosted),
    ) {
        LazyColumn(
            Modifier.width(width).heightIn(max = maxHeight)
                .frame(style, selectDefinition.dp("popupRadius"), border)
                .padding(padding + border),
            state = state,
        ) {
            items(options, key = ChoiceOption::id) { option ->
                val adopted = option.id == selectedId
                val active = option.id == session.activeId
                val colors = LixingTheme.colors
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val pressed by interaction.collectIsPressedAsState()
                val frame = selectDefinition.frame(colors, option.enabled, if (adopted) "selected" else "option",
                    hovered = hovered, pressed = pressed)
                val focusRole = if (adopted) "selectedActive" else "active"
                val focus = requireNotNull(selectDefinition.roles(focusRole))
                val activeFrame = if (active && option.enabled) frame.copy(
                    fill = if (adopted) frame.fill else colors[focus.getString("fill")],
                    focus = colors[focus.getString("edge")],
                ) else frame
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = selectDefinition.dp("height"))
                        .frame(activeFrame, selectDefinition.dp("radius"), border,
                            focused = active && option.enabled, innerFocus = selectDefinition.dp("innerFocus"))
                        .focusProperties { canFocus = false }
                        .semantics { selected = adopted }
                        .pointerInput(option.id, option.enabled) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (option.enabled && (event.type == PointerEventType.Enter ||
                                            event.type == PointerEventType.Move)) session.activeId = option.id
                                }
                            }
                        }
                        .clickable(interaction, null, option.enabled, role = Role.RadioButton) {
                            if (session.open) {
                                session.close()
                                onSelect(option)
                            }
                        }
                        .padding(
                            horizontal = selectDefinition.dp("paddingInline") + border,
                            vertical = selectDefinition.dp("paddingBlock") + border,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(selectDefinition.dp("gap")),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ControlText(option.label, frame.text, Modifier.weight(1f))
                    ControlText("✓", if (adopted) frame.text else Color.Transparent,
                        Modifier.clearAndSetSemantics {})
                }
            }
        }
    }
}

@Composable
internal fun ChoiceChevron(color: Color, expanded: Boolean) {
    Chevron(color, selectDefinition.dp("indicator"), expanded, verticalInset = .375f)
}

private class ChoicePosition(private val margin: Int, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val right = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val left = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left
            else anchorBounds.right - popupContentSize.width
        val below = windowSize.height - anchorBounds.bottom - gap - margin
        val above = anchorBounds.top - gap - margin
        val top = if (below >= popupContentSize.height || below >= above) anchorBounds.bottom + gap
            else anchorBounds.top - gap - popupContentSize.height
        return IntOffset(left.coerceIn(margin, right),
            top.coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)))
    }
}
