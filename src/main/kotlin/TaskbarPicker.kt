import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import com.lixing.components.ChoiceOption
import com.lixing.components.LixingSelect
import com.lixing.components.LixingTextField
import java.awt.Dimension
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.lixing.components.LixingCheckbox
import com.lixing.components.LixingButton
import com.lixing.components.ButtonVariant
import com.lixing.components.LixingChoiceHost
import com.lixing.components.LixingTriStateCheckbox
import com.lixing.components.LixingTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.cancelAndJoin
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import java.util.Base64
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.LinkedHashMap
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import com.lixing.update.LixingUpdater
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.geom.Ellipse2D
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque
import javax.imageio.ImageIO
import kotlin.math.roundToInt

private fun decode(value: String) = Base64.getDecoder().decode(value)
private fun decodeText(value: String) = decode(value).toString(Charsets.UTF_8)
private fun encodeText(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
private fun normalized(value: String) = value.lowercase(Locale.ROOT)
private fun optionalFlag(value: String?): Boolean? = when (value) {
    "1" -> true
    "0" -> false
    else -> null
}

private fun userFacingStatus(details: String?): String? {
    if (details == null) return null
    return when {
        details.startsWith("TaskbarBridge[events]") -> "连接已断开，正在重连"
        details.startsWith("TaskbarBridge[tray-menu]") || details.startsWith("托盘菜单错误") -> "托盘菜单未打开，请重试"
        details.startsWith("TaskbarBridge") -> "任务栏状态读取失败，正在重试"
        details.startsWith("新应用设置未保存") -> "新应用设置未保存，请重试"
        details.startsWith("保存失败") -> "设置未保存，请重试"
        details.startsWith("清理失败") -> "清理失败，请重试"
        else -> details
    }
}

private const val PICKER_WINDOW_TITLE = "任务栏标签"

private enum class AppInstanceCommand(val wireValue: String) {
    OPEN("open"),
    TRAY("tray"),
    QUIT("quit"),
}

private enum class PickerLaunchMode(val command: AppInstanceCommand?) {
    OPEN(AppInstanceCommand.OPEN),
    TRAY(AppInstanceCommand.TRAY),
    QUIT(AppInstanceCommand.QUIT),
}

private sealed interface InstanceLaunch {
    data class Owner(val coordinator: SingleInstanceCoordinator) : InstanceLaunch
    data object Forwarded : InstanceLaunch
    data object QuitWithoutInstance : InstanceLaunch
}

private class SingleInstanceCoordinator private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
    private val markerPath: Path,
    private val marker: String,
    private val token: String,
    private val dispatch: (AppInstanceCommand) -> Boolean,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    init {
        Thread(::serve, "TaskbarLabelPicker-instance-control").apply {
            isDaemon = true
            start()
        }
    }

    private fun serve() {
        while (!closed.get()) {
            try {
                server.accept().use(::handleClient)
            } catch (_: java.net.SocketException) {
                if (!closed.get()) continue
                return
            } catch (_: Exception) {
                if (closed.get()) return
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 2_000
        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
        val fields = reader.readLine()?.split('\t', limit = 2).orEmpty()
        val validToken = fields.getOrNull(0)?.let {
            MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), it.toByteArray(Charsets.UTF_8))
        } == true
        val command = fields.getOrNull(1)?.let { value ->
            AppInstanceCommand.entries.firstOrNull { it.wireValue == value }
        }
        val accepted = validToken && command != null && runCatching { dispatch(command) }.getOrDefault(false)
        writer.write(if (accepted) "OK\n" else "ERR\n")
        writer.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        runCatching {
            if (Files.isRegularFile(markerPath) && Files.readString(markerPath) == marker) {
                Files.deleteIfExists(markerPath)
            }
        }
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        private val loopback = InetAddress.getByName("127.0.0.1")

        fun launch(
            mode: PickerLaunchMode,
            dispatch: (AppInstanceCommand) -> Boolean,
        ): InstanceLaunch {
            val profile = TaskbarProfile.directory
            val lockPath = profile.resolve("picker-instance.lock")
            val markerPath = profile.resolve("picker-instance.txt")
            val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)

            repeat(60) {
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }

                if (lock != null) {
                    if (mode == PickerLaunchMode.QUIT) {
                        lock.release()
                        channel.close()
                        return InstanceLaunch.QuitWithoutInstance
                    }

                    val server = ServerSocket(0, 16, loopback)
                    val token = ByteArray(32).also(SecureRandom()::nextBytes)
                        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
                    val marker = "${server.localPort}\n$token\n"
                    try {
                        Files.writeString(
                            markerPath,
                            marker,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE,
                        )
                        return InstanceLaunch.Owner(
                            SingleInstanceCoordinator(channel, lock, server, markerPath, marker, token, dispatch),
                        )
                    } catch (failure: Exception) {
                        runCatching { server.close() }
                        runCatching { lock.release() }
                        runCatching { channel.close() }
                        throw failure
                    }
                }

                val command = mode.command
                if (command != null && send(markerPath, command)) {
                    channel.close()
                    return InstanceLaunch.Forwarded
                }
                if (command == null) {
                    channel.close()
                    return InstanceLaunch.QuitWithoutInstance
                }
                Thread.sleep(50L)
            }
            channel.close()
            error("已有配置器实例正在启动，暂时无法连接")
        }

        private fun send(markerPath: Path, command: AppInstanceCommand): Boolean = runCatching {
            val fields = Files.readString(markerPath).lineSequence().toList()
            val port = fields.getOrNull(0)?.toIntOrNull() ?: return false
            val token = fields.getOrNull(1)?.takeIf(String::isNotBlank) ?: return false
            Socket().use { socket ->
                socket.connect(InetSocketAddress(loopback, port), 300)
                socket.soTimeout = 1_000
                val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                writer.write("$token\t${command.wireValue}\n")
                writer.flush()
                socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine() == "OK"
            }
        }.getOrDefault(false)
    }
}

private data class OfficialInstallation(val launcher: Path) {
    fun queryAutostart(): AutostartState = invoke("--autostart-status")

    fun setAutostart(enabled: Boolean): AutostartState =
        invoke(if (enabled) "--autostart-enable" else "--autostart-disable")

    private fun invoke(argument: String): AutostartState = try {
        val executable = launcher.toRealPath()
        val process = ProcessBuilder(executable.toString(), argument)
            .directory(executable.parent.toFile())
            .start()
        val diagnostics = ChildProcessDiagnostics(process, "launcher")
        process.outputStream.close()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            val details = "启动器参数 $argument 超时；stderr：${diagnostics.stderrSummary() ?: "空"}"
            TaskbarDiagnostics.record(details)
            AutostartState(
                AutostartState.Value.ERROR,
                details,
            )
        } else {
            val result = process.inputStream.bufferedReader(Charsets.UTF_8).readLine()?.trim()
            when {
                result == "unavailable" || result?.startsWith("unavailable\t") == true -> {
                    val reason = result.substringAfter('\t', "unspecified")
                    TaskbarDiagnostics.record("登录启动安装检查：$reason")
                    val value = if (reason.endsWith("identity-unavailable")) AutostartState.Value.ERROR
                                else AutostartState.Value.UNAVAILABLE
                    AutostartState(value, reason)
                }
                result == "foreign" -> AutostartState(AutostartState.Value.FOREIGN)
                result == "enabled" -> AutostartState(AutostartState.Value.ENABLED)
                result == "disabled" -> AutostartState(AutostartState.Value.DISABLED)
                process.exitValue() != 0 -> {
                    val details = "启动器参数 $argument 退出码 ${process.exitValue()}；stdout=${result.orEmpty()}；stderr=${diagnostics.stderrSummary() ?: "空"}"
                    TaskbarDiagnostics.record(details)
                    AutostartState(AutostartState.Value.ERROR, details)
                }
                else -> {
                    val details = "启动器参数 $argument 返回未知状态：${result.orEmpty()}；stderr=${diagnostics.stderrSummary() ?: "空"}"
                    TaskbarDiagnostics.record(details)
                    AutostartState(AutostartState.Value.ERROR, details)
                }
            }
        }
    } catch (failure: Exception) {
        val details = "启动器参数 $argument 失败：${failure.message ?: failure.javaClass.simpleName}"
        TaskbarDiagnostics.record(details)
        AutostartState(AutostartState.Value.ERROR, details)
    }

    companion object {
        fun detectCurrent(): OfficialInstallation? = runCatching {
            val resourcesText = System.getProperty("compose.application.resources.dir") ?: return null
            val resources = Path.of(resourcesText).toAbsolutePath().normalize()
            if (!Files.isDirectory(resources) || !Files.isRegularFile(resources.resolve("TaskbarBridge.exe"))) {
                return null
            }

            var directory: Path? = resources
            repeat(6) {
                val current = directory ?: return null
                val launcher = current.resolve("TaskbarLabelPickerLauncher.exe")
                if (Files.isRegularFile(launcher)) {
                    val expectedResources = current.resolve("app/app/resources").normalize()
                    if (samePath(resources, expectedResources)) return OfficialInstallation(launcher)
                }
                directory = current.parent
            }
            null
        }.getOrNull()

        private fun samePath(left: Path, right: Path): Boolean =
            runCatching { Files.isSameFile(left, right) }.getOrDefault(
                left.toAbsolutePath().normalize().toString()
                    .equals(right.toAbsolutePath().normalize().toString(), ignoreCase = true),
            )
    }
}

private sealed interface AutostartRequest {
    data object Read : AutostartRequest
    data class Set(val enabled: Boolean) : AutostartRequest
}

private data class AutostartState(
    val value: Value,
    val detail: String? = null,
) {
    enum class Value { LOADING, UNAVAILABLE, ENABLED, DISABLED, FOREIGN, ERROR }

    val toggleEnabled: Boolean get() = value == Value.ENABLED || value == Value.DISABLED

    companion object {
        fun initial(installed: Boolean) = AutostartState(
            if (installed) Value.LOADING else Value.UNAVAILABLE,
        )
    }
}

private fun bringWindowToFront(title: String) {
    EventQueue.invokeLater {
        Frame.getFrames().firstOrNull { it.title == title && it.isShowing }?.let { frame ->
            frame.toFront()
            frame.requestFocus()
        }
    }
}

private data class TaskbarItem(
    val title: String,
    val identity: String,
    val processIdentity: String,
    val icon: String,
    val windowCount: Int,
    val instanceId: String,
    val effectiveStack: Boolean?,
) {
    fun matchingRule(rules: Map<String, SavedSetting>) =
        rules[normalized(identity)] ?: rules[normalized(processIdentity)]
}

private data class SavedSetting(val key: String, val enabled: Boolean)

private data class HistoryItem(
    val title: String,
    val identity: String,
    val processIdentity: String,
    val icon: String,
    val lastSeenUnixMilliseconds: Long,
) {
    val key: String get() = identity.ifBlank { processIdentity }
}

private data class Snapshot(
    val status: String = "loading",
    val appCount: Int? = null,
    val items: List<TaskbarItem> = emptyList(),
    val rules: Map<String, SavedSetting> = emptyMap(),
    val stacks: Map<String, SavedSetting> = emptyMap(),
    val autoNewEnabled: Boolean? = null,
    val history: List<HistoryItem> = emptyList(),
    val historyLoaded: Boolean = false,
    val historyError: String? = null,
)

private data class Change(
    val key: String,
    val processKey: String? = null,
    val canonicalStackKey: String? = null,
    val hide: Boolean? = null,
    val stack: Boolean? = null,
)

private data class RuleWriteRequest(val changes: List<Change>, val batch: Boolean)

private data class BridgeSnapshot(
    val status: String,
    val appCount: Int?,
    val items: List<TaskbarItem>?,
    val rules: Map<String, SavedSetting>,
    val stacks: Map<String, SavedSetting>,
    val autoNewEnabled: Boolean?,
    val historyRevision: Long,
    val processGeneration: Int,
)

private data class HistoryLoad(val items: List<HistoryItem>, val error: String?)
private data class RemovalResult(val removedCount: Int, val appCount: Int)

private sealed interface BridgeSignal {
    data object Ready : BridgeSignal
    data class Failed(val message: String) : BridgeSignal
    data object VisibilityChanged : BridgeSignal
    data object HistoryRetry : BridgeSignal
    data class ReadRetry(val includeItems: Boolean, val forceHistory: Boolean) : BridgeSignal
}

private sealed interface PickerAction {
    data class ChangeRequested(val request: RuleWriteRequest) : PickerAction
    data class SignalReceived(val signal: BridgeSignal) : PickerAction
    data class EventBatch(val mask: Int) : PickerAction
    data class InstanceCommandReceived(val command: AppInstanceCommand) : PickerAction
    data class AutoNewChangeRequested(val enabled: Boolean) : PickerAction
    data class CleanupRequested(val identities: List<String>) : PickerAction
}

private const val EVENT_TASKBAR_CHANGED = 1
private const val EVENT_STATE_CHANGED = 2

private data class AppRow(
    val key: String,
    val uiKey: String,
    val title: String,
    val identity: String,
    val processIdentity: String,
    val icon: String,
    val windowCount: Int?,
    val iconRule: SavedSetting?,
    val stackRule: SavedSetting?,
    val effectiveStack: Boolean?,
    val aliases: Set<String>,
)

private data class ClosedAppRow(
    val key: String,
    val iconRule: SavedSetting?,
    val stackRule: SavedSetting?,
)

private data class HistoryAppRow(
    val key: String,
    val title: String,
    val identity: String,
    val processIdentity: String,
    val icon: String,
    val lastSeenUnixMilliseconds: Long,
    val iconRule: SavedSetting?,
    val stackRule: SavedSetting?,
    val aliases: Set<String>,
)

private data class TrayPresentation(val label: String, val tooltip: String, val color: Color)

private object PickerIcon {
    fun load(): ImageBitmap {
        val bytes = PickerIcon::class.java.getResourceAsStream("/icons/taskbar-label-picker.png")
            ?.use { it.readBytes() }
            ?: error("未找到任务栏标签图标资源")
        return SkiaImage.makeFromEncoded(bytes).use { it.toComposeImageBitmap() }
    }
}

private class TaskbarIconCache(private val maximumBytes: Long = 12L * 1024L * 1024L) {
    private data class Entry(val image: ImageBitmap, val weight: Long)

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var currentBytes = 0L

    fun get(encoded: String): ImageBitmap? {
        if (encoded.isEmpty()) return null
        entries[encoded]?.let { return it.image }
        val image = try {
            SkiaImage.makeFromEncoded(decode(encoded)).use { it.toComposeImageBitmap() }
        } catch (_: Exception) {
            return null
        }
        val weight = image.width.toLong() * image.height.toLong() * 4L + encoded.length.toLong() * 2L
        if (weight > maximumBytes) return image
        entries[encoded] = Entry(image, weight)
        currentBytes += weight
        val iterator = entries.entries.iterator()
        while (currentBytes > maximumBytes && iterator.hasNext()) {
            currentBytes -= iterator.next().value.weight
            iterator.remove()
        }
        return image
    }
}

private val historyTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

private fun formatHistoryTime(lastSeenUnixMilliseconds: Long): String =
    runCatching {
        Instant.ofEpochMilli(lastSeenUnixMilliseconds)
            .atZone(ZoneId.systemDefault())
            .format(historyTimeFormatter)
    }.getOrDefault("时间未知")

private object TrayImages {
    private val logo: BufferedImage by lazy {
        PickerIcon::class.java.getResourceAsStream("/icons/taskbar-label-picker.png")
            ?.use(ImageIO::read)
            ?: error("未找到托盘图标资源")
    }

    fun create(statusColor: Color): BufferedImage {
        val size = 64
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.drawImage(logo, 0, 0, size, size, null)
            val radius = size * 0.145f
            val centerX = size - radius - 1f
            val centerY = radius + 1f
            graphics.color = java.awt.Color(0x10, 0x11, 0x12)
            graphics.fill(Ellipse2D.Float(centerX - radius - 2.5f, centerY - radius - 2.5f,
                (radius + 2.5f) * 2f, (radius + 2.5f) * 2f))
            graphics.color = java.awt.Color(
                (statusColor.red * 255f).roundToInt().coerceIn(0, 255),
                (statusColor.green * 255f).roundToInt().coerceIn(0, 255),
                (statusColor.blue * 255f).roundToInt().coerceIn(0, 255),
                (statusColor.alpha * 255f).roundToInt().coerceIn(0, 255),
            )
            graphics.fill(Ellipse2D.Float(centerX - radius, centerY - radius, radius * 2f, radius * 2f))
        } finally {
            graphics.dispose()
        }
        return image
    }
}

private class ChildProcessDiagnostics(process: Process, source: String) {
    private val lock = Any()
    private val stderrLines = ArrayDeque<String>()

    init {
        Thread({
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach(::append)
                }
            } catch (failure: Exception) {
                append("读取 $source stderr 失败：${failure.message ?: failure.javaClass.simpleName}")
            }
        }, "TaskbarLabelPicker-$source-stderr").apply {
            isDaemon = true
            start()
        }
    }

    private fun append(line: String) {
        synchronized(lock) {
            stderrLines.addLast(line.take(512))
            while (stderrLines.size > 12) stderrLines.removeFirst()
        }
    }

    fun stderrSummary(): String? = synchronized(lock) {
        stderrLines.joinToString(" | ").takeIf(String::isNotBlank)
    }
}

private object TaskbarProfile {
    val directory: Path = Path.of(System.getProperty("user.home"), ".lixing", "TaskbarLabelPicker")
        .toAbsolutePath().normalize()
    val isReady: Boolean get() = Files.isRegularFile(directory.resolve("profile.ready"))
}

private object TaskbarDiagnostics {
    private val path = TaskbarProfile.directory.resolve("diagnostics.log")

    @Synchronized
    fun record(details: String) {
        try {
            Files.createDirectories(path.parent)
            if (Files.exists(path) && Files.size(path) > 65_536L) {
                val tail = Files.readString(path, Charsets.UTF_8).takeLast(8_192)
                Files.writeString(
                    path,
                    tail,
                    Charsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
            }
            val line = "${Instant.now()} ${details.replace('\r', ' ').replace('\n', ' ').take(8_192)}\n"
            Files.writeString(path, line, Charsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (_: Exception) {
            // Diagnostics must not block the setting action or UI recovery.
        }
    }
}

private fun onAwtEventThread(action: () -> Unit) {
    if (EventQueue.isDispatchThread()) action()
    else EventQueue.invokeAndWait { action() }
}

@androidx.compose.runtime.Composable
private fun TaskbarTray(
    bridge: TaskbarBridge,
    presentation: TrayPresentation,
    onOpen: () -> Unit,
    onExit: () -> Unit,
    onMenuError: (String) -> Unit,
    onMenuSuccess: () -> Unit,
    updateVersion: String?,
) {
    val currentPresentation = rememberUpdatedState(presentation)
    val currentOnOpen = rememberUpdatedState(onOpen)
    val currentOnExit = rememberUpdatedState(onExit)
    val currentOnMenuError = rememberUpdatedState(onMenuError)
    val currentOnMenuSuccess = rememberUpdatedState(onMenuSuccess)
    val menuJobScope = rememberCoroutineScope()
    val trayIcon = remember { AtomicReference<TrayIcon?>(null) }
    val nativeMenuActive = remember { AtomicBoolean(false) }
    val nativeMenuProcess = remember { AtomicReference<Process?>(null) }

    DisposableEffect(trayIcon) {
        if (!SystemTray.isSupported()) return@DisposableEffect onDispose { }
        onAwtEventThread {
            val initial = currentPresentation.value
            val icon = TrayIcon(TrayImages.create(initial.color), initial.tooltip).apply {
                isImageAutoSize = true
                addActionListener { currentOnOpen.value() }
                addMouseListener(object : MouseAdapter() {
                    override fun mouseReleased(event: MouseEvent) {
                        if (!(event.isPopupTrigger || event.button == MouseEvent.BUTTON3)) return
                        if (!nativeMenuActive.compareAndSet(false, true)) return
                        val status = currentPresentation.value.label
                        menuJobScope.launch(Dispatchers.IO) {
                            var startedProcess: Process? = null
                            var diagnostics: ChildProcessDiagnostics? = null
                            try {
                                val process = bridge.startNativeTrayMenu(status)
                                startedProcess = process
                                diagnostics = ChildProcessDiagnostics(process, "tray-menu")
                                nativeMenuProcess.set(process)
                                process.outputStream.close()
                                if (!currentCoroutineContext().isActive) {
                                    process.destroyForcibly()
                                    return@launch
                                }
                                val command = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
                                val exitCode = process.waitFor()
                                when {
                                    exitCode != 0 -> {
                                        val details = "TaskbarBridge[tray-menu] 退出码 $exitCode；stderr：${diagnostics?.stderrSummary() ?: "空"}"
                                        TaskbarDiagnostics.record(details)
                                        EventQueue.invokeLater { currentOnMenuError.value(details) }
                                    }
                                    else -> EventQueue.invokeLater {
                                        currentOnMenuSuccess.value()
                                        when (command) {
                                            "open" -> currentOnOpen.value()
                                            "exit" -> currentOnExit.value()
                                        }
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                if (currentCoroutineContext().isActive) {
                                    val stderr = diagnostics?.stderrSummary()?.let { "；stderr：$it" }.orEmpty()
                                    val details = "TaskbarBridge[tray-menu] 启动或读取失败：${failure.message ?: failure.javaClass.simpleName}$stderr"
                                    TaskbarDiagnostics.record(details)
                                    EventQueue.invokeLater { currentOnMenuError.value(details) }
                                }
                            } finally {
                                startedProcess?.let {
                                    if (it.isAlive) it.destroyForcibly()
                                    nativeMenuProcess.compareAndSet(it, null)
                                }
                                nativeMenuActive.set(false)
                            }
                        }
                    }
                })
            }
            SystemTray.getSystemTray().add(icon)
            trayIcon.set(icon)
        }
        onDispose {
            nativeMenuProcess.getAndSet(null)?.let { if (it.isAlive) it.destroyForcibly() }
            onAwtEventThread {
                trayIcon.getAndSet(null)?.let { SystemTray.getSystemTray().remove(it) }
            }
        }
    }

    var notifiedUpdate by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(updateVersion) {
        if (updateVersion != null && updateVersion != notifiedUpdate) onAwtEventThread {
            notifiedUpdate = updateVersion
            trayIcon.get()?.displayMessage("任务栏标签有新版本", "打开配置器查看 $updateVersion 的更新。", TrayIcon.MessageType.INFO)
        }
    }

    LaunchedEffect(presentation) {
        val icon = trayIcon.get() ?: return@LaunchedEffect
        val image = withContext(Dispatchers.Default) { TrayImages.create(presentation.color) }
        onAwtEventThread {
            if (trayIcon.get() === icon) {
                icon.toolTip = presentation.tooltip
                icon.image = image
            }
        }
    }
}

private fun trayPresentation(snapshot: Snapshot, error: String?): TrayPresentation {
    val (label, color) = when {
        error != null -> error.take(48) to Color(0xFFFF8F86)
        snapshot.status == "loading" -> "正在读取任务栏状态" to Color(0xFF8CCBFF)
        snapshot.status == "inactive" -> "Windhawk 模块未加载" to Color(0xFFFF8F86)
        snapshot.status == "pending" -> "规则已保存，等待应用" to Color(0xFFFFD27A)
        snapshot.status == "applied" ->
            "已应用 · ${snapshot.rules.values.count { it.enabled }} 项仅图标 · ${snapshot.stacks.values.count { it.enabled }} 项叠放" to Color(0xFF72D6A4)
        else -> "任务栏状态未知" to Color(0xFFB7B0A3)
    }
    return TrayPresentation(label, "任务栏标签 · $label".take(63), color)
}

private class TaskbarBridge : AutoCloseable {
    private var process: Process? = null
    private var request: java.io.BufferedWriter? = null
    private var response: java.io.BufferedReader? = null
    private var processDiagnostics: ChildProcessDiagnostics? = null
    @Volatile private var eventProcess: Process? = null
    @Volatile private var eventDiagnostics: ChildProcessDiagnostics? = null
    @Volatile private var lastEventDiagnostics: String? = null
    private var processGeneration = 0

    val generation: Int get() = processGeneration

    private fun executable(): File {
        val resources = System.getProperty("compose.application.resources.dir")
        return if (resources == null) File("native/TaskbarBridge.exe")
        else File(resources, "TaskbarBridge.exe")
    }

    private fun ensureProcess() {
        if (process?.isAlive == true) return
        val running = ProcessBuilder(executable().absolutePath).start()
        process = running
        processDiagnostics = ChildProcessDiagnostics(running, "taskbar-bridge")
        request = running.outputStream.bufferedWriter(Charsets.UTF_8)
        response = running.inputStream.bufferedReader(Charsets.UTF_8)
        processGeneration++
    }

    private fun data(command: String): List<List<String>> {
        val commandName = command.substringBefore('\t')
        try {
            ensureProcess()
            request!!.apply {
                write(command)
                newLine()
                flush()
            }
            val rows = mutableListOf<List<String>>()
            while (true) {
                val line = response!!.readLine() ?: error("桥进程已关闭输出流")
                val fields = line.split('\t')
                when (fields[0]) {
                    "END" -> break
                    else -> rows.add(fields)
                }
            }
            rows.firstOrNull { it[0] == "ERROR" }?.let { error(decodeText(it.getOrNull(1).orEmpty())) }
            return rows
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val running = process
            val exit = running?.takeIf { !it.isAlive }?.let { "，退出码 ${runCatching { it.exitValue() }.getOrDefault(-1)}" }.orEmpty()
            val stderr = processDiagnostics?.stderrSummary() ?: "空"
            val details = "TaskbarBridge[$commandName] 失败$exit：${failure.message ?: failure.javaClass.simpleName}；stderr：$stderr"
            TaskbarDiagnostics.record(details)
            throw IllegalStateException(details, failure)
        }
    }

    private fun bridgeSnapshot(rows: List<List<String>>, items: List<TaskbarItem>? = null) = BridgeSnapshot(
        status = rows.firstOrNull { it[0] == "STATE" }?.getOrNull(1) ?: "unknown",
        appCount = rows.firstOrNull { it[0] == "APP_COUNT" }?.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0),
        items = items,
        rules = rows.filter { it[0] == "RULE" || it[0] == "SHOW" }
            .associate {
                val key = decodeText(it[1])
                normalized(key) to SavedSetting(key, it[0] == "RULE")
            },
        stacks = rows.filter { it[0] == "STACK" }.map { decodeText(it[1]) to (it[2] == "1") }
            .associate { (key, enabled) -> normalized(key) to SavedSetting(key, enabled) },
        autoNewEnabled = rows.firstOrNull { it[0] == "AUTO_NEW" }?.getOrNull(1)?.let(::optionalFlag),
        historyRevision = rows.firstOrNull { it[0] == "HREV" }?.getOrNull(1)?.toLongOrNull() ?: -1L,
        processGeneration = generation,
    )

    private fun parseTaskbarItems(rows: List<List<String>>) = rows.filter { it[0] == "ITEM" }.map {
            TaskbarItem(
                title = decodeText(it[1]),
                identity = decodeText(it[2]),
                processIdentity = decodeText(it[3]),
                icon = it[4],
                windowCount = it.getOrNull(6)?.toIntOrNull() ?: 0,
                instanceId = it.getOrNull(7)?.let(::decodeText).orEmpty(),
                effectiveStack = optionalFlag(it.getOrNull(9)),
            )
        }

    fun scan(visible: Boolean): BridgeSnapshot {
        val rows = data(if (visible) "scan-view" else "scan-hidden")
        val unchanged = rows.any { it[0] == "SAME" }
        val items = if (!visible || unchanged) null else parseTaskbarItems(rows)
        return bridgeSnapshot(rows, items)
    }

    fun status(): BridgeSnapshot = bridgeSnapshot(data("status"))

    fun setAutoNew(enabled: Boolean): Boolean =
        data("set-auto-new\t${if (enabled) "1" else "0"}")
            .firstOrNull { it[0] == "AUTO_NEW" }
            ?.getOrNull(1)
            ?.let(::optionalFlag)
            ?: error("TaskbarBridge[set-auto-new] 未返回 AUTO_NEW 状态")

    fun removeApps(identities: List<String>): RemovalResult {
        require(identities.isNotEmpty()) { "TaskbarBridge[remove-apps] 未选择应用" }
        val command = buildString {
            append("remove-apps")
            identities.distinctBy(::normalized).forEach { identity ->
                require(identity.isNotBlank()) { "TaskbarBridge[remove-apps] 应用身份为空" }
                append('\t')
                append(encodeText(identity))
            }
        }
        val rows = data(command)
        val removedCount = rows.firstOrNull { it[0] == "REMOVED" }?.getOrNull(1)?.toIntOrNull()
            ?: error("TaskbarBridge[remove-apps] 未返回 REMOVED 数量")
        val appCount = rows.firstOrNull { it[0] == "APP_COUNT" }?.getOrNull(1)?.toIntOrNull()
            ?: error("TaskbarBridge[remove-apps] 未返回 APP_COUNT 数量")
        return RemovalResult(removedCount.coerceAtLeast(0), appCount.coerceAtLeast(0))
    }

    fun history(): HistoryLoad {
        val rows = data("history")
        rows.firstOrNull { it[0] == "HERROR" }?.let {
            return HistoryLoad(emptyList(), decodeText(it.getOrNull(1).orEmpty()))
        }
        val items = rows.filter { it[0] == "HISTORY" }.mapNotNull {
            val lastSeen = it.getOrNull(5)?.toLongOrNull() ?: return@mapNotNull null
            HistoryItem(
                title = decodeText(it[1]),
                identity = decodeText(it[2]),
                processIdentity = decodeText(it[3]),
                icon = it[4],
                lastSeenUnixMilliseconds = lastSeen,
            )
        }
        return HistoryLoad(items, null)
    }

    fun startEvents(): Process {
        val running = ProcessBuilder(executable().absolutePath, "events").start()
        eventProcess = running
        eventDiagnostics = ChildProcessDiagnostics(running, "taskbar-events")
        return running
    }

    fun startNativeTrayMenu(status: String): Process =
        ProcessBuilder(executable().absolutePath, "tray-menu", status).start()

    fun eventDiagnosticSummary(): String? = eventDiagnostics?.stderrSummary() ?: lastEventDiagnostics

    fun stopEvents(running: Process? = eventProcess) {
        if (running != null) {
            if (eventProcess === running) {
                lastEventDiagnostics = eventDiagnostics?.stderrSummary()
                eventDiagnostics = null
                eventProcess = null
            }
            running.destroyForcibly()
        }
    }

    fun set(change: Change) {
        val encodedKey = encodeText(change.key)
        change.hide?.let {
            val processKey = change.processKey?.takeIf(String::isNotBlank)
            val processArgument = processKey?.let(::encodeText).orEmpty()
            val stackArgument = change.canonicalStackKey?.takeIf(String::isNotBlank)
                ?.let(::encodeText)
                ?: encodedKey
            data("set-icon\t$encodedKey\t${if (it) "1" else "0"}\t$processArgument\t$stackArgument")
        }
        change.stack?.let { data("set-stack\t$encodedKey\t${if (it) "1" else "0"}") }
    }

    override fun close() {
        stopEvents()
        process?.destroyForcibly()
        process = null
        processDiagnostics = null
        request = null
        response = null
    }
}

private fun ruleLabel(identity: String): String =
    identity.substringAfter(':').substringAfterLast('\\')

private fun appRows(snapshot: Snapshot): List<AppRow> {
    val seenInstances = mutableSetOf<String>()
    val uniqueItems = snapshot.items.filter { item ->
        item.instanceId.isBlank() || seenInstances.add(normalized(item.instanceId))
    }
    val grouped = linkedMapOf<String, MutableList<TaskbarItem>>()
    uniqueItems.forEachIndexed { index, item ->
        val key = item.identity.ifBlank { item.processIdentity }
        val groupKey = if (key.isBlank()) "unidentified:$index" else normalized(key)
        grouped.getOrPut(groupKey) { mutableListOf() }.add(item)
    }

    return grouped.entries.map { (groupKey, items) ->
        val first = items.first()
        val identity = items.firstOrNull { it.identity.isNotBlank() }?.identity.orEmpty()
        val processIdentity = items.firstOrNull { it.processIdentity.isNotBlank() }?.processIdentity.orEmpty()
        val key = identity.ifBlank { processIdentity }
        val iconRule = items.firstNotNullOfOrNull { it.matchingRule(snapshot.rules) }
        val stackRule = items.firstNotNullOfOrNull { it.matchingRule(snapshot.stacks) }
        val effectiveStack = items.mapNotNull { it.effectiveStack }.distinct().singleOrNull()
        val confirmedWindowCount = items.sumOf { it.windowCount.coerceAtLeast(0) }.takeIf { it > 1 }
        val distinctTitles = items.map { it.title }.filter { it.isNotBlank() }.distinct()
        val title = if (distinctTitles.size > 1) ruleLabel(key).ifBlank { first.title } else first.title
        val aliases = buildSet {
            items.forEach {
                if (it.identity.isNotBlank()) add(it.identity)
                if (it.processIdentity.isNotBlank()) add(it.processIdentity)
            }
            iconRule?.let { add(it.key) }
            stackRule?.let { add(it.key) }
        }
        AppRow(
            key = key,
            uiKey = if (key.isBlank()) groupKey else key,
            title = title.ifBlank { ruleLabel(key).ifBlank { "任务栏应用" } },
            identity = identity,
            processIdentity = processIdentity,
            icon = items.firstOrNull { it.icon.isNotEmpty() }?.icon.orEmpty(),
            windowCount = confirmedWindowCount,
            iconRule = iconRule,
            stackRule = stackRule,
            effectiveStack = effectiveStack,
            aliases = aliases,
        )
    }
}

private fun closedAppRows(snapshot: Snapshot, openAliases: Set<String>): List<ClosedAppRow> {
    val savedKeys = (snapshot.rules.values + snapshot.stacks.values)
        .map { it.key }
        .distinctBy(::normalized)
        .filterNot { normalized(it) in openAliases }
    return savedKeys.map { key ->
        ClosedAppRow(key, snapshot.rules[normalized(key)], snapshot.stacks[normalized(key)])
    }
}

private fun historyAppRows(snapshot: Snapshot, apps: List<AppRow>): List<HistoryAppRow> {
    val visibleIdentities = apps.map { normalized(it.identity.ifBlank { it.key }) }.toSet()
    val visibleProcessKeys = apps.map { normalized(it.processIdentity) }.filter(String::isNotBlank).toSet()
    val entries = snapshot.history.filter { item ->
        item.key.isNotBlank() && when {
            item.identity.startsWith("id:", ignoreCase = true) -> normalized(item.identity) !in visibleIdentities
            else -> normalized(item.processIdentity.ifBlank { item.identity }) !in visibleProcessKeys
        }
    }
    return entries.groupBy { normalized(it.identity.ifBlank { it.processIdentity }) }
        .values
        .map { sameApp -> sameApp.maxBy { it.lastSeenUnixMilliseconds } }
        .map { item ->
            val identity = item.identity
            val processIdentity = item.processIdentity
            val key = identity.ifBlank { processIdentity }
            val iconRule = snapshot.rules[normalized(identity)] ?: snapshot.rules[normalized(processIdentity)]
            val stackRule = snapshot.stacks[normalized(identity)] ?: snapshot.stacks[normalized(processIdentity)]
            val aliases = buildSet {
                if (identity.isNotBlank()) add(identity)
                if (processIdentity.isNotBlank()) add(processIdentity)
                iconRule?.let { add(it.key) }
                stackRule?.let { add(it.key) }
            }
            HistoryAppRow(
                key = key,
                title = item.title.ifBlank { ruleLabel(key).ifBlank { "任务栏应用" } },
                identity = identity,
                processIdentity = processIdentity,
                icon = item.icon,
                lastSeenUnixMilliseconds = item.lastSeenUnixMilliseconds,
                iconRule = iconRule,
                stackRule = stackRule,
                aliases = aliases,
            )
        }
        .sortedWith(compareByDescending<HistoryAppRow> { it.lastSeenUnixMilliseconds }.thenBy { normalized(it.key) })
}

fun main(args: Array<String>) {
    val mode = when {
        args.any { it.equals("--quit", ignoreCase = true) } -> PickerLaunchMode.QUIT
        args.any { it.equals("--tray", ignoreCase = true) } -> PickerLaunchMode.TRAY
        else -> PickerLaunchMode.OPEN
    }
    if (!TaskbarProfile.isReady) {
        if (mode == PickerLaunchMode.OPEN) {
            javax.swing.JOptionPane.showMessageDialog(
                null, "请使用开始菜单中的「任务栏标签」初始化设置。", "任务栏标签",
                javax.swing.JOptionPane.ERROR_MESSAGE,
            )
        }
        return
    }
    val commands = Channel<AppInstanceCommand>(Channel.UNLIMITED)
    when (val launch = SingleInstanceCoordinator.launch(mode) { commands.trySend(it).isSuccess }) {
        is InstanceLaunch.Owner -> try {
            val installation = OfficialInstallation.detectCurrent()
            application {
                TaskbarPickerApplication(
                    initialVisible = mode == PickerLaunchMode.OPEN,
                    installation = installation,
                    commands = commands,
                    onExit = { exitApplication() },
                )
            }
        } finally {
            launch.coordinator.close()
            commands.close()
        }
        InstanceLaunch.Forwarded,
        InstanceLaunch.QuitWithoutInstance -> commands.close()
    }
}

@androidx.compose.runtime.Composable
private fun TaskbarPickerApplication(
    initialVisible: Boolean,
    installation: OfficialInstallation?,
    commands: Channel<AppInstanceCommand>,
    onExit: () -> Unit,
) {
    val changes = remember { Channel<RuleWriteRequest>(1) }
    val cleanupRequests = remember { Channel<List<String>>(1) }
    val signals = remember { Channel<BridgeSignal>(Channel.UNLIMITED) }
    val eventWakeups = remember { Channel<Unit>(Channel.CONFLATED) }
    val autostartRequests = remember { Channel<AutostartRequest>(Channel.UNLIMITED) }
    val autoNewChanges = remember { Channel<Boolean>(Channel.UNLIMITED) }
    val bridge = remember { TaskbarBridge() }
    var snapshot by remember { mutableStateOf(Snapshot()) }
    var scanReadError by remember { mutableStateOf<String?>(null) }
    var statusReadError by remember { mutableStateOf<String?>(null) }
    var writeError by remember { mutableStateOf<String?>(null) }
    var changing by remember { mutableStateOf(false) }
    var autostartState by remember { mutableStateOf(AutostartState.initial(installation != null)) }
    var autostartChanging by remember { mutableStateOf(false) }
    var autoNewChanging by remember { mutableStateOf(false) }
    var autoNewError by remember { mutableStateOf<String?>(null) }
    var cleanupError by remember { mutableStateOf<String?>(null) }
    var cleanupFeedback by remember { mutableStateOf<String?>(null) }
    var cleanupChanging by remember { mutableStateOf(false) }
    var batchFeedback by remember { mutableStateOf<String?>(null) }
    var trayMenuError by remember { mutableStateOf<String?>(null) }
    var eventError by remember { mutableStateOf<String?>(null) }
    var windowVisible by remember { mutableStateOf(initialVisible) }
    var settingsVisible by remember { mutableStateOf(false) }
    var helpVisible by remember { mutableStateOf(false) }
    var supportVisible by remember { mutableStateOf(false) }
    var updateVisible by remember { mutableStateOf(false) }
    val createUpdater = remember(installation) { if (installation != null) packagedUpdaterFactory() else null }
    var updater by remember(createUpdater) { mutableStateOf<LixingUpdater?>(null) }
    var lastUpdateCheck by remember(createUpdater) { mutableStateOf(JsonObject(emptyMap())) }
    val idleUpdateState = remember { MutableStateFlow(JsonObject(emptyMap())) }
    val pendingUpdateState by (updater?.state ?: idleUpdateState).collectAsState()
    val updateState = if (updater == null) lastUpdateCheck else pendingUpdateState
    val updateVersion = availableUpdate(updateState)
    val updateScope = rememberCoroutineScope()
    val updatePreference = remember { TaskbarProfile.directory.resolve("auto-update.dat") }
    var automaticUpdate by remember { mutableStateOf(runCatching { Files.readString(updatePreference) == "1" }.getOrDefault(false)) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var automaticAttempt by remember { mutableStateOf<String?>(null) }
    suspend fun update(action: String): Boolean {
        if (updateBusy) return false
        val service = updater ?: createUpdater?.invoke()?.also { updater = it } ?: return false
        val checking = action == "check"
        var keepSession = !checking
        if (checking) lastUpdateCheck = JsonObject(emptyMap())
        updateBusy = true
        updateError = null
        try {
            val response = service.request(action)
            val ok = response.flag("ok")
            if (!ok) updateError = response.text("error") ?: "更新操作失败"
            if (checking && ok) {
                lastUpdateCheck = response["state"] as? JsonObject ?: service.state.value
                keepSession = lastUpdateCheck.flag("canSetup")
            }
            return ok
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            updateError = failure.message ?: "更新服务暂不可用"
            return false
        } finally {
            if (!keepSession) {
                service.close()
                updater = null
            }
            updateBusy = false
        }
    }
    DisposableEffect(createUpdater) { onDispose { updater?.close() } }
    LaunchedEffect(createUpdater) { if (createUpdater != null) update("check") }
    LaunchedEffect(updateVersion, automaticUpdate, updateBusy) {
        val version = updateVersion ?: return@LaunchedEffect
        if (automaticUpdate && !updateBusy && updateState.flag("canSetup") && automaticAttempt != version) {
            automaticAttempt = version
            updateScope.launch {
                if (updateState.text("phase") == "ready" || update("download")) update("install")
            }
        }
    }
    val visibilityState = rememberUpdatedState(windowVisible)
    val pendingBridgeEvents = remember { AtomicInteger(0) }
    val readError = scanReadError ?: statusReadError
    val displayMessage = userFacingStatus(cleanupError ?: trayMenuError ?: autoNewError ?: writeError ?: readError ?: eventError)
    val trayError = userFacingStatus(trayMenuError ?: writeError ?: readError ?: autoNewError ?: eventError)
    val tray = trayPresentation(snapshot, trayError)

    LaunchedEffect(windowVisible) {
        if (windowVisible && installation != null) autostartRequests.send(AutostartRequest.Read)
        signals.send(BridgeSignal.VisibilityChanged)
    }

    LaunchedEffect(installation, autostartRequests) {
        if (installation == null) return@LaunchedEffect
        for (request in autostartRequests) {
            autostartChanging = true
            autostartState = AutostartState(AutostartState.Value.LOADING)
            try {
                autostartState = withContext(Dispatchers.IO) {
                    when (request) {
                        AutostartRequest.Read -> installation.queryAutostart()
                        is AutostartRequest.Set -> installation.setAutostart(request.enabled)
                    }
                }
            } finally {
                autostartChanging = false
            }
        }
    }

    DisposableEffect(bridge) {
        onDispose {
            changes.close()
            cleanupRequests.close()
            signals.close()
            eventWakeups.close()
            autostartRequests.close()
            autoNewChanges.close()
            bridge.close()
        }
    }

    LaunchedEffect(bridge) {
        var ready = false
        var lastHistoryAttemptRevision: Long? = null
        var bridgeGeneration = -1
        var historyRetryAt = 0L
        var historyRetryJob: Job? = null
        var readRetryJob: Job? = null
        var retryIncludesItems = false
        var retryForcesHistory = false

        suspend fun refresh(includeItems: Boolean, forceHistory: Boolean) {
            val visible = visibilityState.value
            val update = withContext(Dispatchers.IO) {
                if (includeItems) bridge.scan(visible) else bridge.status()
            }
            val now = System.currentTimeMillis()
            val shouldReadHistory = visible && (
                forceHistory ||
                    update.processGeneration != bridgeGeneration ||
                    update.historyRevision != lastHistoryAttemptRevision ||
                    (snapshot.historyError != null && now >= historyRetryAt)
                )
            val historyLoad = if (shouldReadHistory) withContext(Dispatchers.IO) { bridge.history() } else null

            var nextSnapshot = snapshot.copy(
                status = update.status,
                appCount = update.appCount ?: snapshot.appCount,
                items = update.items ?: snapshot.items,
                rules = update.rules,
                stacks = update.stacks,
                autoNewEnabled = update.autoNewEnabled ?: snapshot.autoNewEnabled,
            )
            if (historyLoad != null) {
                lastHistoryAttemptRevision = update.historyRevision
                if (historyLoad.error == null) {
                    nextSnapshot = nextSnapshot.copy(
                        history = historyLoad.items,
                        historyLoaded = true,
                        historyError = null,
                    )
                    historyRetryAt = 0L
                    historyRetryJob?.cancel()
                    historyRetryJob = null
                } else {
                    nextSnapshot = nextSnapshot.copy(historyError = historyLoad.error)
                    TaskbarDiagnostics.record("TaskbarBridge[history] ${historyLoad.error}")
                    historyRetryAt = System.currentTimeMillis() + 5_000L
                    historyRetryJob?.cancel()
                    historyRetryJob = launch {
                        delay(5_000L)
                        signals.send(BridgeSignal.HistoryRetry)
                    }
                }
            }
            bridgeGeneration = update.processGeneration
            if (nextSnapshot != snapshot) snapshot = nextSnapshot
            if (includeItems) {
                scanReadError = null
                statusReadError = null
                retryIncludesItems = false
                retryForcesHistory = false
                readRetryJob?.cancel()
                readRetryJob = null
            } else {
                statusReadError = null
                if (scanReadError == null) {
                    retryIncludesItems = false
                    retryForcesHistory = false
                    readRetryJob?.cancel()
                    readRetryJob = null
                }
            }
        }

        suspend fun refreshSafely(includeItems: Boolean, forceHistory: Boolean, allowRetry: Boolean = true) {
            try {
                refresh(includeItems, forceHistory)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val details = failure.message ?: "无法读取任务栏状态"
                if (includeItems) scanReadError = details else statusReadError = details
                retryIncludesItems = retryIncludesItems || includeItems || scanReadError != null
                retryForcesHistory = retryForcesHistory || forceHistory
                if (allowRetry && visibilityState.value) {
                    readRetryJob?.cancel()
                    val retry = BridgeSignal.ReadRetry(retryIncludesItems, retryForcesHistory)
                    readRetryJob = launch {
                        delay(800L)
                        if (readRetryJob === currentCoroutineContext()[Job]) {
                            retryIncludesItems = false
                            retryForcesHistory = false
                            signals.send(retry)
                        }
                    }
                }
            }
        }

        suspend fun setAutoNewSafely(enabled: Boolean) {
            autoNewChanging = true
            try {
                val actual = withContext(Dispatchers.IO) { bridge.setAutoNew(enabled) }
                snapshot = snapshot.copy(autoNewEnabled = actual)
                autoNewError = null
                writeError = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                autoNewError = "新应用设置未保存：${failure.message ?: failure.javaClass.simpleName}"
            } finally {
                autoNewChanging = false
            }
        }

        suspend fun removeAppsSafely(identities: List<String>) {
            cleanupChanging = true
            cleanupError = null
            cleanupFeedback = null
            try {
                val result = withContext(Dispatchers.IO) { bridge.removeApps(identities) }
                snapshot = snapshot.copy(appCount = result.appCount)
                cleanupFeedback = when {
                    result.removedCount > 0 -> "已清理 ${result.removedCount} 个应用"
                    else -> "所选应用已无可清理记录"
                }
                writeError = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                cleanupError = "清理结果未确认，请检查后重试"
                TaskbarDiagnostics.record("TaskbarBridge[remove-apps] ${failure.message ?: failure.javaClass.simpleName}")
            } finally {
                try {
                    if (isActive) refreshSafely(includeItems = true, forceHistory = true)
                } finally {
                    cleanupChanging = false
                }
            }
        }

        val eventReader = launch(Dispatchers.IO) {
            while (isActive) {
                var eventProcess: Process? = null
                try {
                    eventProcess = bridge.startEvents()
                    val reader = eventProcess.inputStream.bufferedReader(Charsets.UTF_8)
                    val readyLine = reader.readLine()
                    if (readyLine != "READY") error("任务栏事件连接未就绪")
                    signals.send(BridgeSignal.Ready)
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        val fields = line.split('\t')
                        if (fields.firstOrNull() == "EVENT") {
                            val bit = when (fields.getOrNull(1)) {
                                "taskbar" -> EVENT_TASKBAR_CHANGED
                                "state" -> EVENT_STATE_CHANGED
                                else -> 0
                            }
                            if (bit != 0) {
                                pendingBridgeEvents.getAndUpdate { it or bit }
                                eventWakeups.trySend(Unit)
                            }
                        }
                    }
                    if (isActive) error("任务栏事件连接已断开")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (isActive) {
                        val stderr = bridge.eventDiagnosticSummary()?.let { "；stderr：$it" } ?: "；stderr为空"
                        val details = "TaskbarBridge[events] ${failure.message ?: failure.javaClass.simpleName}$stderr"
                        TaskbarDiagnostics.record(details)
                        signals.send(BridgeSignal.Failed(details))
                    }
                } finally {
                    bridge.stopEvents(eventProcess)
                }
                if (isActive) delay(1_500L)
            }
        }

        try {
            while (isActive) {
                when (val action = select<PickerAction> {
                    changes.onReceive { PickerAction.ChangeRequested(it) }
                    signals.onReceive { PickerAction.SignalReceived(it) }
                    eventWakeups.onReceive { PickerAction.EventBatch(pendingBridgeEvents.getAndSet(0)) }
                    commands.onReceive { PickerAction.InstanceCommandReceived(it) }
                    autoNewChanges.onReceive { PickerAction.AutoNewChangeRequested(it) }
                    cleanupRequests.onReceive { PickerAction.CleanupRequested(it) }
                }) {
                    is PickerAction.ChangeRequested -> {
                        val request = action.request
                        var completed = 0
                        try {
                            for (change in request.changes) {
                                withContext(Dispatchers.IO) { bridge.set(change) }
                                completed++
                            }
                            if (request.batch) batchFeedback = "已保存 $completed 条规则"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            writeError = if (request.batch) {
                                "已处理 $completed / ${request.changes.size} 条规则，其余设置未确认。请检查后重试。"
                            } else {
                                "保存失败：${failure.message ?: "请重试"}"
                            }
                            TaskbarDiagnostics.record("TaskbarBridge[set] ${failure.message ?: failure.javaClass.simpleName}")
                        } finally {
                            try {
                                if (isActive) refreshSafely(includeItems = false, forceHistory = false)
                            } finally {
                                changing = false
                            }
                        }
                    }
                    is PickerAction.SignalReceived -> when (val signal = action.signal) {
                        BridgeSignal.Ready -> {
                            ready = true
                            eventError = null
                            refreshSafely(includeItems = true, forceHistory = true)
                        }
                        BridgeSignal.VisibilityChanged -> {
                            if (visibilityState.value) {
                                refreshSafely(includeItems = true, forceHistory = true)
                            } else {
                                readRetryJob?.cancel()
                                readRetryJob = null
                                if (ready) refreshSafely(includeItems = false, forceHistory = false)
                            }
                        }
                        BridgeSignal.HistoryRetry -> if (ready) {
                            refreshSafely(includeItems = false, forceHistory = false)
                        }
                        is BridgeSignal.ReadRetry -> if (visibilityState.value) {
                            refreshSafely(signal.includeItems, signal.forceHistory, allowRetry = false)
                        }
                        is BridgeSignal.Failed -> eventError = signal.message
                    }
                    is PickerAction.InstanceCommandReceived -> when (action.command) {
                        AppInstanceCommand.OPEN -> {
                            val wasVisible = windowVisible
                            windowVisible = true
                            bringWindowToFront(PICKER_WINDOW_TITLE)
                            if (wasVisible && installation != null) autostartRequests.trySend(AutostartRequest.Read)
                            refreshSafely(includeItems = true, forceHistory = true)
                        }
                        AppInstanceCommand.TRAY -> Unit
                        AppInstanceCommand.QUIT -> onExit()
                    }
                    is PickerAction.AutoNewChangeRequested -> setAutoNewSafely(action.enabled)
                    is PickerAction.CleanupRequested -> removeAppsSafely(action.identities)
                    is PickerAction.EventBatch -> if (ready && action.mask != 0) {
                        refreshSafely(
                            includeItems = action.mask and EVENT_TASKBAR_CHANGED != 0,
                            forceHistory = false,
                        )
                    }
                }
            }
        } finally {
            historyRetryJob?.cancel()
            readRetryJob?.cancel()
            bridge.stopEvents()
            eventReader.cancelAndJoin()
        }
    }

    TaskbarTray(
        bridge = bridge,
        presentation = tray,
        onOpen = { commands.trySend(AppInstanceCommand.OPEN) },
        onExit = onExit,
        onMenuError = { details ->
            trayMenuError = "托盘菜单错误：$details"
            commands.trySend(AppInstanceCommand.OPEN)
        },
        onMenuSuccess = { trayMenuError = null },
        updateVersion = updateVersion,
    )

    fun submitRules(request: RuleWriteRequest) {
        if (changing || cleanupChanging || request.changes.isEmpty()) return
        changing = true
        writeError = null
        cleanupError = null
        cleanupFeedback = null
        batchFeedback = null
        if (changes.trySend(request).isFailure) {
            changing = false
            writeError = "设置未提交，请重试"
        }
    }

    val workArea = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
    val pickerWindowState = rememberWindowState(
        position = WindowPosition.Aligned(Alignment.Center),
        width = minOf(760, workArea.width).dp,
        height = minOf(690, workArea.height).dp,
    )
    val logo = if (windowVisible) remember { PickerIcon.load() } else null
    if (logo != null) Window(
        onCloseRequest = {
            windowVisible = false
            settingsVisible = false
            helpVisible = false
            supportVisible = false
            updateVisible = false
        },
        title = PICKER_WINDOW_TITLE,
        icon = androidx.compose.ui.graphics.painter.BitmapPainter(logo),
        state = pickerWindowState,
    ) {
        DisposableEffect(window) {
            val frame = window
            frame.minimumSize = Dimension(minOf(560, workArea.width), minOf(500, workArea.height))
            fun activate() {
                if (frame.isShowing) {
                    frame.toFront()
                    frame.requestFocus()
                }
            }
            val onOpened = object : WindowAdapter() {
                override fun windowOpened(event: WindowEvent) = activate()
            }
            frame.addWindowListener(onOpened)
            EventQueue.invokeLater { activate() }
            onDispose { frame.removeWindowListener(onOpened) }
        }
        LixingTheme {
            LixingChoiceHost {
                Picker(
                    snapshot = snapshot,
                    error = displayMessage,
                    listReadFailed = scanReadError != null,
                    busy = changing || cleanupChanging,
                    feedback = cleanupFeedback ?: batchFeedback,
                    onOpenSettings = { settingsVisible = true },
                    onOpenSupport = {
                        if (supportVisible) bringWindowToFront("支持开发")
                        else supportVisible = true
                    },
                    onRemoveApps = { identities ->
                        if (!changing && !cleanupChanging && identities.isNotEmpty()) {
                            cleanupChanging = true
                            cleanupError = null
                            cleanupFeedback = null
                            writeError = null
                            batchFeedback = null
                            if (cleanupRequests.trySend(identities).isFailure) {
                                cleanupChanging = false
                                cleanupError = "清理请求未提交，请重试"
                            }
                        }
                    },
                    onWrite = ::submitRules,
                )
            }
        }
    }

    if (logo != null && settingsVisible) TaskbarSettingsWindow(
        logo = logo,
        autostartState = autostartState,
        autostartChanging = autostartChanging,
        onRetryAutostart = if (installation != null) {
            { autostartRequests.trySend(AutostartRequest.Read) }
        } else null,
        onAutostartChange = { enabled ->
            autostartChanging = true
            if (autostartRequests.trySend(AutostartRequest.Set(enabled)).isFailure) autostartChanging = false
        },
        autoNewEnabled = snapshot.autoNewEnabled,
        autoNewChanging = autoNewChanging,
        autoNewError = userFacingStatus(autoNewError),
        onAutoNewChange = { enabled ->
            autoNewChanging = true
            if (autoNewChanges.trySend(enabled).isFailure) autoNewChanging = false
        },
        onOpenHelp = { helpVisible = true },
        updateLabel = if (updateVersion != null) "有更新" else "版本与更新",
        onOpenUpdate = if (createUpdater != null) { { updateVisible = true } } else null,
        onClose = { settingsVisible = false },
    )

    TaskbarHelpWindow(
        visible = helpVisible && windowVisible,
        onClose = { helpVisible = false },
    )
    if (logo != null && supportVisible) {
        SupportDevelopmentWindow(visible = true, logo = logo, onClose = { supportVisible = false })
    }
    if (logo != null && createUpdater != null) TaskbarUpdateWindow(
        visible = updateVisible && windowVisible,
        logo = logo,
        state = updateState,
        automatic = automaticUpdate,
        busy = updateBusy,
        failure = updateError,
        onSetAutomatic = { requested ->
            updateScope.launch {
                try {
                    withContext(Dispatchers.IO) { saveAutoUpdate(updatePreference, requested) }
                    automaticUpdate = requested
                    updateError = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    updateError = failure.message ?: "自动更新设置未保存"
                }
            }
        },
        onAction = { action -> updateScope.launch { update(action) } },
        onClose = { updateVisible = false },
    )
}

private enum class AppSection(val label: String) {
    ACTIVE("当前任务栏"),
    HISTORY("历史应用"),
    SAVED("其他已保存"),
}

private enum class CheckedFilter(val label: String) {
    ALL("全部状态"),
    ANY("已勾选任一项"),
    NONE("无已勾选项"),
    ICON("已勾选仅图标"),
    STACK("已勾选叠放");

    fun matches(row: PickerRow): Boolean = when (this) {
        ALL -> true
        ANY -> row.iconChecked || row.stackChecked == true
        NONE -> !row.iconChecked && row.stackChecked != true
        ICON -> row.iconChecked
        STACK -> row.stackChecked == true
    }
}

private enum class BatchAction(val label: String) {
    ICON("设为仅图标"),
    NAME("显示名称"),
    STACK("叠放窗口"),
    SEPARATE("分开窗口"),
    CLEANUP("清理应用记录");

    fun change(row: PickerRow): Change = when (this) {
        ICON -> row.iconChange(true)
        NAME -> row.iconChange(false)
        STACK -> row.stackChange(true)
        SEPARATE -> row.stackChange(false)
        CLEANUP -> error("清理使用独立命令")
    }
}

private const val ALL_APP_SECTIONS = "all"

private data class PickerRow(
    val section: AppSection,
    val key: String,
    val uiKey: String,
    val title: String,
    val detail: String,
    val iconData: String,
    val processKey: String?,
    val iconRule: SavedSetting?,
    val stackRule: SavedSetting?,
    val effectiveStack: Boolean? = null,
) {
    val selectionKey = normalized(key)
    val listKey = "${section.name}:${normalized(uiKey)}"
    val selectable = key.isNotBlank() && !key.startsWith("id:*PID", ignoreCase = true)
    val iconChecked: Boolean get() = iconRule?.enabled == true
    val stackChecked: Boolean? get() = stackRule?.enabled ?: effectiveStack
    val iconWriteKey: String get() = iconRule?.key ?: key
    private val searchNames = listOfNotNull(title, key, processKey, iconRule?.key, stackRule?.key)

    fun matchesSearch(words: List<String>): Boolean =
        words.all { word -> searchNames.any { it.contains(word, ignoreCase = true) } }

    fun iconChange(checked: Boolean) = Change(
        key = iconWriteKey,
        processKey = processKey,
        canonicalStackKey = key,
        hide = checked,
    )

    fun stackChange(checked: Boolean) = Change(key = key, stack = checked)
}

private fun usesProcessRule(key: String, processKey: String, vararg rules: SavedSetting?): Boolean =
    !processKey.equals(key, ignoreCase = true) && rules.any { it?.key.equals(processKey, ignoreCase = true) }

private fun pickerRows(apps: List<AppRow>, history: List<HistoryAppRow>, saved: List<ClosedAppRow>) = buildList {
    apps.forEach { app ->
        val detail = listOfNotNull(
            app.windowCount?.let { "$it 个窗口" },
            ruleLabel(app.key).takeIf(String::isNotBlank),
            "同程序".takeIf { usesProcessRule(app.key, app.processIdentity, app.iconRule, app.stackRule) },
        ).joinToString(" · ").ifBlank { "无法识别应用" }
        add(PickerRow(
            AppSection.ACTIVE, app.key, app.uiKey, app.title, detail, app.icon,
            app.processIdentity, app.iconRule, app.stackRule, app.effectiveStack,
        ))
    }
    history.forEach { app ->
        val detail = listOfNotNull(
            "上次打开 ${formatHistoryTime(app.lastSeenUnixMilliseconds)}",
            ruleLabel(app.key).takeIf(String::isNotBlank),
            "同程序".takeIf { usesProcessRule(app.key, app.processIdentity, app.iconRule, app.stackRule) },
        ).joinToString(" · ")
        add(PickerRow(
            AppSection.HISTORY, app.key, app.key, app.title, detail, app.icon,
            app.processIdentity, app.iconRule, app.stackRule,
        ))
    }
    saved.forEach { app ->
        add(PickerRow(
            AppSection.SAVED, app.key, app.key, ruleLabel(app.key).ifBlank { app.key },
            "已保存规则", "", null, app.iconRule, app.stackRule,
        ))
    }
}

@androidx.compose.runtime.Composable
private fun AppSettingsRow(
    row: PickerRow,
    iconCache: TaskbarIconCache,
    selecting: Boolean,
    selected: Boolean,
    busy: Boolean,
    onSelect: (Boolean) -> Unit,
    onChange: (Change) -> Unit,
) {
    val colors = LixingTheme.colors
    val icon = remember(iconCache, row.iconData) { iconCache.get(row.iconData) }
    val stackState = when (row.stackChecked) {
        true -> ToggleableState.On
        false -> ToggleableState.Off
        null -> ToggleableState.Indeterminate
    }
    val iconDetail = when {
        row.iconChecked -> "已设置"
        row.iconRule != null -> "显示名称"
        else -> "默认显示名称"
    }
    val stackDetail = when {
        row.stackRule?.enabled == true -> "已设置叠放"
        row.stackRule != null -> "已设置分开"
        row.effectiveStack == true -> "系统 · 当前叠放"
        row.effectiveStack == false -> "系统 · 当前分开"
        else -> "系统 · 当前未知"
    }
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) colors.surfaceRaised else colors.surface)
            .heightIn(min = 58.dp).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Box(Modifier.width(82.dp)) {
                LixingCheckbox(
                    "选择", selected, onSelect,
                    Modifier.semantics { contentDescription = "选择 ${row.title}" },
                    enabled = !busy && row.selectable,
                )
            }
        }
        Box(
            Modifier.size(32.dp).background(colors.surfaceRaised, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(icon, contentDescription = null, Modifier.size(24.dp))
            } else {
                BasicText(row.title.take(1), style = TextStyle(color = colors.textMuted, fontSize = 15.sp))
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BasicText(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            BasicText(row.detail, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = colors.textMuted, fontSize = 11.sp))
        }
        if (row.key.isNotBlank()) {
            if (selecting) {
                val iconStatus = if (row.iconChecked) "仅图标" else "显示名称"
                BasicText(
                    "$iconStatus · $stackDetail",
                    Modifier.width(148.dp),
                    style = TextStyle(color = colors.textMuted, fontSize = 11.sp),
                )
            } else {
                Column(Modifier.width(106.dp)) {
                    LixingCheckbox(
                        "仅图标", row.iconChecked, { onChange(row.iconChange(it)) },
                        Modifier.semantics { contentDescription = "${row.title} 的仅图标设置" },
                        enabled = !busy,
                    )
                    BasicText(iconDetail, Modifier.padding(start = 26.dp),
                        style = TextStyle(color = colors.textMuted, fontSize = 10.sp))
                }
                Column(Modifier.width(126.dp)) {
                    LixingTriStateCheckbox(
                        "叠放", stackState,
                        { onChange(row.stackChange(stackState != ToggleableState.On)) },
                        Modifier.semantics {
                            contentDescription = "${row.title} 的叠放设置"
                            stateDescription = stackDetail
                        },
                        enabled = !busy,
                    )
                    BasicText(stackDetail, Modifier.padding(start = 26.dp),
                        style = TextStyle(color = colors.textMuted, fontSize = 10.sp))
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().size(1.dp).background(colors.border.copy(alpha = 0.24f)))
}

@OptIn(ExperimentalFoundationApi::class)
@androidx.compose.runtime.Composable
private fun Picker(
    snapshot: Snapshot,
    error: String?,
    listReadFailed: Boolean,
    busy: Boolean,
    feedback: String?,
    onOpenSettings: () -> Unit,
    onOpenSupport: () -> Unit,
    onRemoveApps: (List<String>) -> Unit,
    onWrite: (RuleWriteRequest) -> Unit,
) {
    val colors = LixingTheme.colors
    val listState = rememberLazyListState()
    val iconCache = remember { TaskbarIconCache() }
    var search by remember { mutableStateOf("") }
    var checkedFilter by remember { mutableStateOf(CheckedFilter.ALL) }
    var sectionFilter by remember { mutableStateOf(ALL_APP_SECTIONS) }
    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var batchAction by remember { mutableStateOf<BatchAction?>(null) }
    var cleanupConfirmation by remember { mutableStateOf<List<String>?>(null) }
    val applyFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    var restoreApplyFocus by remember { mutableStateOf(false) }
    var headerHeight by remember { mutableStateOf(0) }
    val listBringIntoView = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                // The sticky header covers the leading edge of the scrolling viewport.
                val inset = headerHeight.toFloat().coerceAtMost((containerSize - size).coerceAtLeast(0f))
                return when {
                    offset < inset -> offset - inset
                    offset + size > containerSize -> offset + size - containerSize
                    else -> 0f
                }
            }
        }
    }
    val apps = remember(snapshot.items, snapshot.rules, snapshot.stacks) { appRows(snapshot) }
    val history = remember(snapshot.history, snapshot.rules, snapshot.stacks, apps) { historyAppRows(snapshot, apps) }
    val saved = remember(snapshot.rules, snapshot.stacks, apps, history) {
        val known = (apps.flatMap { it.aliases } + history.flatMap { it.aliases }).map(::normalized).toSet()
        closedAppRows(snapshot, known)
    }
    val rows = remember(apps, history, saved) { pickerRows(apps, history, saved) }
    val visibleRows = remember(rows, search, checkedFilter, sectionFilter) {
        val words = search.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        rows.filter { row ->
            (sectionFilter == ALL_APP_SECTIONS || row.section.name == sectionFilter) &&
                checkedFilter.matches(row) && row.matchesSearch(words)
        }
    }
    val groups = remember(visibleRows) { visibleRows.groupBy { it.section } }
    val selectableKeys = remember(visibleRows) {
        visibleRows.filter(PickerRow::selectable).map(PickerRow::selectionKey).toSet()
    }
    val selectedRows = remember(visibleRows, selection) {
        visibleRows.filter { it.selectable && it.selectionKey in selection }.distinctBy(PickerRow::selectionKey)
    }
    val cleanupKeys = remember(rows, snapshot.history) {
        val historyIdentities = snapshot.history.filter { it.identity.startsWith("id:", ignoreCase = true) }
            .map { normalized(it.identity) }.toSet()
        val historyProcesses = snapshot.history.filterNot { it.identity.startsWith("id:", ignoreCase = true) }
            .map { normalized(it.processIdentity.ifBlank { it.identity }) }.filter(String::isNotBlank).toSet()
        rows.filter { row ->
            row.selectable && (row.section != AppSection.ACTIVE || row.iconRule != null || row.stackRule != null ||
                row.selectionKey in historyIdentities || row.processKey?.let { normalized(it) in historyProcesses } == true)
        }.map(PickerRow::selectionKey).toSet()
    }
    val cleanupTargets = selectedRows.filter { it.selectionKey in cleanupKeys }.map(PickerRow::key)
    val resetting = search.isNotEmpty() || checkedFilter != CheckedFilter.ALL || sectionFilter != ALL_APP_SECTIONS
    val allSelected = selectableKeys.isNotEmpty() && selection.containsAll(selectableKeys)
    val scrollbarStyle = remember(colors.textMuted) {
        ScrollbarStyle(
            minimalHeight = 32.dp,
            thickness = 8.dp,
            shape = RoundedCornerShape(4.dp),
            hoverDurationMillis = 120,
            unhoverColor = colors.textMuted.copy(alpha = 0.62f),
            hoverColor = colors.textMuted,
        )
    }
    val serviceStatus = when {
        snapshot.status == "loading" -> "正在连接任务栏…"
        snapshot.status == "inactive" -> "任务栏功能未运行，请启动 Windhawk 模块"
        snapshot.status == "pending" -> "等待任务栏更新…"
        snapshot.status == "unknown" -> "任务栏状态未知"
        snapshot.historyError != null -> "历史记录暂时无法读取"
        else -> null
    }
    val status = when {
        busy -> "正在处理所选设置…"
        error != null -> error
        else -> listOfNotNull(feedback, serviceStatus).joinToString(" · ").ifBlank { "任务栏已连接" }
    }

    fun clearSelection() {
        selection = emptySet()
        cleanupConfirmation = null
    }

    fun resetFilters() {
        search = ""
        checkedFilter = CheckedFilter.ALL
        sectionFilter = ALL_APP_SECTIONS
        clearSelection()
    }

    LaunchedEffect(search, checkedFilter, sectionFilter) { listState.scrollToItem(0) }
    LaunchedEffect(selectableKeys) { selection = selection.intersect(selectableKeys) }
    LaunchedEffect(cleanupConfirmation, restoreApplyFocus) {
        if (cleanupConfirmation != null) cancelFocus.requestFocus()
        else if (restoreApplyFocus) {
            applyFocus.requestFocus()
            restoreApplyFocus = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(colors.canvas).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText("应用列表", Modifier.weight(1f).semantics { heading() },
                style = TextStyle(color = colors.text, fontSize = 25.sp, fontWeight = FontWeight.SemiBold))
            LixingButton(
                if (selecting) "退出选择" else "批量选择",
                { selecting = !selecting; clearSelection() },
                enabled = !busy && (selecting || selectableKeys.isNotEmpty()),
                variant = if (selecting) ButtonVariant.Selected else ButtonVariant.Default,
            )
            LixingButton("支持开发", onOpenSupport)
            LixingButton("设置", onOpenSettings, variant = ButtonVariant.Quiet)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LixingTextField("搜索应用", search, { search = it; clearSelection() }, Modifier.weight(1f),
                enabled = !busy)
            LixingSelect(
                "勾选状态", CheckedFilter.entries.map { ChoiceOption(it.name, it.label) }, checkedFilter.name,
                {
                    val requested = CheckedFilter.valueOf(it)
                    if (checkedFilter != requested) {
                        checkedFilter = requested
                        clearSelection()
                    }
                },
                Modifier.width(190.dp), enabled = !busy,
            )
            LixingSelect(
                "应用分类",
                listOf(ChoiceOption(ALL_APP_SECTIONS, "全部分类")) + AppSection.entries.map { ChoiceOption(it.name, it.label) },
                sectionFilter,
                {
                    if (sectionFilter != it) {
                        sectionFilter = it
                        clearSelection()
                    }
                },
                Modifier.width(176.dp), enabled = !busy,
            )
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val selectionSummary = if (selecting) " · 已选 ${selectedRows.size} 项" else ""
            BasicText(
                "显示 ${visibleRows.size} / ${rows.size} 个应用$selectionSummary", Modifier.weight(1f),
                style = TextStyle(color = colors.textMuted, fontSize = 12.sp),
            )
            if (selecting) LixingButton(
                if (allSelected) "取消全选" else "全选当前结果",
                {
                    selection = if (allSelected) emptySet() else selectableKeys
                    cleanupConfirmation = null
                },
                enabled = selectableKeys.isNotEmpty() && !busy,
                variant = ButtonVariant.Quiet,
            )
            if (resetting) LixingButton("重置筛选", ::resetFilters, enabled = !busy, variant = ButtonVariant.Quiet)
        }
        val listShape = RoundedCornerShape(8.dp)
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(listShape).background(colors.surface)
                .border(1.dp, colors.border, listShape).padding(1.dp),
        ) {
            if (visibleRows.isEmpty()) {
                val message = when {
                    snapshot.status == "loading" -> "正在读取应用…"
                    listReadFailed -> "应用列表暂时无法读取"
                    snapshot.historyError != null && sectionFilter != AppSection.ACTIVE.name -> "历史记录暂时无法读取"
                    !snapshot.historyLoaded && sectionFilter == AppSection.HISTORY.name -> "正在读取历史应用…"
                    snapshot.status == "inactive" && rows.isEmpty() -> "等待任务栏模块连接"
                    resetting -> "没有匹配的应用"
                    else -> "暂无可显示的应用"
                }
                BasicText(message, Modifier.align(Alignment.Center).padding(12.dp),
                    style = TextStyle(color = colors.textMuted, fontSize = 14.sp))
            } else {
                CompositionLocalProvider(LocalBringIntoViewSpec provides listBringIntoView) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                    ) {
                        groups.forEach { (section, group) ->
                            stickyHeader(key = "section:${section.name}", contentType = "section") {
                                Row(
                                    Modifier.fillMaxWidth().background(colors.surfaceRaised)
                                        .onSizeChanged { headerHeight = it.height }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BasicText(section.label, Modifier.weight(1f).semantics { heading() },
                                        style = TextStyle(color = colors.text, fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp))
                                    BasicText("${group.size} 项", style = TextStyle(color = colors.textMuted, fontSize = 11.sp))
                                }
                            }
                            items(group, key = PickerRow::listKey, contentType = { "app" }) { row ->
                                AppSettingsRow(
                                    row, iconCache, selecting, row.selectionKey in selection, busy,
                                    onSelect = { checked ->
                                        selection = if (checked) selection + row.selectionKey else selection - row.selectionKey
                                        cleanupConfirmation = null
                                    },
                                    onChange = { onWrite(RuleWriteRequest(listOf(it), batch = false)) },
                                )
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).width(12.dp).fillMaxHeight()
                        .semantics { contentDescription = "应用列表滚动条" },
                    style = scrollbarStyle,
                )
            }
        }
        if (selecting) {
            Column(
                Modifier.fillMaxWidth().background(colors.surfaceRaised, RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val confirmation = cleanupConfirmation
                if (confirmation != null) {
                    val sameTargets = confirmation == cleanupTargets
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BasicText(
                            if (sameTargets) "清理 ${confirmation.size} 项应用的历史和设置？"
                            else "可清理的应用已变化，请重新确认。",
                            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                            style = TextStyle(color = colors.text, fontSize = 13.sp),
                        )
                        LixingButton("取消", {
                            cleanupConfirmation = null
                            restoreApplyFocus = true
                        }, Modifier.focusRequester(cancelFocus), variant = ButtonVariant.Quiet)
                        LixingButton("确认清理", {
                            if (sameTargets) {
                                cleanupConfirmation = null
                                onRemoveApps(confirmation)
                            }
                        }, enabled = sameTargets && !busy, variant = ButtonVariant.Danger)
                    }
                    if (sameTargets) {
                        BasicText("清理可能同时移除关联的程序级规则",
                            style = TextStyle(color = colors.textMuted, fontSize = 11.sp))
                    }
                } else {
                    val action = batchAction
                    val ruleChanges = remember(action, selectedRows) {
                        if (action == null || action == BatchAction.CLEANUP) emptyList()
                        else selectedRows.map(action::change).distinctBy { normalized(it.key) }
                    }
                    val count = if (action == BatchAction.CLEANUP) cleanupTargets.size else selectedRows.size
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LixingSelect(
                            "批量操作", BatchAction.entries.map { ChoiceOption(it.name, it.label) },
                            action?.name, { batchAction = BatchAction.valueOf(it) }, Modifier.weight(1f),
                            enabled = !busy,
                        )
                        LixingButton(
                            if (action == BatchAction.CLEANUP) "清理 $count 项" else "应用到 $count 项",
                            {
                                if (action == BatchAction.CLEANUP) {
                                    cleanupConfirmation = cleanupTargets
                                } else {
                                    onWrite(RuleWriteRequest(ruleChanges, batch = true))
                                }
                            },
                            Modifier.focusRequester(applyFocus),
                            enabled = action != null && count > 0 && !busy,
                            variant = if (action == BatchAction.CLEANUP) ButtonVariant.Danger else ButtonVariant.Primary,
                        )
                    }
                    val scopeNotice = when {
                        action == BatchAction.CLEANUP && count != selectedRows.size ->
                            "所选应用中有 $count 项存在可清理的记录"
                        ruleChanges.any {
                            it.key.startsWith("path:", ignoreCase = true)
                        } ->
                            "包含同程序规则，也会影响共用该规则的其他应用"
                        else -> null
                    }
                    if (scopeNotice != null) BasicText(scopeNotice,
                        style = TextStyle(color = colors.textMuted, fontSize = 11.sp))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText(status, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = TextStyle(color = colors.textMuted, fontSize = 12.sp))
            BasicText("应用记录 ${snapshot.appCount?.toString() ?: "—"}/500",
                style = TextStyle(color = colors.textMuted, fontSize = 11.sp))
        }
    }
}

@androidx.compose.runtime.Composable
private fun TaskbarSettingsWindow(
    logo: ImageBitmap,
    autostartState: AutostartState,
    autostartChanging: Boolean,
    onRetryAutostart: (() -> Unit)?,
    onAutostartChange: (Boolean) -> Unit,
    autoNewEnabled: Boolean?,
    autoNewChanging: Boolean,
    autoNewError: String?,
    onAutoNewChange: (Boolean) -> Unit,
    onOpenHelp: () -> Unit,
    updateLabel: String,
    onOpenUpdate: (() -> Unit)?,
    onClose: () -> Unit,
) {
    Window(
        onCloseRequest = onClose,
        title = "任务栏标签设置",
        icon = androidx.compose.ui.graphics.painter.BitmapPainter(logo),
        state = rememberWindowState(width = 500.dp, height = 440.dp),
    ) {
        LixingTheme {
            val colors = LixingTheme.colors
            val autostartDetail = when (autostartState.value) {
                AutostartState.Value.LOADING -> "正在读取设置…"
                AutostartState.Value.UNAVAILABLE -> "无法确认当前安装"
                AutostartState.Value.ENABLED -> "已设置登录时启动"
                AutostartState.Value.DISABLED -> "未设置登录启动"
                AutostartState.Value.FOREIGN -> "已有其他登录启动设置"
                AutostartState.Value.ERROR -> "读取失败，请重试"
            }
            val autostartCheck = when (autostartState.value) {
                AutostartState.Value.ENABLED -> ToggleableState.On
                AutostartState.Value.DISABLED -> ToggleableState.Off
                else -> ToggleableState.Indeterminate
            }
            val autoNewCheck = when (autoNewEnabled) {
                true -> ToggleableState.On
                false -> ToggleableState.Off
                null -> ToggleableState.Indeterminate
            }
            Column(
                Modifier.fillMaxSize().background(colors.canvas).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BasicText("任务栏设置", Modifier.semantics { heading() },
                    style = TextStyle(color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
                Column(
                    Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(8.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BasicText("新应用自动设置", style = TextStyle(color = colors.text, fontSize = 14.sp))
                            BasicText(autoNewError ?: "首次出现时隐藏名称并叠放",
                                style = TextStyle(color = colors.textMuted, fontSize = 12.sp))
                        }
                        LixingTriStateCheckbox("自动设置", autoNewCheck,
                            { onAutoNewChange(autoNewEnabled != true) },
                            enabled = autoNewEnabled != null && !autoNewChanging)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BasicText("登录 Windows 后启动", style = TextStyle(color = colors.text, fontSize = 14.sp))
                            BasicText(autostartDetail, style = TextStyle(color = colors.textMuted, fontSize = 12.sp))
                        }
                        if (onRetryAutostart != null && (autostartState.value == AutostartState.Value.ERROR ||
                                autostartState.value == AutostartState.Value.UNAVAILABLE)) {
                            LixingButton("重试", onRetryAutostart, variant = ButtonVariant.Quiet)
                        }
                        LixingTriStateCheckbox("登录时启动", autostartCheck,
                            { onAutostartChange(autostartState.value != AutostartState.Value.ENABLED) },
                            enabled = autostartState.toggleEnabled && !autostartChanging)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LixingButton("使用说明", onOpenHelp, variant = ButtonVariant.Quiet)
                    if (onOpenUpdate != null) LixingButton(updateLabel, onOpenUpdate, variant = ButtonVariant.Quiet)
                }
            }
        }
    }
}


@androidx.compose.runtime.Composable
private fun TaskbarHelpWindow(
    visible: Boolean,
    onClose: () -> Unit,
) {
    val windowState = rememberWindowState(width = 500.dp, height = 390.dp)
    if (!visible) return
    val logo = remember { PickerIcon.load() }
    Window(
        onCloseRequest = onClose,
        title = "任务栏标签说明",
        state = windowState,
        icon = androidx.compose.ui.graphics.painter.BitmapPainter(logo),
    ) {
        LixingTheme {
            val colors = LixingTheme.colors
            LixingChoiceHost {
                Column(
                    Modifier.fillMaxSize().background(colors.canvas)
                        .verticalScroll(rememberScrollState()).padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BasicText(
                        "使用说明",
                        Modifier.semantics { heading() },
                        style = TextStyle(color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                    )
                    BasicText("按名称、勾选状态和分类查找应用。批量选择中的全选只作用于当前结果。",
                        style = TextStyle(color = colors.text, fontSize = 13.sp))
                    BasicText("每行一个应用，可设置名称和窗口叠放。同程序规则可能影响同一程序的其他应用。",
                        style = TextStyle(color = colors.text, fontSize = 13.sp))
                    BasicText("新应用自动设置只作用于首次出现且没有规则的应用；关闭后保留已有设置。",
                        style = TextStyle(color = colors.text, fontSize = 13.sp))
                    BasicText("开启仅图标会自动叠放；自动叠放随仅图标关闭，手动叠放保留。",
                        style = TextStyle(color = colors.text, fontSize = 13.sp))
                    BasicText("最多记录 500 个应用；超出时移除最久未打开项。",
                        style = TextStyle(color = colors.text, fontSize = 13.sp))
                    BasicText("手动清理会同时删除历史和设置；再次出现时可按“新应用自动设置”重新配置。",
                        style = TextStyle(color = colors.text, fontSize = 13.sp))
                    BasicText("默认显示名称；未设置叠放时跟随 Windows。",
                        style = TextStyle(color = colors.text, fontSize = 13.sp))
                    BasicText("关闭主窗口会收进托盘，可从托盘重开或退出。",
                        style = TextStyle(color = colors.text, fontSize = 13.sp))
                }
            }
        }
    }
}
