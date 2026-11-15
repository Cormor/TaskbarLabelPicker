package com.lixing.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val checkbox = LixingDesign.component("C18")
private val switch = LixingDesign.component("C19")
private val choiceRowRadius = LixingDesign.tokens.getJSONObject("radius").getInt("md").dp

@Composable
fun LixingCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    LixingTriStateCheckbox(label, if (checked) ToggleableState.On else ToggleableState.Off,
        { onCheckedChange(!checked) }, modifier, enabled)
}

@Composable
fun LixingTriStateCheckbox(
    label: String,
    state: ToggleableState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    ChoiceRow(label, enabled, interaction, modifier
        .onPreviewKeyEvent { it.key == Key.Enter || it.key == Key.NumPadEnter }
        .triStateToggleable(state, interaction, indication = null, enabled = enabled,
            role = Role.Checkbox, onClick = onClick)) { focusColor ->
        ChoiceMark(state == ToggleableState.On, state == ToggleableState.Indeterminate,
            enabled, focused = focused, focusColor = focusColor)
    }
}

@Composable
fun LixingRadio(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    ChoiceRow(label, enabled, interaction, modifier
        .selectable(selected, interaction, indication = null, enabled = enabled,
            role = Role.RadioButton, onClick = onSelect)) { focusColor ->
        ChoiceMark(selected, false, enabled, radio = true, focused = focused, focusColor = focusColor)
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    enabled: Boolean,
    interaction: MutableInteractionSource,
    modifier: Modifier,
    mark: @Composable (Color) -> Unit,
) {
    val colors = LixingTheme.colors
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val activePress = enabled && pressed
    val activeHover = enabled && hovered
    val fill = when {
        activePress -> colors["controlPressed"]
        activeHover -> colors["controlHover"]
        else -> Color.Transparent
    }
    val shape = RoundedCornerShape(choiceRowRadius)
    val pointerEdge = when {
        activePress -> Modifier.border(1.dp, colors["accent"], shape)
        activeHover -> Modifier.border(1.dp, colors["borderControl"], shape)
        else -> Modifier
    }
    Row(modifier.heightIn(min = checkbox.dp("height"))
        .background(fill, shape)
        .then(pointerEdge)
        .padding(horizontal = LixingDesign.space(2)),
        horizontalArrangement = Arrangement.spacedBy(checkbox.dp("gap")),
        verticalAlignment = Alignment.CenterVertically) {
        // The marker's outline sits on the row's press fill while the button is held.
        mark(colors[if (activePress) "accentEdge" else LixingDesign.focusRole])
        LixingText(label, color = colors[if (enabled) "text" else "disabledText"])
    }
}

@Composable
internal fun ChoiceMark(
    checked: Boolean,
    mixed: Boolean,
    enabled: Boolean,
    radio: Boolean = false,
    focused: Boolean = false,
    focusColor: Color? = null,
) {
    val colors = LixingTheme.colors
    val state = when {
        mixed -> "mixed"
        checked -> "selected"
        else -> "default"
    }
    val selected = checkbox.frame(colors, enabled, if (radio) "default" else state)
    val mark = if (enabled) colors[checkbox.roles(state)?.optString("mark", "text") ?: "text"]
        else colors["disabledText"]
    Canvas(Modifier.size(checkbox.dp("marker"))) {
        val radius = if (radio) center.x else checkbox.dp("markerRadius").toPx()
        roundedFrame(selected.fill, selected.edge, radius, 1.dp.toPx())
        if (radio && checked) {
            drawCircle(if (enabled) colors["onChoiceFill"] else mark, size.minDimension * .29f)
            if (enabled) drawCircle(colors["choiceFill"], size.minDimension * .22f)
        } else if (checked || mixed) checkMark(mark, mixed)
        if (focused && enabled) focusRing(focusColor ?: selected.focus, radius)
    }
}

@Composable
fun LixingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LixingTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val state = if (!enabled) "disabled" else if (checked) "on" else "default"
    val roles = requireNotNull(switch.roles(state))
    val edge = colors[roles.getString("edge")]
    val fill = colors[roles.optString("fill", "surface")]
    val knob = colors[roles.optString("knob", if (enabled) "text" else "disabledText")]
    val density = LocalDensity.current
    val em = with(density) { LixingDesign.textSize("md").toDp() }
    val border = switch.dp("border")
    val radius = switch.dp("radius")
    val shellFill = when {
        !enabled -> fill
        pressed -> colors["controlPressed"]
        hovered -> colors["controlHover"]
        else -> colors.surface
    }
    val outer = Frame(shellFill, if (enabled) colors["borderControl"] else colors["disabledEdge"],
        if (enabled) colors.text else colors["disabledText"], colors[LixingDesign.focusRole])
    Row(modifier.heightIn(min = switch.dp("height"))
        .frame(outer, choiceRowRadius, border, enabled && focused)
        .toggleable(checked, interaction, indication = null, enabled = enabled,
            role = Role.Switch, onValueChange = onCheckedChange)
        .padding(horizontal = LixingDesign.space(2)),
        horizontalArrangement = Arrangement.spacedBy(LixingDesign.space(2)),
        verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(em * switch.number("trackWidthEm"), em * switch.number("trackHeightEm"))) {
            val dot = (em * switch.number("knobEm")).toPx() / 2
            val inset = (size.height / 2 - dot).coerceAtLeast(0f)
            val knobAtEnd = checked == (layoutDirection == LayoutDirection.Ltr)
            roundedFrame(fill, edge, radius.toPx(), border.toPx())
            drawCircle(knob, dot, Offset(if (knobAtEnd) size.width - inset - dot else inset + dot, center.y))
        }
        LixingText(label, color = outer.text)
    }
}
