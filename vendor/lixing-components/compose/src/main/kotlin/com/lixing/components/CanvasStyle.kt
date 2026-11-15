package com.lixing.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal data class Frame(
    val fill: Color,
    val edge: Color,
    val text: Color,
    val focus: Color,
)

internal fun ComponentDefinition.frame(
    colors: LixingColors,
    enabled: Boolean = true,
    state: String = "default",
    hovered: Boolean = false,
    pressed: Boolean = false,
    variant: JSONObject? = null,
): Frame {
    val normal = roles("default")
    val overlay = if (enabled) variant ?: roles(state) else roles("disabled")
    fun color(part: String, fallback: String): Color {
        val defaultRole = normal?.optString(part, fallback) ?: fallback
        return colors[overlay?.optString(part, defaultRole) ?: defaultRole]
    }
    val fill = when {
        enabled && pressed -> color("pressed", "controlPressed")
        enabled && hovered -> color("hover", "controlHover")
        else -> color("fill", "surface")
    }
    // The outer ring lies on the host surface; an inset ring picks its own role.
    return Frame(fill, color("edge", "transparent"), color("text", "text"),
        colors[LixingDesign.focusRole])
}

internal fun DrawScope.roundedFrame(
    fill: Color,
    edge: Color,
    radius: Float,
    border: Float,
    topLeft: Offset = Offset.Zero,
    bounds: Size = size,
) {
    if (bounds.width <= 0f || bounds.height <= 0f) return
    val r = radius.coerceAtMost(minOf(bounds.width, bounds.height) / 2)
    drawRoundRect(fill, topLeft, bounds, CornerRadius(r))
    if (border > 0 && edge.alpha > 0 && bounds.width > border && bounds.height > border) {
        val inset = border / 2
        drawRoundRect(edge, topLeft + Offset(inset, inset),
            Size(bounds.width - border, bounds.height - border),
            CornerRadius((r - inset).coerceAtLeast(0f)), style = Stroke(border))
    }
}

internal fun DrawScope.focusRing(
    color: Color,
    radius: Float,
    innerWidth: Dp? = null,
    innerGap: Dp = 0.dp,
    outerGap: Dp = LixingDesign.focusOffset,
) {
    val width = (innerWidth ?: LixingDesign.focusWidth).toPx()
    val offset = if (innerWidth != null) innerGap.toPx() + width / 2
        else -outerGap.toPx() - width / 2
    if (size.width > offset * 2 && size.height > offset * 2) {
        drawRoundRect(color, Offset(offset, offset),
            Size(size.width - 2 * offset, size.height - 2 * offset),
            CornerRadius((radius - offset).coerceAtLeast(0f)), style = Stroke(width))
    }
}

internal fun Modifier.frame(
    frame: Frame,
    radius: Dp,
    border: Dp,
    focused: Boolean = false,
    innerFocus: Dp? = null,
    marker: Dp = 0.dp,
    bottomMarker: Boolean = false,
    markerColor: Color = frame.edge,
    focusOffset: Dp = LixingDesign.focusOffset,
    insetBottom: Dp = 0.dp,
    insetEdge: Dp = 0.dp,
): Modifier {
    val shape = RoundedCornerShape(radius)
    val surface = background(frame.fill, shape).then(
        if (border > 0.dp && frame.edge.alpha > 0f) Modifier.border(border, frame.edge, shape)
        else Modifier,
    )
    if (!focused && marker == 0.dp && insetBottom == 0.dp && insetEdge == 0.dp) return surface
    return surface.drawBehind {
        if (marker > 0.dp) {
            val line = marker.toPx()
            val pad = (radius.toPx() / 2).coerceAtMost(minOf(size.width, size.height) / 4)
            if (bottomMarker) {
                drawLine(markerColor, Offset(pad, size.height - line / 2),
                    Offset(size.width - pad, size.height - line / 2), line, StrokeCap.Round)
            } else {
                val x = if (layoutDirection == LayoutDirection.Ltr) line else size.width - line
                drawLine(markerColor, Offset(x, pad), Offset(x, size.height - pad), line, StrokeCap.Round)
            }
        }
        val shell = border.toPx()
        val r = radius.toPx()
        if (insetBottom > 0.dp && size.minDimension > 2 * shell) {
            val clip = Path().apply {
                addRoundRect(RoundRect(Rect(shell, shell, size.width - shell, size.height - shell),
                    CornerRadius((r - shell).coerceAtLeast(0f))))
            }
            val line = insetBottom.toPx()
            clipPath(clip) {
                drawRect(frame.text, Offset(shell, size.height - shell - line),
                    Size(size.width - 2 * shell, line))
            }
        }
        if (insetEdge > 0.dp) {
            val stroke = insetEdge.toPx()
            val inset = shell + stroke / 2
            if (size.minDimension > 2 * inset) {
                drawRoundRect(frame.text, Offset(inset, inset),
                    Size(size.width - 2 * inset, size.height - 2 * inset),
                    CornerRadius((r - inset).coerceAtLeast(0f)), style = Stroke(stroke))
            }
        }
        if (focused) focusRing(frame.focus, r, innerFocus, border, focusOffset)
    }
}

@Composable
internal fun Control(
    definition: ComponentDefinition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    state: String = "default",
    variant: JSONObject? = null,
    role: Role = Role.Button,
    marker: Dp = 0.dp,
    bottomMarker: Boolean = false,
    markerColor: Color? = null,
    iconOnly: Boolean = false,
    centerContent: Boolean = false,
    focusOverride: Boolean? = null,
    keyboardFocusOnly: Boolean = false,
    checked: Boolean? = null,
    insetBottom: Dp = 0.dp,
    pressedInset: Dp = 0.dp,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.(Color) -> Unit,
) {
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val inputMode = LocalInputModeManager.current
    val hasVisibleFocus = (focusOverride ?: focused) &&
        (!keyboardFocusOnly || inputMode.inputMode == InputMode.Keyboard)
    val keyInput = if (keyboardFocusOnly) Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) inputMode.requestInputMode(InputMode.Keyboard)
        false
    } else Modifier
    val colors = LixingTheme.colors
    val style = if (enabled && variant?.has("pointerFill") == true) {
        val idle = definition.frame(colors, state = state, variant = variant)
        if (hovered) idle.copy(fill = colors[variant.getString("pointerFill")], text = colors[variant.getString("pointerText")])
        else idle
    } else definition.frame(colors, enabled, state, hovered, pressed, variant)
    val action = if (checked == null) {
        Modifier.clickable(interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
    } else {
        Modifier.toggleable(checked, interaction, indication = null, enabled = enabled, role = role) { onClick() }
    }
    val border = definition.optionalDp("border", 1.dp)
    val height = definition.dp("height")
    val inlinePadding = if (iconOnly) 0.dp else definition.dp("paddingInline")
    Row(
        keyInput.then(modifier).defaultMinSize(minWidth = height, minHeight = height)
            .frame(style, definition.optionalDp("radius", 0.dp), border, hasVisibleFocus,
                marker = marker, bottomMarker = bottomMarker, markerColor = markerColor ?: style.edge,
                focusOffset = definition.optionalDp("focusOffset", LixingDesign.focusOffset),
                insetBottom = insetBottom, insetEdge = if (enabled && pressed) pressedInset else 0.dp)
            .then(action)
            .padding(
                start = inlinePadding + border + if (bottomMarker) 0.dp else marker,
                end = inlinePadding + border,
                top = definition.optionalDp("paddingBlock", LixingDesign.space(2)) + border,
                bottom = definition.optionalDp("paddingBlock", LixingDesign.space(2)) + border,
            ),
        horizontalArrangement = Arrangement.spacedBy(
            definition.optionalDp("contentGap", definition.optionalDp("gap", LixingDesign.space(2))),
            if (iconOnly || centerContent) Alignment.CenterHorizontally else Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(style.text) }
}

@Composable
internal fun Chevron(color: Color, size: Dp, expanded: Boolean = false, verticalInset: Float = .39f) {
    Canvas(Modifier.size(size)) {
        val left = Offset(this.size.width * .25f, this.size.height * if (expanded) 1f - verticalInset else verticalInset)
        val middle = Offset(center.x, this.size.height - left.y)
        val right = Offset(this.size.width * .75f, left.y)
        drawLine(color, left, middle, 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(color, middle, right, 1.5.dp.toPx(), StrokeCap.Round)
    }
}

internal fun DrawScope.checkMark(color: Color, mixed: Boolean = false) {
    val stroke = 2.dp.toPx()
    if (mixed) {
        drawLine(color, Offset(size.width * .25f, center.y), Offset(size.width * .75f, center.y),
            stroke, StrokeCap.Round)
    } else {
        val elbow = Offset(size.width * .43f, size.height * .66f)
        drawLine(color, Offset(size.width * .24f, size.height * .49f), elbow, stroke, StrokeCap.Round)
        drawLine(color, elbow, Offset(size.width * .75f, size.height * .33f), stroke, StrokeCap.Round)
    }
}
