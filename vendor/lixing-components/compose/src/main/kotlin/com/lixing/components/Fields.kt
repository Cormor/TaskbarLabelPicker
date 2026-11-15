package com.lixing.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue

private val field = LixingDesign.component("C05")
private val suggestion = LixingDesign.component("C21")

@Composable
fun LixingTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    hint: String? = null,
    error: String? = null,
    onCommit: ((String) -> Unit)? = null,
) {
    var local by remember { mutableStateOf(TextFieldValue(value)) }
    val editor = if (local.text == value) local else TextFieldValue(value, TextRange(value.length))
    var pendingEnter by remember { mutableStateOf(false) }
    FieldLayout(field, label, hint, error, modifier) {
        Editor(field, label, editor, {
            local = it
            if (it.text != value) onValueChange(it.text)
        }, Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
            if (!enabled || readOnly || !singleLine || onCommit == null ||
                (event.key != Key.Enter && event.key != Key.NumPadEnter)) return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyDown) {
                pendingEnter = editor.composition == null
                pendingEnter
            } else if (event.type == KeyEventType.KeyUp && pendingEnter) {
                pendingEnter = false
                onCommit(value)
                true
            } else false
        }, enabled, readOnly, singleLine, error, onCommit)
    }
}

/**
 * C21. Escape closes suggestions first and restores the last adopted text from a closed editor.
 * Put the window content inside [LixingChoiceHost] for single-toggle and outside-click behavior.
 */
@Composable
fun LixingSuggestions(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    committedValue: String,
    options: List<ChoiceOption>,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hint: String? = null,
    error: String? = null,
    suggestionsLabel: String = "展开建议",
    commitOnFocusLost: Boolean = false,
) {
    var local by remember { mutableStateOf(TextFieldValue(value)) }
    val editor = if (local.text == value) local else TextFieldValue(value, TextRange(value.length))
    val focus = remember { FocusRequester() }
    val session = rememberChoiceSession()
    val window = LocalWindowInfo.current
    val canOpen = enabled && options.any(ChoiceOption::enabled)
    val selectedId = options.firstOrNull { it.label == committedValue }?.id
    var editorFocused by remember { mutableStateOf(false) }
    var lastSubmitted by remember(committedValue) { mutableStateOf(committedValue) }
    fun submit(text: String) {
        lastSubmitted = text
        onCommit(text)
    }
    val select: (ChoiceOption) -> Unit = {
        if (it.label != value) onValueChange(it.label)
        submit(it.label)
    }

    LaunchedEffect(canOpen) { if (!canOpen) session.close() }

    FieldLayout(suggestion, label, hint, error, modifier) {
        Box(Modifier.fillMaxWidth().onGloballyPositioned { session.anchor = it.boundsInWindow() }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(suggestion.dp("gap"))) {
                Editor(suggestion, label, editor, {
                    local = it
                    if (it.text != value) onValueChange(it.text)
                }, Modifier.weight(1f).focusRequester(focus).onFocusChanged { state ->
                    val lost = editorFocused && !state.isFocused
                    editorFocused = state.isFocused
                    if (lost) {
                        session.close()
                        if (commitOnFocusLost && enabled && session.acceptsEditorBlur(window.isWindowFocused) &&
                            editor.composition == null &&
                            value != committedValue && value != lastSubmitted) submit(value)
                    }
                }.onPreviewKeyEvent { event ->
                    if (session.consumedActivation(event)) return@onPreviewKeyEvent true
                    if (!enabled || editor.composition != null) return@onPreviewKeyEvent false
                    if (session.open) {
                        session.key(event, options, select, allowPrefix = false)
                    } else if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.F4, Key.DirectionDown, Key.DirectionUp ->
                                session.open(options, selectedId, event.key == Key.DirectionUp)
                            Key.Escape -> {
                                session.adopt(event) {
                                    if (value != committedValue) onValueChange(committedValue)
                                }
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> { session.adopt(event) { submit(value) }; true }
                            else -> false
                        }
                    } else false
                }.semantics {
                    if (session.open) {
                        val active = options.firstOrNull { it.id == session.activeId }?.label
                        if (active != null) stateDescription = active
                    }
                }, enabled, false, true, error, ::submit)
                Control(suggestion, {
                    focus.requestFocus()
                    if (session.open) session.close() else session.open(options, selectedId)
                }, Modifier.width(suggestion.dp("triggerWidth"))
                    .onGloballyPositioned { session.trigger = it.boundsInWindow() }
                    .focusProperties { canFocus = false }
                    .semantics { contentDescription = suggestionsLabel },
                    enabled = canOpen,
                    iconOnly = true,
                ) { text ->
                    ChoiceChevron(text, session.open)
                }
            }
            if (canOpen) ChoicePopup(session, options, selectedId, select)
        }
    }
}

@Composable
private fun FieldLayout(
    definition: ComponentDefinition,
    label: String,
    hint: String?,
    error: String?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(definition.dp("gap"))) {
        LixingText(label, size = LixingDesign.textSize("sm"))
        content()
        if (error != null || hint != null) {
            LixingText(error ?: hint.orEmpty(),
                color = LixingTheme.colors[if (error == null) "textMuted" else "danger"],
                size = LixingDesign.textSize("sm"))
        }
    }
}

@Composable
private fun Editor(
    definition: ComponentDefinition,
    label: String,
    value: TextFieldValue,
    onEdit: (TextFieldValue) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    readOnly: Boolean,
    singleLine: Boolean,
    error: String?,
    onCommit: ((String) -> Unit)?,
) {
    val colors = LixingTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    var pressed by remember { mutableStateOf(false) }
    val border = definition.dp("border")
    val frame = definition.frame(colors, enabled, state = if (error != null) "invalid" else "default",
        hovered = hovered, pressed = pressed)
    val selection = remember(colors) {
        TextSelectionColors(colors["accentEdge"], colors["selection"].copy(alpha = .5f))
    }
    CompositionLocalProvider(LocalTextSelectionColors provides selection) {
        BasicTextField(
            value = value,
            onValueChange = onEdit,
            modifier = modifier.defaultMinSize(minHeight = definition.dp("height"))
                .hoverable(interactions, enabled)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        // Editing retains the entire sequence. This observer only colors the shell.
                        pressed = true
                        try {
                            while (awaitPointerEvent(PointerEventPass.Initial).changes
                                    .any { it.id == down.id && it.pressed }) {
                                // Wait until the tracked pointer is released or cancelled.
                            }
                        } finally {
                            pressed = false
                        }
                    }
                }
                .semantics {
                    contentDescription = label
                    if (error != null) error(error)
                },
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            textStyle = textStyle(frame.text),
            cursorBrush = SolidColor(colors.text),
            interactionSource = interactions,
            keyboardOptions = if (onCommit != null && singleLine) KeyboardOptions(imeAction = ImeAction.Done)
                else KeyboardOptions.Default,
            keyboardActions = if (onCommit == null) KeyboardActions.Default
                else KeyboardActions(onDone = { onCommit(value.text) }),
            decorationBox = { editor ->
                Box(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = definition.dp("height"))
                        .frame(frame, definition.dp("radius"), border, focused)
                        .padding(horizontal = definition.dp("paddingInline") + border,
                            vertical = definition.dp("paddingBlock") + border),
                    contentAlignment = Alignment.CenterStart,
                ) { editor() }
            },
        )
    }
}
