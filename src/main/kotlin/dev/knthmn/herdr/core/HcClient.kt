package dev.knthmn.herdr.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import dev.knthmn.herdr.settings.HerdrSettings
import java.io.IOException
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface CoreState {
    data object NotFound : CoreState

    data class Unsupported(val version: HcVersion) : CoreState

    data object Connecting : CoreState

    data class Disconnected(val reason: String) : CoreState

    data class Connected(val hc: Path, val table: TerminalTable) : CoreState
}

/** Keeps one `hc connect` process subscribed to core events and restarts it when it ends. */
@Service(Service.Level.APP)
class HcClient(private val cs: CoroutineScope) {
    private val mutableState = MutableStateFlow<CoreState>(CoreState.Connecting)
    val state: StateFlow<CoreState> = mutableState.asStateFlow()

    private var loop: Job? = null

    init {
        restart()
    }

    @Synchronized
    fun restart() {
        loop?.cancel()
        mutableState.value = CoreState.Connecting
        loop = cs.launch(Dispatchers.IO) { runLoop() }
    }

    /** Sets the state only from the current loop, so a cancelled loop cannot overwrite it. */
    @Synchronized
    private fun publish(job: Job, state: CoreState) {
        if (loop === job) mutableState.value = state
    }

    private suspend fun runLoop() {
        val job = currentCoroutineContext()[Job]!!
        var backoff = MIN_BACKOFF
        while (currentCoroutineContext().isActive) {
            val hc =
                when (val health = HcLocator.health(HerdrSettings.getInstance().hcPath)) {
                    HcHealth.Missing -> null.also { publish(job, CoreState.NotFound) }
                    is HcHealth.Unsupported ->
                        null.also { publish(job, CoreState.Unsupported(health.version)) }
                    is HcHealth.Usable -> health.hc
                }
            if (hc == null) {
                delay(NOT_FOUND_RETRY)
                continue
            }
            val startedAt = System.nanoTime()
            val reason =
                try {
                    connect(hc, job)
                } catch (e: IOException) {
                    e.message ?: e.javaClass.simpleName
                }
            publish(job, CoreState.Disconnected(reason))
            if (System.nanoTime() - startedAt > STABLE_CONNECTION.inWholeNanoseconds) {
                backoff = MIN_BACKOFF
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
        }
    }

    private suspend fun connect(hc: Path, job: Job): String = coroutineScope {
        val process =
            ProcessBuilder(hc.toString(), "connect")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        val watcher =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    process.destroy()
                }
            }
        try {
            process.outputWriter().apply {
                write(HELLO)
                write(SUBSCRIBE)
                flush()
            }
            var table: TerminalTable? = null
            process.inputReader().useLines { lines ->
                for (line in lines) {
                    table = handleLine(line, table)
                    table?.let { publish(job, CoreState.Connected(hc, it)) }
                }
            }
            "hc connect exited (${process.waitFor()})"
        } finally {
            watcher.cancel()
            process.destroy()
        }
    }

    private fun handleLine(line: String, table: TerminalTable?): TerminalTable? =
        try {
            message(HcJson.parseToJsonElement(line).jsonObject, table)
        } catch (e: SerializationException) {
            thisLogger().warn("Unreadable line from hc connect", e)
            table
        } catch (e: IllegalArgumentException) {
            thisLogger().warn("Unexpected line from hc connect", e)
            table
        }

    private fun message(message: JsonObject, table: TerminalTable?): TerminalTable? {
        val method = message["method"]?.jsonPrimitive?.contentOrNull
        val params = message["params"] as? JsonObject
        return when {
            message["id"]?.jsonPrimitive?.intOrNull == SUBSCRIBE_ID -> {
                (message["result"] as? JsonObject)?.let(TerminalTable::fromSnapshot)
                    ?: throw IOException(
                        "Subscribe failed: " +
                            ((message["error"] as? JsonObject)
                                ?.get("message")
                                ?.jsonPrimitive
                                ?.contentOrNull ?: "no result")
                    )
            }
            method == "event" && params != null && table != null ->
                table.apply(params, System.currentTimeMillis())
            method == "missed" && params != null -> {
                val snapshot = params["snapshot"] as? JsonObject ?: params
                TerminalTable.fromSnapshot(snapshot) ?: table
            }
            else -> table
        }
    }

    companion object {
        private const val SUBSCRIBE_ID = 2
        private const val HELLO =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"hello\",\"params\":{\"generation\":1}}\n"
        private const val SUBSCRIBE =
            "{\"jsonrpc\":\"2.0\",\"id\":$SUBSCRIBE_ID,\"method\":\"events.subscribe\",\"params\":{}}\n"
        private val MIN_BACKOFF = 1.seconds
        private val MAX_BACKOFF = 30.seconds
        private val NOT_FOUND_RETRY = 5.seconds
        private val STABLE_CONNECTION = 10.seconds

        fun getInstance(): HcClient = service()
    }
}
