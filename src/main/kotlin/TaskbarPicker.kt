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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
        val process = ProcessBuilder(launcher.toString(), argument).start()
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
                result == "unavailable" -> AutostartState(AutostartState.Value.UNAVAILABLE)
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

private fun bringPickerToFront() {
    EventQueue.invokeLater {
        Frame.getFrames().firstOrNull { it.title == PICKER_WINDOW_TITLE }?.let { frame ->
            if (!frame.isVisible) frame.isVisible = true
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
    data class ChangeRequested(val change: Change) : PickerAction
    data class SignalReceived(val signal: BridgeSignal) : PickerAction
    data class EventBatch(val mask: Int) : PickerAction
    data class InstanceCommandReceived(val command: AppInstanceCommand) : PickerAction
    data class AutostartChangeRequested(val enabled: Boolean) : PickerAction
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
    val changes = remember { Channel<Change>(Channel.UNLIMITED) }
    val cleanupRequests = remember { Channel<List<String>>(Channel.UNLIMITED) }
    val signals = remember { Channel<BridgeSignal>(Channel.UNLIMITED) }
    val eventWakeups = remember { Channel<Unit>(Channel.CONFLATED) }
    val autostartChanges = remember { Channel<Boolean>(Channel.UNLIMITED) }
    val autoNewChanges = remember { Channel<Boolean>(Channel.UNLIMITED) }
    val bridge = remember { TaskbarBridge() }
    val logo = remember { PickerIcon.load() }
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
    var cleanupSelectionMode by remember { mutableStateOf(false) }
    var selectedCleanupKeys by remember { mutableStateOf(emptySet<String>()) }
    var trayMenuError by remember { mutableStateOf<String?>(null) }
    var eventError by remember { mutableStateOf<String?>(null) }
    var windowVisible by remember { mutableStateOf(initialVisible) }
    var helpVisible by remember { mutableStateOf(false) }
    var supportVisible by remember { mutableStateOf(false) }
    var updateVisible by remember { mutableStateOf(false) }
    val updater = remember(installation) { if (installation != null) packagedUpdater() else null }
    val updateState by remember(updater) { updater?.state ?: MutableStateFlow(JsonObject(emptyMap())) }.collectAsState()
    val updateVersion = availableUpdate(updateState)
    val updateScope = rememberCoroutineScope()
    val updatePreference = remember { TaskbarProfile.directory.resolve("auto-update.dat") }
    var automaticUpdate by remember { mutableStateOf(runCatching { Files.readString(updatePreference) == "1" }.getOrDefault(false)) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var automaticAttempt by remember { mutableStateOf<String?>(null) }
    suspend fun update(action: String): Boolean {
        val service = updater ?: return false
        if (updateBusy) return false
        updateBusy = true
        updateError = null
        try {
            val response = service.request(action)
            if (!response.flag("ok")) updateError = response.text("error") ?: "更新操作失败"
            return response.flag("ok")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            updateError = failure.message ?: "更新服务暂不可用"
            return false
        } finally {
            updateBusy = false
        }
    }
    DisposableEffect(updater) { onDispose { updater?.close() } }
    LaunchedEffect(updater) { if (updater != null) update("check") }
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
        signals.send(BridgeSignal.VisibilityChanged)
    }

    DisposableEffect(bridge) {
        onDispose {
            changes.close()
            cleanupRequests.close()
            signals.close()
            eventWakeups.close()
            autostartChanges.close()
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

        suspend fun refreshAutostartSafely(force: Boolean = false) {
            val currentInstallation = installation
            if (currentInstallation == null) {
                autostartState = AutostartState(AutostartState.Value.UNAVAILABLE)
                return
            }
            if (!force && autostartState.value != AutostartState.Value.LOADING) return
            autostartChanging = true
            autostartState = AutostartState(AutostartState.Value.LOADING)
            try {
                autostartState = withContext(Dispatchers.IO) { currentInstallation.queryAutostart() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                autostartState = AutostartState(AutostartState.Value.ERROR, failure.message ?: "读取失败")
            } finally {
                autostartChanging = false
            }
        }

        suspend fun setAutostartSafely(enabled: Boolean) {
            val currentInstallation = installation
            if (currentInstallation == null) {
                autostartState = AutostartState(AutostartState.Value.UNAVAILABLE)
                autostartChanging = false
                return
            }
            autostartState = AutostartState(AutostartState.Value.LOADING)
            try {
                autostartState = withContext(Dispatchers.IO) { currentInstallation.setAutostart(enabled) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                autostartState = AutostartState(AutostartState.Value.ERROR, failure.message ?: "设置失败")
            } finally {
                autostartChanging = false
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
                selectedCleanupKeys = emptySet()
                cleanupSelectionMode = false
                cleanupFeedback = when {
                    result.removedCount > 0 -> "已清理 ${result.removedCount} 个应用"
                    else -> "所选应用已无可清理记录"
                }
                writeError = null
                refreshSafely(includeItems = true, forceHistory = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                cleanupError = "清理失败，请重试"
                TaskbarDiagnostics.record("TaskbarBridge[remove-apps] ${failure.message ?: failure.javaClass.simpleName}")
            } finally {
                cleanupChanging = false
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
                    autostartChanges.onReceive { PickerAction.AutostartChangeRequested(it) }
                    autoNewChanges.onReceive { PickerAction.AutoNewChangeRequested(it) }
                    cleanupRequests.onReceive { PickerAction.CleanupRequested(it) }
                }) {
                    is PickerAction.ChangeRequested -> {
                        var saved = false
                        try {
                            withContext(Dispatchers.IO) { bridge.set(action.change) }
                            writeError = null
                            saved = true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            writeError = "保存失败：${failure.message ?: "请重试"}"
                        } finally {
                            changing = false
                        }
                        if (saved) refreshSafely(includeItems = false, forceHistory = false)
                    }
                    is PickerAction.SignalReceived -> when (val signal = action.signal) {
                        BridgeSignal.Ready -> {
                            ready = true
                            eventError = null
                            refreshSafely(includeItems = true, forceHistory = true)
                            if (visibilityState.value) refreshAutostartSafely()
                        }
                        BridgeSignal.VisibilityChanged -> {
                            if (visibilityState.value) refreshAutostartSafely()
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
                            windowVisible = true
                            bringPickerToFront()
                            refreshSafely(includeItems = true, forceHistory = true)
                            refreshAutostartSafely(force = true)
                        }
                        AppInstanceCommand.TRAY -> Unit
                        AppInstanceCommand.QUIT -> onExit()
                    }
                    is PickerAction.AutostartChangeRequested -> setAutostartSafely(action.enabled)
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

    Window(
        onCloseRequest = {
            windowVisible = false
            helpVisible = false
            supportVisible = false
            updateVisible = false
        },
        visible = windowVisible,
        title = PICKER_WINDOW_TITLE,
        icon = androidx.compose.ui.graphics.painter.BitmapPainter(logo),
        state = rememberWindowState(width = 640.dp, height = 650.dp),
    ) {
        LixingTheme {
            LixingChoiceHost {
                Picker(
                    snapshot = snapshot,
                    error = displayMessage,
                    changing = changing,
                    autostartState = autostartState,
                    autostartChanging = autostartChanging,
                    onAutostartChange = { enabled ->
                        autostartChanging = true
                        if (autostartChanges.trySend(enabled).isFailure) autostartChanging = false
                    },
                    autoNewChanging = autoNewChanging,
                    onAutoNewChange = { enabled ->
                        autoNewChanging = true
                        if (autoNewChanges.trySend(enabled).isFailure) autoNewChanging = false
                    },
                    onOpenHelp = { helpVisible = true },
                    onOpenSupport = { supportVisible = true },
                    updateLabel = if (updateVersion != null) "有更新" else "版本",
                    onOpenUpdate = if (updater != null) { { updateVisible = true } } else null,
                    cleanupSelectionMode = cleanupSelectionMode,
                    selectedCleanupKeys = selectedCleanupKeys,
                    cleanupChanging = cleanupChanging,
                    cleanupFeedback = cleanupFeedback,
                    onCleanupModeChange = { enabled ->
                        cleanupSelectionMode = enabled
                        selectedCleanupKeys = emptySet()
                        cleanupError = null
                        cleanupFeedback = null
                    },
                    onCleanupSelectionChange = { key, selected ->
                        val normalizedKey = normalized(key)
                        selectedCleanupKeys = if (selected) selectedCleanupKeys + normalizedKey
                        else selectedCleanupKeys - normalizedKey
                        cleanupFeedback = null
                    },
                    onRemoveApps = { identities ->
                        cleanupChanging = true
                        if (cleanupRequests.trySend(identities).isFailure) cleanupChanging = false
                    },
                ) {
                    changing = true
                    changes.trySend(it)
                }
            }
        }
    }

    TaskbarHelpWindow(
        visible = helpVisible && windowVisible,
        onClose = { helpVisible = false },
    )
    SupportDevelopmentWindow(visible = supportVisible && windowVisible, logo = logo, onClose = { supportVisible = false })
    if (updater != null) TaskbarUpdateWindow(
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

@androidx.compose.runtime.Composable
private fun AppSettingsRow(
    title: String,
    detail: String,
    iconData: String,
    iconRule: SavedSetting?,
    stackRule: SavedSetting?,
    effectiveStack: Boolean?,
    iconKey: String,
    processKey: String?,
    canonicalStackKey: String,
    stackKey: String,
    iconCache: TaskbarIconCache,
    changing: Boolean,
    cleanupSelectionMode: Boolean,
    cleanupSelected: Boolean,
    cleanupEligible: Boolean,
    onCleanupSelectionChange: (Boolean) -> Unit,
    onChange: (Change) -> Unit,
) {
    val colors = LixingTheme.colors
    val icon = remember(iconCache, iconData) { iconCache.get(iconData) }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (icon != null) Image(icon, contentDescription = null, Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = colors.text, fontSize = 14.sp))
            BasicText(detail, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = colors.text, fontSize = 11.sp))
        }
        if (cleanupSelectionMode) {
            Column(Modifier.width(112.dp), verticalArrangement = Arrangement.Center) {
                if (cleanupEligible) {
                    LixingCheckbox(
                        "选择",
                        cleanupSelected,
                        onCleanupSelectionChange,
                        enabled = !changing,
                    )
                    BasicText(
                        "删除历史和设置",
                        modifier = Modifier.padding(start = 27.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = colors.text.copy(alpha = 0.76f), fontSize = 9.sp),
                    )
                } else {
                    BasicText("暂无已存记录", style = TextStyle(color = colors.text.copy(alpha = 0.68f), fontSize = 10.sp))
                }
            }
        } else {
            Column(Modifier.width(112.dp), verticalArrangement = Arrangement.Center) {
                LixingCheckbox(
                    "仅图标",
                    iconRule?.enabled == true,
                    { enabled -> onChange(Change(
                        key = iconKey,
                        processKey = processKey,
                        canonicalStackKey = canonicalStackKey,
                        hide = enabled,
                    )) },
                    enabled = !changing,
                )
                BasicText(
                    when {
                        iconRule?.enabled == true -> "已设置：仅图标"
                        iconRule != null -> "已设置：显示名称"
                        else -> "默认：显示名称"
                    },
                    modifier = Modifier.padding(start = 27.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = colors.text.copy(alpha = 0.76f), fontSize = 9.sp),
                )
            }
            Column(Modifier.width(112.dp), verticalArrangement = Arrangement.Center) {
                val stackState = when (stackRule?.enabled ?: effectiveStack) {
                    true -> ToggleableState.On
                    false -> ToggleableState.Off
                    null -> ToggleableState.Indeterminate
                }
                LixingTriStateCheckbox(
                    "叠放",
                    stackState,
                    { onChange(Change(key = stackKey, stack = stackState != ToggleableState.On)) },
                    enabled = !changing,
                )
                BasicText(
                    when {
                        stackRule != null && stackRule.enabled -> "已设置：叠放"
                        stackRule != null -> "已设置：分开"
                        effectiveStack == true -> "跟随系统：当前叠放"
                        effectiveStack == false -> "跟随系统：当前分开"
                        else -> "跟随系统：当前未知"
                    },
                    modifier = Modifier.padding(start = 27.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = colors.text.copy(alpha = 0.76f), fontSize = 9.sp),
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Picker(
    snapshot: Snapshot,
    error: String?,
    changing: Boolean,
    autostartState: AutostartState,
    autostartChanging: Boolean,
    onAutostartChange: (Boolean) -> Unit,
    autoNewChanging: Boolean,
    onAutoNewChange: (Boolean) -> Unit,
    onOpenHelp: () -> Unit,
    onOpenSupport: () -> Unit,
    updateLabel: String,
    onOpenUpdate: (() -> Unit)?,
    cleanupSelectionMode: Boolean,
    selectedCleanupKeys: Set<String>,
    cleanupChanging: Boolean,
    cleanupFeedback: String?,
    onCleanupModeChange: (Boolean) -> Unit,
    onCleanupSelectionChange: (String, Boolean) -> Unit,
    onRemoveApps: (List<String>) -> Unit,
    onChange: (Change) -> Unit,
) {
    val colors = LixingTheme.colors
    val listState = rememberLazyListState()
    val iconCache = remember { TaskbarIconCache() }
    val apps = remember(snapshot.items, snapshot.rules, snapshot.stacks) { appRows(snapshot) }
    val history = remember(snapshot.history, snapshot.rules, snapshot.stacks, apps) { historyAppRows(snapshot, apps) }
    val activeAliases = remember(apps) { apps.flatMap { it.aliases }.map(::normalized).toSet() }
    val historyAliases = remember(history) { history.flatMap { it.aliases }.map(::normalized).toSet() }
    val closed = remember(snapshot.rules, snapshot.stacks, activeAliases, historyAliases) {
        closedAppRows(snapshot, activeAliases + historyAliases)
    }
    val cleanupTargets = remember(apps, history, closed, snapshot.history) {
        val identities = mutableListOf<String>()
        history.forEach { identities += it.key }
        closed.forEach { identities += it.key }
        apps.forEach { app ->
            val identity = app.identity.ifBlank { app.processIdentity }
            val hasHistory = snapshot.history.any { item ->
                if (item.identity.startsWith("id:", ignoreCase = true)) {
                    normalized(item.identity) == normalized(app.identity)
                } else {
                    normalized(item.processIdentity.ifBlank { item.identity }) == normalized(app.processIdentity)
                }
            }
            if (app.iconRule != null || app.stackRule != null || hasHistory) identities += identity
        }
        identities.filter(String::isNotBlank).distinctBy(::normalized)
    }
    val cleanupTargetKeys = remember(cleanupTargets) { cleanupTargets.map(::normalized).toSet() }
    LaunchedEffect(cleanupTargetKeys, selectedCleanupKeys) {
        (selectedCleanupKeys - cleanupTargetKeys).forEach { onCleanupSelectionChange(it, false) }
    }
    val selectedTargets = cleanupTargets.filter { normalized(it) in selectedCleanupKeys }
    val status = error ?: cleanupFeedback ?: when (snapshot.status) {
        "loading" -> "正在连接任务栏…"
        "inactive" -> "任务栏功能未运行；请启动 Windhawk 模块。"
        "pending" -> "已保存，等待任务栏更新…"
        else -> "${snapshot.rules.values.count { it.enabled }} 项仅图标设置 · ${snapshot.stacks.values.count { it.enabled }} 项叠放"
    }
    val scrollbarStyle = remember(colors.text) {
        ScrollbarStyle(
            minimalHeight = 32.dp,
            thickness = 12.dp,
            shape = RoundedCornerShape(6.dp),
            hoverDurationMillis = 120,
            unhoverColor = colors.text.copy(alpha = 0.68f),
            hoverColor = colors.text,
        )
    }

    Column(
        Modifier.fillMaxSize().background(colors.canvas).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "按应用设置任务栏",
                Modifier.weight(1f),
                style = TextStyle(color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
            )
            LixingButton(
                if (cleanupSelectionMode) "取消清理" else "清理",
                { onCleanupModeChange(!cleanupSelectionMode) },
                enabled = cleanupSelectionMode || cleanupTargets.isNotEmpty(),
                variant = ButtonVariant.Quiet,
            )
            LixingButton("说明", onOpenHelp, variant = ButtonVariant.Quiet)
            LixingButton("支持开发", onOpenSupport, variant = ButtonVariant.Quiet)
            if (onOpenUpdate != null) LixingButton(updateLabel, onOpenUpdate, variant = ButtonVariant.Quiet)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(status, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = colors.text, fontSize = 12.sp))
            BasicText(
                "应用记录 ${snapshot.appCount?.toString() ?: "—"}/500",
                modifier = Modifier.padding(start = 10.dp),
                maxLines = 1,
                style = TextStyle(color = colors.text.copy(alpha = 0.76f), fontSize = 10.sp),
            )
        }
        if (cleanupSelectionMode) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    "已选 ${selectedTargets.size} 项 · 同时删除历史和设置",
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = colors.text, fontSize = 11.sp),
                )
                LixingButton(
                    "删除所选",
                    { onRemoveApps(selectedTargets) },
                    enabled = selectedTargets.isNotEmpty() && !cleanupChanging,
                    variant = ButtonVariant.Danger,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                val autostartDetail = when (autostartState.value) {
                    AutostartState.Value.LOADING -> "正在读取设置…"
                    AutostartState.Value.UNAVAILABLE -> "请从正式安装启动后设置"
                    AutostartState.Value.ENABLED -> "已设置登录时启动"
                    AutostartState.Value.DISABLED -> "未设置登录启动"
                    AutostartState.Value.FOREIGN -> "已有其他登录启动设置"
                    AutostartState.Value.ERROR -> "读取失败，请重试"
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("登录 Windows 后启动", Modifier.weight(1f),
                        style = TextStyle(color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                    val autostartToggleState = when (autostartState.value) {
                        AutostartState.Value.ENABLED -> ToggleableState.On
                        AutostartState.Value.DISABLED -> ToggleableState.Off
                        else -> ToggleableState.Indeterminate
                    }
                    LixingTriStateCheckbox(
                        "开机启动", autostartToggleState,
                        { onAutostartChange(autostartState.value != AutostartState.Value.ENABLED) },
                        enabled = autostartState.toggleEnabled && !autostartChanging,
                    )
                }
                BasicText(autostartDetail, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = colors.text.copy(alpha = 0.76f), fontSize = 10.sp))
            }
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("新应用自动设置", Modifier.weight(1f),
                        style = TextStyle(color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                    val autoNewState = when (snapshot.autoNewEnabled) {
                        true -> ToggleableState.On
                        false -> ToggleableState.Off
                        null -> ToggleableState.Indeterminate
                    }
                    LixingTriStateCheckbox(
                        "自动设置", autoNewState,
                        { onAutoNewChange(snapshot.autoNewEnabled != true) },
                        enabled = snapshot.autoNewEnabled != null && !autoNewChanging,
                    )
                }
                BasicText("首次出现时隐藏名称并叠放", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = colors.text.copy(alpha = 0.76f), fontSize = 10.sp))
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(end = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (apps.isEmpty()) {
                    item(key = "empty:active") {
                        BasicText("任务栏上暂无可读取的应用按钮", style = TextStyle(color = colors.text, fontSize = 14.sp))
                    }
                } else {
                    item(key = "section:active") {
                        BasicText("当前在任务栏显示", Modifier.padding(top = 4.dp),
                            style = TextStyle(color = colors.text, fontWeight = FontWeight.SemiBold))
                    }
                    itemsIndexed(
                        items = apps,
                        key = { _, app -> "active:${normalized(app.uiKey)}" },
                        contentType = { _, _ -> "app" },
                    ) { _, app ->
                        if (app.key.isBlank()) {
                            BasicText("无法识别应用", style = TextStyle(color = colors.text, fontSize = 12.sp))
                        } else {
                            val detailParts = buildList {
                                app.windowCount?.let { add("$it 个窗口") }
                                ruleLabel(app.key).ifBlank { "应用身份未知" }.let(::add)
                                val processScoped = app.identity.isNotBlank() &&
                                    listOfNotNull(app.iconRule?.key, app.stackRule?.key)
                                        .any { normalized(it) == normalized(app.processIdentity) && normalized(it) != normalized(app.identity) }
                                if (processScoped) add("同程序")
                            }
                            AppSettingsRow(
                                title = app.title,
                                detail = detailParts.joinToString(" · "),
                                iconData = app.icon,
                                iconRule = app.iconRule,
                                stackRule = app.stackRule,
                                effectiveStack = app.effectiveStack,
                                iconKey = app.iconRule?.key ?: app.key,
                                processKey = app.processIdentity,
                                canonicalStackKey = app.key,
                                stackKey = app.key,
                                iconCache = iconCache,
                                changing = changing || cleanupChanging,
                                cleanupSelectionMode = cleanupSelectionMode,
                                cleanupSelected = normalized(app.key) in selectedCleanupKeys,
                                cleanupEligible = normalized(app.key) in cleanupTargetKeys,
                                onCleanupSelectionChange = { selected -> onCleanupSelectionChange(app.key, selected) },
                                onChange = onChange,
                            )
                        }
                    }
                }

                item(key = "section:history") {
                    BasicText("最近显示过", Modifier.padding(top = 14.dp),
                        style = TextStyle(color = colors.text, fontWeight = FontWeight.SemiBold))
                }
                if (snapshot.historyError != null) {
                    item(key = "history:error") {
                        BasicText("历史记录暂不可用，正在重试。", style = TextStyle(color = colors.text, fontSize = 12.sp))
                    }
                }
                if (!snapshot.historyLoaded && snapshot.historyError == null) {
                    item(key = "history:loading") {
                        BasicText("正在读取已显示应用…", style = TextStyle(color = colors.text, fontSize = 12.sp))
                    }
                } else if (history.isEmpty() && snapshot.historyError == null) {
                    item(key = "history:empty") {
                        BasicText("应用显示在任务栏后，会按最近出现时间保存在这里。",
                            style = TextStyle(color = colors.text, fontSize = 12.sp))
                    }
                }
                itemsIndexed(
                    items = history,
                    key = { _, app -> "history:${normalized(app.key)}" },
                    contentType = { _, _ -> "app" },
                ) { _, app ->
                    val detailParts = buildList {
                        add("最近显示 ${formatHistoryTime(app.lastSeenUnixMilliseconds)}")
                        val processScoped = app.identity.isNotBlank() &&
                            listOfNotNull(app.iconRule?.key, app.stackRule?.key)
                                .any { normalized(it) == normalized(app.processIdentity) && normalized(it) != normalized(app.identity) }
                        if (processScoped) add("同程序")
                    }
                    AppSettingsRow(
                        title = app.title,
                        detail = detailParts.joinToString(" · "),
                        iconData = app.icon,
                        iconRule = app.iconRule,
                        stackRule = app.stackRule,
                        effectiveStack = null,
                        iconKey = app.iconRule?.key ?: app.key,
                        processKey = app.processIdentity,
                        canonicalStackKey = app.key,
                        stackKey = app.key,
                        iconCache = iconCache,
                        changing = changing || cleanupChanging,
                        cleanupSelectionMode = cleanupSelectionMode,
                        cleanupSelected = normalized(app.key) in selectedCleanupKeys,
                        cleanupEligible = normalized(app.key) in cleanupTargetKeys,
                        onCleanupSelectionChange = { selected -> onCleanupSelectionChange(app.key, selected) },
                        onChange = onChange,
                    )
                }

                if (closed.isNotEmpty()) {
                    item(key = "section:saved") {
                        BasicText("其他已保存应用", Modifier.padding(top = 14.dp),
                            style = TextStyle(color = colors.text, fontWeight = FontWeight.SemiBold))
                    }
                }
                itemsIndexed(
                    items = closed,
                    key = { _, app -> "saved:${normalized(app.key)}" },
                    contentType = { _, _ -> "app" },
                ) { _, app ->
                    val settingSummary = when {
                        app.iconRule?.enabled == true -> "仅图标已开启"
                        app.iconRule != null -> "显示文字"
                        app.stackRule?.enabled == true -> "仅保存了窗口叠放方式"
                        else -> "未设置仅图标"
                    }
                    AppSettingsRow(
                        title = ruleLabel(app.key).ifBlank { app.key },
                        detail = settingSummary,
                        iconData = "",
                        iconRule = app.iconRule,
                        stackRule = app.stackRule,
                        effectiveStack = null,
                        iconKey = app.iconRule?.key ?: app.key,
                        processKey = null,
                        canonicalStackKey = app.key,
                        stackKey = app.stackRule?.key ?: app.key,
                        iconCache = iconCache,
                        changing = changing || cleanupChanging,
                        cleanupSelectionMode = cleanupSelectionMode,
                        cleanupSelected = normalized(app.key) in selectedCleanupKeys,
                        cleanupEligible = normalized(app.key) in cleanupTargetKeys,
                        onCleanupSelectionChange = { selected -> onCleanupSelectionChange(app.key, selected) },
                        onChange = onChange,
                    )
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).width(20.dp).fillMaxSize()
                    .semantics { contentDescription = "应用列表滚动条" },
                style = scrollbarStyle,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun TaskbarHelpWindow(
    visible: Boolean,
    onClose: () -> Unit,
) {
    val logo = remember { PickerIcon.load() }
    Window(
        onCloseRequest = onClose,
        visible = visible,
        title = "任务栏标签说明",
        state = rememberWindowState(width = 500.dp, height = 390.dp),
        icon = androidx.compose.ui.graphics.painter.BitmapPainter(logo),
    ) {
        LixingTheme {
            val colors = LixingTheme.colors
            LixingChoiceHost {
                Column(
                    Modifier.fillMaxSize().background(colors.canvas).padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BasicText(
                            "简要说明",
                            Modifier.weight(1f),
                            style = TextStyle(color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                        )
                        LixingButton("关闭", onClose, variant = ButtonVariant.Quiet)
                    }
                    BasicText("每行一个应用，可设置名称和窗口叠放。",
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
