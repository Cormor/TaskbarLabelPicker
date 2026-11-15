package com.lixing.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import kotlin.math.round

private val slider = LixingDesign.component("C07")

/** C07. A zero step is continuous, with 1% keyboard increments. */
@Composable
fun LixingSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    step: Float = 0f,
    enabled: Boolean = true,
    valueText: String? = null,
    touch: Boolean = false,
) {
    val min = range.start
    val max = range.endInclusive
    val length = max - min
    require(min.isFinite() && max.isFinite() && length.isFinite() && length > 0f)
    require(step.isFinite() && step >= 0f)
    val current = if (value.isFinite()) value.coerceIn(min, max) else min
    val currentCallback by rememberUpdatedState(onValueChange)
    val increment = if (step > 0f) step else length / 100
    val discreteSteps = if (step > 0f) (length / step).roundToInt().coerceAtLeast(1) - 1 else 0
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    var pressed by remember { mutableStateOf(false) }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val colors = LixingTheme.colors
    val normal = requireNotNull(slider.roles("default"))
    val state = when {
        !enabled -> slider.roles("disabled")
        pressed -> slider.roles("pressed")
        hovered -> slider.roles("hover")
        else -> normal
    }
    fun color(part: String) = colors[state?.optString(part, normal.getString(part)) ?: normal.getString(part)]
    fun set(raw: Float): Boolean {
        val clamped = raw.coerceIn(min, max)
        val next = if (step > 0f && clamped != max) {
            (min + round((clamped - min) / step) * step).coerceIn(min, max)
        } else clamped
        if (next == current) return false
        currentCallback(next)
        return true
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(LixingDesign.space(2))) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LixingDesign.space(2))) {
            LixingText(label, Modifier.weight(1f), size = LixingDesign.textSize("sm"))
            if (valueText != null) LixingText(valueText, color = colors.textMuted, size = LixingDesign.textSize("sm"))
        }
        val thumb = slider.dp("thumb")
        val hitHeight = slider.dp(if (touch) "touchHeight" else "height")
        val pointerChange by rememberUpdatedState<(Float, Float, Float) -> Unit> { x, width, diameter ->
            val fraction = ((x - diameter / 2) / (width - diameter).coerceAtLeast(1f)).coerceIn(0f, 1f)
            set(min + length * if (rtl) 1 - fraction else fraction)
        }
        // Canvas is spacer-backed and needs a measured height for pointer hit testing.
        Canvas(
            Modifier.fillMaxWidth().height(hitHeight)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val target = when (event.key) {
                        Key.DirectionLeft -> current + if (rtl) increment else -increment
                        Key.DirectionRight -> current + if (rtl) -increment else increment
                        Key.DirectionUp -> current + increment
                        Key.DirectionDown -> current - increment
                        Key.MoveHome -> min
                        Key.MoveEnd -> max
                        else -> return@onPreviewKeyEvent false
                    }
                    set(target)
                    true
                }
                .focusable(enabled, interaction)
                .hoverable(interaction, enabled)
                .pointerInput(enabled, thumb) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun move(x: Float) {
                            val width = size.width.toFloat()
                            pointerChange(x, width, thumb.toPx().coerceAtMost(width))
                        }
                        focus.requestFocus()
                        pressed = true
                        try {
                            // Claim horizontal movement only; a parent can consume vertical scrolling.
                            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                                change.consume()
                            }
                            if (start == null) {
                                val up = currentEvent.changes.firstOrNull { it.id == down.id }
                                if (up?.changedToUp() == true) {
                                    up.consume()
                                    move(up.position.x)
                                }
                            } else {
                                move(start.position.x)
                                horizontalDrag(start.id) { change ->
                                    change.consume()
                                    move(change.position.x)
                                }
                            }
                        } finally {
                            pressed = false
                        }
                    }
                }
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(current, range, discreteSteps)
                    if (valueText != null) stateDescription = valueText
                    if (enabled) setProgress { set(it) } else disabled()
                },
        ) {
            val fraction = (current - min) / length
            val diameter = thumb.toPx().coerceAtMost(size.width)
            val radius = diameter / 2
            val x = radius + (size.width - diameter) * if (rtl) 1 - fraction else fraction
            val thickness = slider.dp("track").toPx()
            roundedFrame(color("track"), color("track"), thickness / 2, 0f,
                Offset(radius, center.y - thickness / 2), Size(size.width - diameter, thickness))
            drawCircle(if (enabled) color("thumbEdge") else color("track"), radius, Offset(x, center.y))
            drawCircle(color("thumb"), (radius - slider.dp("thumbBorder").toPx()).coerceAtLeast(0f),
                Offset(x, center.y))
            if (focused) focusRing(colors[LixingDesign.focusRole], thumb.toPx() / 2)
        }
    }
}
