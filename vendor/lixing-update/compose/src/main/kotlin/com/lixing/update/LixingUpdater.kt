package com.lixing.update

import java.io.BufferedWriter
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Client for the bundled updater. The owning application decides when to check and install. */
class LixingUpdater(private val directory: Path, private val configFile: Path) : AutoCloseable {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val sequence = AtomicLong()
    private val connection = Any()
    private var input: BufferedWriter? = null
    private var closed = false
    private val mutableState = MutableStateFlow(JsonObject(emptyMap()))
    val state: StateFlow<JsonObject> = mutableState.asStateFlow()

    suspend fun request(action: String, kind: String? = null): JsonObject {
        val id = sequence.incrementAndGet()
        val answer = CompletableDeferred<JsonObject>()
        val message = buildJsonObject {
            put("id", id)
            put("action", action)
            if (kind != null) put("kind", kind)
        }
        try {
            withContext(Dispatchers.IO) {
                synchronized(connection) {
                    check(!closed) { "更新服务已关闭" }
                    val writer = input ?: start()
                    pending[id] = answer
                    writer.write(message.toString())
                    writer.newLine()
                    writer.flush()
                }
            }
            return answer.await()
        } finally {
            pending.remove(id)
        }
    }

    private fun start(): BufferedWriter {
        val path = directory.toAbsolutePath()
        val processBuilder = ProcessBuilder(
            path.resolve("node.exe").toString(),
            "--no-warnings",
            path.resolve("cli.cjs").toString(),
            "--config",
            configFile.toAbsolutePath().toString(),
        )
        processBuilder.environment().remove("NODE_OPTIONS")
        processBuilder.environment().remove("NODE_PATH")
        val process = processBuilder.start()
        val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
        input = writer
        io.launch { process.errorStream.use { it.transferTo(java.io.OutputStream.nullOutputStream()) } }
        io.launch {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val message = Json.parseToJsonElement(line).jsonObject
                        val id = message["id"]?.jsonPrimitive?.longOrNull
                        if (id != null) {
                            message["result"]?.jsonObject?.let { pending.remove(id)?.complete(it) }
                        } else if (message["event"]?.jsonPrimitive?.contentOrNull == "state") {
                            message["state"]?.jsonObject?.let { mutableState.value = it }
                        }
                    }
                }
            } finally {
                synchronized(connection) {
                    if (input === writer) {
                        runCatching { writer.close() }
                        input = null
                        val cause = IllegalStateException("更新服务已退出")
                        pending.values.forEach { it.completeExceptionally(cause) }
                        pending.clear()
                        if (!closed) {
                            mutableState.value = buildJsonObject {
                                put("phase", "error")
                                put("error", cause.message)
                            }
                        }
                    }
                }
            }
        }
        return writer
    }

    override fun close() {
        synchronized(connection) {
            closed = true
            // EOF detaches the UI; an installer already running can still finish and persist its result.
            runCatching { input?.close() }
            input = null
            pending.values.forEach { it.cancel() }
            pending.clear()
        }
    }
}
