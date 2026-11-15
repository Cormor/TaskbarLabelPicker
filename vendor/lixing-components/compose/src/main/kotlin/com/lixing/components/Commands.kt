package com.lixing.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuBarScope

private val button = LixingDesign.component("C02")
private val disclosure = LixingDesign.component("C03")
private val navigation = LixingDesign.component("C09")
private val surface = LixingDesign.component("C10")
private val segments = LixingDesign.component("C20")
private val tabs = LixingDesign.component("C25")

enum class ButtonVariant(internal val key: String) {
    Default("default"), Primary("primary"), Quiet("quiet"), Danger("danger"), Selected("selected"),
}

/** C02. An optional icon slot shares the label's single keyboard and pointer target. */
@Composable
fun LixingButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: ButtonVariant = ButtonVariant.Default,
    icon: (@Composable () -> Unit)? = null,
) {
    val selection = if (variant == ButtonVariant.Selected) Modifier.semantics { selected = true }
        else Modifier
    ButtonControl(label, onClick, modifier.then(selection), enabled, variant, icon, checked = null)
}

/** C02. A persistent toggle exposes both Off and On and leaves adoption to the caller. */
@Composable
fun LixingToggleButton(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
) {
    val variant = when {
        primary -> ButtonVariant.Primary
        checked -> ButtonVariant.Selected
        else -> ButtonVariant.Default
    }
    ButtonControl(label, { onCheckedChange(!checked) }, modifier, enabled, variant, icon, checked)
}

@Composable
private fun ButtonControl(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    variant: ButtonVariant,
    icon: (@Composable () -> Unit)?,
    checked: Boolean?,
) {
    val latched = checked == true || variant == ButtonVariant.Selected
    Control(button, onClick, modifier, enabled, variant = button.variant(variant.key),
        centerContent = true, keyboardFocusOnly = true, checked = checked,
        insetBottom = if (latched) button.dp("persistentEdge") else 0.dp,
        pressedInset = if (variant == ButtonVariant.Danger) button.dp("pressedInset") else 0.dp) { text ->
        if (icon != null) Box(Modifier.size(button.dp("icon")), contentAlignment = Alignment.Center) { icon() }
        ControlText(label, text, weight = FontWeight.SemiBold,
            underlineOffsetEm = if (latched) button.number("underlineOffsetEm") else 0f)
    }
}

/** C03. The consumer owns expanded state, including closing from content if required. */
@Composable
fun LixingDisclosure(
    label: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    stateLabel: String? = null,
    content: @Composable () -> Unit,
) {
    val toggle = { onExpandedChange(!expanded) }
    Column(modifier) {
        Control(disclosure, toggle,
            Modifier.fillMaxWidth().semantics {
                stateDescription = stateLabel ?: if (expanded) "已展开" else "已收起"
                if (enabled) {
                    val action = { toggle(); true }
                    if (expanded) collapse(action = action) else expand(action = action)
                }
            }, enabled, keyboardFocusOnly = true) { text ->
            val rotation = when {
                expanded -> 0f
                LocalLayoutDirection.current == LayoutDirection.Ltr -> -90f
                else -> 90f
            }
            Box(Modifier.rotate(rotation)) {
                Chevron(text, disclosure.dp("indicator"), verticalInset = .375f)
            }
            ControlText(label, text)
        }
        if (expanded) {
            Spacer(Modifier.height(disclosure.dp("gap")))
            content()
        }
    }
}

/** C09. The window supplies the navigation column's width. */
@Composable
fun LixingNavigationGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(navigation.dp("gap")), content = content)
}

/** C09. All rows reserve an inboard marker slot, independent of focus. */
@Composable
fun LixingNavigationItem(
    label: String,
    current: Boolean,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    val markerColor = if (current) {
        val roles = requireNotNull(navigation.roles(if (enabled) "current" else "disabled"))
        LixingTheme.colors[roles.getString("marker")]
    } else Color.Transparent
    Control(navigation, onNavigate,
        modifier.heightIn(min = navigation.dp("height")).height(IntrinsicSize.Min)
            .semantics { selected = current },
        enabled, state = if (current) "current" else "default", keyboardFocusOnly = true) { text ->
        Box(Modifier.width(navigation.dp("marker")).fillMaxHeight().background(markerColor, CircleShape))
        if (icon != null) Box(Modifier.size(navigation.dp("icon")), contentAlignment = Alignment.Center) { icon() }
        ControlText(label, text)
    }
}

/** C10. Layout and content stay in Compose and can reflow with the host. */
@Composable
fun LixingSurface(
    modifier: Modifier = Modifier,
    title: String? = null,
    raised: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val style = surface.frame(LixingTheme.colors, state = if (raised) "raised" else "default")
    Column(modifier
        .frame(style, surface.dp("radius"), surface.dp("border"))
        .padding(surface.dp("padding") + surface.dp("border")),
        verticalArrangement = Arrangement.spacedBy(LixingDesign.space(3))) {
        if (title != null) {
            LixingText(title, size = LixingDesign.textSize("lg"), weight = FontWeight.SemiBold)
        }
        content()
        if (actions != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(LixingDesign.space(2)), content = actions)
        }
    }
}

/** C17. Compose forwards menu structure, keys and accessibility to the desktop host. */
@Composable
fun FrameWindowScope.LixingMenuBar(content: @Composable MenuBarScope.() -> Unit) {
    MenuBar(content = content)
}

/** Stable IDs allow labels and availability to change independently from adopted values. */
data class ChoiceOption(val id: String, val label: String, val enabled: Boolean = true)

@Composable
fun LixingSegments(
    options: List<ChoiceOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChoiceStrip(options, selectedId, onSelect, modifier, false)
}

/** C25. Arrows browse, Enter or Space activates, and the selected panel follows the value. */
@Composable
fun LixingTabs(
    options: List<ChoiceOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    panel: @Composable (String?) -> Unit,
) {
    Column(modifier) {
        ChoiceStrip(options, selectedId, onSelect, Modifier, true)
        Spacer(Modifier.height(tabs.dp("panelGap")))
        panel(selectedId)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceStrip(
    options: List<ChoiceOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier,
    isTabs: Boolean,
) {
    val available = options.filter(ChoiceOption::enabled)
    val groupFocus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val inputMode = LocalInputModeManager.current
    var browseId by remember { mutableStateOf<String?>(null) }
    val active = available.firstOrNull { it.id == browseId }
        ?: available.firstOrNull { it.id == selectedId } ?: available.firstOrNull()
    val definition = if (isTabs) tabs else segments
    FlowRow(modifier.selectableGroup().focusRequester(groupFocus)
        .onFocusChanged { if (!it.isFocused) browseId = null }
        .onPreviewKeyEvent { event ->
            if (!isTabs && event.type == KeyEventType.KeyDown) inputMode.requestInputMode(InputMode.Keyboard)
            if (active == null) return@onPreviewKeyEvent false
            if (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar) {
                if (event.type == KeyEventType.KeyUp && active.id != selectedId) onSelect(active.id)
                return@onPreviewKeyEvent true
            }
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val index = available.indexOf(active)
            val next = when (event.key) {
                Key.DirectionLeft, Key.DirectionUp -> (index - 1 + available.size) % available.size
                Key.DirectionRight, Key.DirectionDown -> (index + 1) % available.size
                Key.MoveHome -> if (isTabs) 0 else return@onPreviewKeyEvent false
                Key.MoveEnd -> if (isTabs) available.lastIndex else return@onPreviewKeyEvent false
                else -> return@onPreviewKeyEvent false
            }
            val id = available[next].id
            browseId = id
            if (!isTabs && id != selectedId) onSelect(id)
            true
        }.focusable(active != null, interaction)
        .semantics { if (active != null) contentDescription = active.label },
        horizontalArrangement = Arrangement.spacedBy(definition.dp("gap")),
        verticalArrangement = Arrangement.spacedBy(LixingDesign.space(2)),
    ) {
        for (option in options) key(option.id) {
            val adopted = option.id == selectedId
            val optionModifier = Modifier.focusProperties { canFocus = false }
                .semantics { selected = adopted }
            Control(definition, {
                groupFocus.requestFocus()
                browseId = option.id
                if (!adopted) onSelect(option.id)
            }, optionModifier, option.enabled,
                state = if (adopted) "selected" else "default",
                role = if (isTabs) Role.Tab else Role.RadioButton,
                marker = if (isTabs && adopted) tabs.dp("selectedEdge") else 0.dp,
                bottomMarker = true, focusOverride = focused && active?.id == option.id,
                keyboardFocusOnly = !isTabs) { text ->
                if (isTabs) LixingText(option.label, color = text)
                else {
                    SegmentMarker(adopted, option.enabled)
                    ControlText(option.label, text)
                }
            }
        }
    }
}

@Composable
private fun SegmentMarker(selected: Boolean, enabled: Boolean) {
    val roles = requireNotNull(segments.roles(when {
        !enabled -> "disabled"
        selected -> "selected"
        else -> "default"
    }))
    val colors = LixingTheme.colors
    val border = segments.dp("border")
    Box(Modifier.size(segments.dp("mark"))
        .background(colors[roles.getString("markFill")], CircleShape)
        .border(border, colors[roles.getString("markEdge")], CircleShape),
        contentAlignment = Alignment.Center) {
        if (selected) {
            Box(Modifier.size(segments.dp("dot"))
                .background(colors[roles.getString("dotFill")], CircleShape)
                .border(border, colors[roles.getString("dotEdge")], CircleShape))
        }
    }
}
