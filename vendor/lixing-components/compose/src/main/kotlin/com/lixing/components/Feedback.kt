package com.lixing.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

private val status = LixingDesign.component("C08")
private val messageDefinition = LixingDesign.component("C31")
private val progress = LixingDesign.component("C32")
private val table = LixingDesign.component("C34")
private val meter = LixingDesign.component("C38")

@Composable
fun LixingStatus(
    message: String,
    tone: Tone = Tone.Info,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    urgent: Boolean = false,
) {
    Notice(status, message, tone, modifier, urgent, action)
}

// Keep the original JVM entry for callers already compiled against 0.1.4.
@Deprecated("Use LixingMessage with a dismiss variant.", level = DeprecationLevel.HIDDEN)
@Composable
fun LixingMessage(
    message: String,
    tone: Tone = Tone.Info,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    dismissLabel: String = "关闭",
) {
    LixingMessage(message, tone, modifier, onDismiss, dismissLabel, ButtonVariant.Quiet)
}

/** C31. The caller controls visibility and the next focus target if the action removes this notice. */
@Composable
fun LixingMessage(
    message: String,
    tone: Tone = Tone.Info,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    dismissLabel: String = "关闭",
    dismissVariant: ButtonVariant = ButtonVariant.Quiet,
) {
    Notice(messageDefinition, message, tone, modifier, false,
        action = if (onDismiss == null) null else {
            { LixingButton(dismissLabel, onDismiss, variant = dismissVariant) }
        })
}

@Composable
private fun Notice(
    definition: ComponentDefinition,
    message: String,
    tone: Tone,
    modifier: Modifier,
    urgent: Boolean,
    action: (@Composable () -> Unit)?,
) {
    val colors = LixingTheme.colors
    val normal = requireNotNull(definition.roles("default"))
    val toneEdge = colors[requireNotNull(definition.roles(tone.role)).getString("edge")]
    val frame = Frame(colors[normal.getString("fill")], colors[normal.getString("border")],
        colors[normal.getString("text")], colors[LixingDesign.focusRole])
    val border = definition.dp("border")
    val marker = definition.dp("toneEdge")
    Row(
        modifier.frame(frame, definition.dp("radius"), border, marker = marker, markerColor = toneEdge)
            .padding(start = definition.dp("paddingInline") + border + marker,
                end = definition.dp("paddingInline") + border,
                top = definition.dp("paddingBlock") + border, bottom = definition.dp("paddingBlock") + border),
        horizontalArrangement = Arrangement.spacedBy(definition.dp("gap")),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LixingText(message, Modifier.weight(1f, fill = false).semantics {
            liveRegion = if (urgent) LiveRegionMode.Assertive else LiveRegionMode.Polite
        }, color = frame.text)
        action?.invoke()
    }
}

@Composable
fun LixingMeter(
    label: String,
    value: Float?,
    valueText: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Info,
) {
    val em = with(LocalDensity.current) { LixingDesign.textSize("md").toDp() }
    val shown = value?.takeIf(Float::isFinite)?.coerceIn(0f, 1f)
    val state = if (tone == Tone.Warning || tone == Tone.Danger) tone.role else "default"
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LixingDesign.space(1))) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LixingDesign.space(2))) {
            LixingText(label, Modifier.weight(1f), size = LixingDesign.textSize("sm"))
            LixingText(valueText, color = LixingTheme.colors.textMuted, size = LixingDesign.textSize("sm"))
        }
        DataTrack(meter, state, shown, em * meter.number("heightEm"), false, "$label $valueText")
    }
}

/** C32. Supply a localized label that includes the task and waiting state when value is null. */
@Composable
fun LixingProgress(
    label: String,
    value: Float?,
    modifier: Modifier = Modifier,
) {
    val fraction = value?.takeIf(Float::isFinite)?.coerceIn(0f, 1f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LixingDesign.space(1))) {
        LixingText(label, size = LixingDesign.textSize("sm"))
        DataTrack(progress, "default", fraction, progress.dp("height"), true, label)
    }
}

@Composable
private fun DataTrack(
    definition: ComponentDefinition,
    state: String,
    value: Float?,
    height: Dp,
    indeterminate: Boolean,
    label: String,
) {
    val colors = LixingTheme.colors
    val normal = requireNotNull(definition.roles("default"))
    val fill = colors[definition.roles(state)?.optString("fill", normal.getString("fill")) ?: normal.getString("fill")]
    val track = colors[normal.getString("track")]
    val edge = colors[normal.getString("edge")]
    val thickness = definition.dp("border")
    Canvas(Modifier.fillMaxWidth().height(height).semantics {
        contentDescription = label
        if (value != null) progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
        else if (indeterminate) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
    }) {
        val radius = definition.dp("radius").toPx().coerceAtMost(size.minDimension / 2)
        val inset = thickness.toPx()
        roundedFrame(track, edge, radius, inset)
        if (size.width <= inset * 2 || size.height <= inset * 2) return@Canvas
        val inside = Rect(inset, inset, size.width - inset, size.height - inset)
        val clip = Path().apply { addRoundRect(RoundRect(inside, CornerRadius((radius - inset).coerceAtLeast(0f)))) }
        clipPath(clip) {
            if (value != null && value > 0f) {
                val width = inside.width * value
                val x = if (layoutDirection == LayoutDirection.Ltr) inside.left else inside.right - width
                drawRect(fill, Offset(x, inside.top), Size(width, inside.height))
            } else if (value == null && indeterminate) {
                val width = inside.height
                var x = inside.left - width
                while (x < inside.right) {
                    drawLine(fill, Offset(x, inside.bottom), Offset(x + width, inside.top), width / 3)
                    x += width
                }
            }
        }
    }
}

data class TableColumn(val label: String, val numeric: Boolean = false, val weight: Float = 1f)
data class TableRow(val id: String, val cells: List<String>)

/** C34. Read-only comparison or optional whole-row selection. Sorting and editing are consumer actions. */
@Composable
fun LixingTable(
    columns: List<TableColumn>,
    rows: List<TableRow>,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    onSelect: ((String) -> Unit)? = null,
) {
    require(columns.isNotEmpty())
    val selectableRows = onSelect != null
    Column(modifier.semantics { collectionInfo = CollectionInfo(rows.size + 1, columns.size) }) {
        TableLine(columns, columns.map(TableColumn::label), 0, false, selectableRows)
        for ((index, row) in rows.withIndex()) key(row.id) {
            TableLine(columns, row.cells, index + 1, row.id == selectedId, selectableRows,
                onSelect = onSelect?.let { select -> { select(row.id) } })
        }
    }
}

@Composable
private fun TableLine(
    columns: List<TableColumn>,
    cells: List<String>,
    row: Int,
    selected: Boolean,
    reserveMarker: Boolean,
    onSelect: (() -> Unit)? = null,
) {
    val colors = LixingTheme.colors
    val state = if (row == 0) "heading" else if (selected) "selected" else "default"
    val roles = requireNotNull(table.roles(state))
    val markerInset = table.dp("markerInset")
    val marker = table.dp("marker")
    val inlinePadding = table.dp("paddingInline")
    val firstCellPadding = if (reserveMarker) markerInset + marker + table.dp("markerGap") else inlinePadding
    var rowModifier = Modifier.fillMaxWidth()
    if (onSelect == null) {
        rowModifier = rowModifier.background(colors[roles.getString("fill")])
    } else {
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val pressed by interaction.collectIsPressedAsState()
        val focused by interaction.collectIsFocusedAsState()
        val fill = colors[roles.getString(when {
            pressed -> "pressed"
            hovered -> "hover"
            else -> "fill"
        })]
        rowModifier = rowModifier.background(fill)
        if (pressed || hovered) {
            rowModifier = rowModifier.border(table.dp(if (pressed) "pressedEdge" else "hoverEdge"),
                colors[roles.getString("interactionEdge")])
        }
        val focusInset = table.dp("focusInset")
        val focusColor = colors[roles.getString(if (pressed) "pressedFocus" else "focus")]
        val markerColor = if (selected) colors[roles.getString("marker")] else Color.Transparent
        rowModifier = rowModifier.drawBehind {
            if (focused) focusRing(focusColor, 0f, LixingDesign.focusWidth, focusInset)
            if (selected) {
                val inset = markerInset.toPx()
                val x = inset + marker.toPx() / 2
                val markX = if (layoutDirection == LayoutDirection.Ltr) x else size.width - x
                drawLine(markerColor, Offset(markX, inset), Offset(markX, size.height - inset), marker.toPx())
            }
        }.selectable(selected, interaction, indication = null, role = Role.RadioButton, onClick = onSelect)
    }
    Row(rowModifier) {
        for ((index, column) in columns.withIndex()) {
            val cell = Modifier.weight(column.weight)
                .padding(start = if (index == 0) firstCellPadding else inlinePadding,
                    end = inlinePadding, top = table.dp("paddingBlock"), bottom = table.dp("paddingBlock"))
                .semantics {
                    collectionItemInfo = CollectionItemInfo(row, 1, index, 1)
                    if (row == 0) heading()
                }
            val style = textStyle(colors[roles.getString("text")],
                weight = if (row == 0) FontWeight.SemiBold else FontWeight.Normal)
            androidx.compose.foundation.text.BasicText(cells.getOrElse(index) { "" }, cell, style.copy(
                fontFamily = if (column.numeric && row > 0) FontFamily.Monospace else style.fontFamily,
                textAlign = if (column.numeric) TextAlign.End else TextAlign.Start,
            ))
        }
    }
    Box(Modifier.fillMaxWidth().height(table.dp("divider")).background(colors["border"]))
}
