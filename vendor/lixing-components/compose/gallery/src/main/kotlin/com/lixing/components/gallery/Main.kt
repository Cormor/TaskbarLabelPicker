package com.lixing.components.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.lixing.components.ButtonVariant
import com.lixing.components.ChoiceOption
import com.lixing.components.LixingButton
import com.lixing.components.LixingChoiceHost
import com.lixing.components.LixingCheckbox
import com.lixing.components.LixingDesign
import com.lixing.components.LixingDisclosure
import com.lixing.components.LixingMenuBar
import com.lixing.components.LixingMessage
import com.lixing.components.LixingMeter
import com.lixing.components.LixingNavigationGroup
import com.lixing.components.LixingNavigationItem
import com.lixing.components.LixingProgress
import com.lixing.components.LixingSegments
import com.lixing.components.LixingSelect
import com.lixing.components.LixingSlider
import com.lixing.components.LixingStatus
import com.lixing.components.LixingSuggestions
import com.lixing.components.LixingSurface
import com.lixing.components.LixingSwitch
import com.lixing.components.LixingTable
import com.lixing.components.LixingTabs
import com.lixing.components.LixingText
import com.lixing.components.LixingTextField
import com.lixing.components.LixingTheme
import com.lixing.components.LixingToggleButton
import com.lixing.components.TableColumn
import com.lixing.components.TableRow
import com.lixing.components.Tone

private val destinations = listOf(ChoiceOption("input", "输入与操作"), ChoiceOption("data", "信息与反馈"))
private val sources = listOf(
    ChoiceOption("microphone", "麦克风"),
    ChoiceOption("unavailable", "已拔出的设备", enabled = false),
    ChoiceOption("system", "系统音频"),
    ChoiceOption("both", "麦克风和系统音频同时输入"),
    ChoiceOption("line", "外接音频线路"),
    ChoiceOption("stereo", "立体声混音"),
    ChoiceOption("virtual", "Virtual cable"),
    ChoiceOption("test", "测试输入"),
)
private val views = listOf(ChoiceOption("reading", "读数"), ChoiceOption("records", "记录"))

fun main() = application {
    var dark by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf("input") }
    var commands by remember { mutableIntStateOf(0) }
    Window(onCloseRequest = ::exitApplication, title = "砺行 Compose 组件图廊",
        state = rememberWindowState(width = 900.dp, height = 820.dp)) {
        LixingMenuBar {
            Menu("图廊") {
                Item("输入与操作", onClick = { destination = "input" })
                Item("信息与反馈", onClick = { destination = "data" })
                Item(if (dark) "使用浅色" else "使用深色", onClick = { dark = !dark })
            }
        }
        LixingTheme(dark) {
            val padding = LixingDesign.space(4)
            LixingChoiceHost(Modifier.background(LixingTheme.colors.canvas)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding),
                    verticalArrangement = Arrangement.spacedBy(padding)) {
                    LixingText("砺行 Compose 组件图廊", size = LixingDesign.textSize("xl"), weight = FontWeight.Bold)
                    GalleryNav(destination, { destination = it }, dark, { dark = it })
                    if (destination == "input") Inputs(commands, { commands += 1 }) else Feedback()
                    LixingText("设计 ${LixingDesign.designVersion} · ${LixingDesign.definitionSha256.take(12)}",
                        color = LixingTheme.colors.textMuted, size = LixingDesign.textSize("sm"))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GalleryNav(destination: String, onNavigate: (String) -> Unit, dark: Boolean, onDark: (Boolean) -> Unit) {
    val gap = LixingDesign.space(3)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
        LixingNavigationGroup {
            for (item in destinations) {
                LixingNavigationItem(item.label, item.id == destination, { onNavigate(item.id) })
            }
        }
        LixingSwitch("深色主题", dark, onDark)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Inputs(commands: Int, onCommand: () -> Unit) {
    var selected by remember { mutableStateOf("microphone") }
    var text by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("自定义设备") }
    var committed by remember { mutableStateOf(draft) }
    var volume by remember { mutableFloatStateOf(25f) }
    var enabled by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val gap = LixingDesign.space(3)
    LixingSurface(Modifier.fillMaxWidth(), "命令与参数") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            LixingButton("运行一次", onCommand, variant = ButtonVariant.Primary)
            LixingButton("刷新列表", onCommand)
            LixingButton("预览列表", onCommand, variant = ButtonVariant.Quiet)
            LixingButton("移除示例", onCommand, variant = ButtonVariant.Danger)
            LixingToggleButton("置顶列表", pin, { pin = it })
        }
        LixingText("命令次数 $commands")
        LixingSelect("输入设备", sources, selected, { selected = it })
        LixingCheckbox("记住所选设备", enabled, { enabled = it })
        LixingSlider("输入增益", volume, { volume = it }, range = 0f..100f, step = 1f,
            valueText = "${volume.toInt()}%")
    }
    LixingSurface(Modifier.fillMaxWidth(), "编辑与建议", raised = true) {
        LixingTextField("显示名称", text, { text = it }, hint = "可输入中文并使用原生文本编辑快捷键")
        LixingSuggestions("设备别名", draft, { draft = it }, committed, sources,
            onCommit = { committed = it }, hint = "Enter 采用，Escape 关闭建议或恢复已采用值",
            commitOnFocusLost = true)
        LixingText("已采用的别名 · $committed", color = LixingTheme.colors.textMuted,
            size = LixingDesign.textSize("sm"))
        LixingDisclosure("详情", expanded, { expanded = it }) {
            LixingText("展开后可查看当前设置的说明。")
        }
    }
}

@Composable
private fun Feedback() {
    var mode by remember { mutableStateOf("unknown") }
    var selected by remember { mutableStateOf("reading") }
    var notice by remember { mutableStateOf(true) }
    var tableRow by remember { mutableStateOf<String?>(null) }
    val modes = listOf(ChoiceOption("unknown", "待测"), ChoiceOption("zero", "零值"), ChoiceOption("signal", "有信号"))
    LixingSurface(Modifier.fillMaxWidth(), "实时读数与操作结果") {
        LixingStatus("设备可用，可以开始采集。", Tone.Success)
        LixingSegments(modes, mode, { mode = it })
        LixingTabs(views, selected, { selected = it }) { tab ->
            if (tab == "reading") {
                val value = when (mode) { "zero" -> 0f; "signal" -> .68f; else -> null }
                LixingMeter("当前电平", value, value?.let { "${(it * 100).toInt()}%" } ?: "待测")
                LixingProgress("准备输入设备…", null, Modifier.padding(top = LixingDesign.space(3)))
                LixingProgress("任务进度 72%", .72f, Modifier.padding(top = LixingDesign.space(3)))
            } else {
                LixingTable(listOf(TableColumn("设备", weight = 2f), TableColumn("采样率（Hz）", numeric = true)),
                    listOf(TableRow("input", listOf("麦克风", "48000")),
                        TableRow("system", listOf("系统音频", "44100"))),
                    Modifier.fillMaxWidth(), tableRow, { tableRow = it })
            }
        }
        if (notice) LixingMessage("设备列表已刷新。", Tone.Info, onDismiss = { notice = false })
    }
}
