import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.lixing.components.ButtonVariant
import com.lixing.components.LixingButton
import com.lixing.components.LixingCheckbox
import com.lixing.components.LixingTheme
import com.lixing.update.LixingUpdater
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true

internal fun availableUpdate(state: JsonObject): String? =
    if (state.flag("verified") && state.flag("available")) {
        (state["release"] as? JsonObject)?.text("version")
    } else null

internal fun packagedUpdater(): LixingUpdater? {
    val resources = System.getProperty("compose.application.resources.dir") ?: return null
    val update = Path.of(resources, "update")
    val config = update.resolve("release-config.json")
    return if (Files.isRegularFile(config) && Files.isRegularFile(update.resolve("node.exe"))) {
        LixingUpdater(update, config)
    } else null
}

internal fun saveAutoUpdate(preference: Path, enabled: Boolean) {
    val temporary = Files.createTempFile(preference.parent, "auto-update-", ".tmp")
    try {
        Files.writeString(temporary, if (enabled) "1" else "0")
        Files.move(temporary, preference, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

@Composable
internal fun TaskbarUpdateWindow(
    visible: Boolean,
    logo: ImageBitmap,
    state: JsonObject,
    automatic: Boolean,
    busy: Boolean,
    failure: String?,
    onSetAutomatic: (Boolean) -> Unit,
    onAction: (String) -> Unit,
    onClose: () -> Unit,
) {
    Window(
        onCloseRequest = onClose,
        visible = visible,
        title = "任务栏标签更新",
        state = rememberWindowState(width = 510.dp, height = 450.dp),
        icon = BitmapPainter(logo),
    ) {
        LixingTheme {
            val colors = LixingTheme.colors
            val phase = state.text("phase")
            val version = availableUpdate(state)
            val release = state["release"] as? JsonObject
            val receipt = state["installation"] as? JsonObject
            Column(
                Modifier.fillMaxSize().background(colors.canvas).verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText(
                        "版本与更新",
                        Modifier.weight(1f),
                        style = TextStyle(color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                    )
                    LixingButton("关闭", onClose, variant = ButtonVariant.Quiet)
                }
                BasicText("当前版本 ${state.text("currentVersion") ?: "—"}",
                    style = TextStyle(color = colors.text, fontSize = 13.sp))
                val status = when (phase) {
                    "checking" -> "正在检查更新…"
                    "downloading" -> "正在下载已验证更新…"
                    "ready" -> "下载完成，可以安装"
                    "installing" -> "正在安装，配置器会重新启动"
                    "awaiting-restart" -> "正在等待新版本启动"
                    "current" -> "已是当前版本"
                    "available" -> if (version != null) "可更新到 $version" else "未找到可验证的安装包"
                    else -> null
                }
                val latestError = failure ?: state.text("error") ?: state.text("trustError")
                ((latestError ?: status) ?: "可检查已签名的更新。").let {
                    BasicText(it, style = TextStyle(color = colors.text, fontSize = 13.sp))
                }
                if (receipt?.text("phase") == "completed") {
                    BasicText("最近更新 ${receipt.text("version").orEmpty()} 已完成。",
                        style = TextStyle(color = colors.text, fontSize = 13.sp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LixingButton("检查更新", { onAction("check") }, enabled = !busy)
                    if (phase == "ready") {
                        LixingButton("安装并重启", { onAction("install") }, enabled = !busy)
                    } else if (version != null && state.flag("canSetup")) {
                        LixingButton("下载更新", { onAction("download") }, enabled = !busy)
                    }
                }
                LixingCheckbox("自动安装已验证更新", automatic, onSetAutomatic, enabled = !busy)
                if (state.flag("verified")) {
                    val notes = (release?.get("versionHistory") as? JsonArray).orEmpty()
                    for (entry in notes.filterIsInstance<JsonObject>()) {
                        BasicText(entry.text("version").orEmpty(),
                            style = TextStyle(color = colors.text, fontWeight = FontWeight.SemiBold))
                        BasicText(entry.text("notes").orEmpty(), style = TextStyle(color = colors.text, fontSize = 13.sp))
                    }
                    if (notes.isEmpty() && version != null) {
                        BasicText(release?.text("notes").orEmpty(), style = TextStyle(color = colors.text, fontSize = 13.sp))
                    }
                }
            }
        }
    }
}
