package dev.knthmn.herdr.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import dev.knthmn.herdr.settings.HerdrSettings
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HcException(val kind: String, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** One short-lived `hc --json <command>` process per call. */
@Service(Service.Level.APP)
class HcCommands {
    /** Starts [argv] in a new background terminal and returns its address. */
    suspend fun run(
        argv: List<String>,
        cwd: String,
        name: String? = null,
        keep: Boolean = false,
        labels: Map<String, String> = emptyMap(),
        parent: String? = null,
        outliveParent: Boolean = false,
    ): String {
        val options = buildList {
            add("--bg")
            if (parent == null) add("--no-parent") else addAll(listOf("--parent", parent))
            if (parent != null && outliveParent) add("--outlive-parent")
            addAll(listOf("--cwd", cwd))
            name?.let { addAll(listOf("--name", it)) }
            if (keep) add("--keep")
            labels.forEach { (k, v) -> addAll(listOf("--label", "$k=$v")) }
        }
        val result = call(listOf("run") + options + "--" + argv)
        return result["address"]?.jsonPrimitive?.contentOrNull
            ?: throw HcException("unexpected", "hc run returned no address")
    }

    suspend fun send(terminal: String, text: String) {
        call(listOf("send", "--", terminal, text))
    }

    suspend fun keys(terminal: String, vararg keys: String) {
        call(listOf("keys", terminal) + keys)
    }

    suspend fun kill(terminal: String) {
        call(listOf("kill", terminal))
    }

    suspend fun rm(terminal: String) {
        call(listOf("rm", terminal))
    }

    private suspend fun call(args: List<String>): JsonObject =
        withContext(Dispatchers.IO) {
            val hc =
                HcLocator.locate(HerdrSettings.getInstance().hcPath)
                    ?: throw HcException("not_found", "hc not found")
            val process = start(listOf(hc.toString(), "--json") + args)
            process.outputStream.close()
            val output = async { process.inputReader().readText() }
            if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw HcException("timeout", "hc ${args.first()} did not answer")
            }
            val json =
                try {
                    output
                        .await()
                        .lines()
                        .lastOrNull { it.isNotBlank() }
                        ?.let {
                            HcJson.parseToJsonElement(it).jsonObject
                        }
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            val error = json?.get("error") as? JsonObject
            when {
                error != null ->
                    throw HcException(
                        error["kind"]?.jsonPrimitive?.contentOrNull ?: "error",
                        error["message"]?.jsonPrimitive?.contentOrNull ?: "hc failed",
                    )
                process.exitValue() != 0 ->
                    throw HcException("exit", "hc ${args.first()} exited ${process.exitValue()}")
                else -> json ?: JsonObject(emptyMap())
            }
        }

    private fun start(command: List<String>): Process =
        try {
            ProcessBuilder(command)
                .apply {
                    environment()["TERM_PROGRAM"] = TERM_PROGRAM
                    environment().remove("TERM_PROGRAM_VERSION")
                }
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (e: IOException) {
            throw HcException("spawn", e.message ?: "hc did not start", e)
        }

    companion object {
        private const val TIMEOUT_S = 30L
        private const val TERM_PROGRAM = "JetBrains-JediTerm"

        fun getInstance(): HcCommands = service()
    }
}
