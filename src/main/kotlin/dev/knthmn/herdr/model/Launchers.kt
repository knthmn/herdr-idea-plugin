package dev.knthmn.herdr.model

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.EnvironmentUtil
import dev.knthmn.herdr.core.HcJson
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class Agent(val program: Program, val run: List<String>, val installed: Boolean)

/** How new terminals start: the Herdr app's menu agents and its login-shell wrapper. */
object Launchers {
    private const val CACHE_MS = 60_000L

    @Volatile private var cached: Pair<Long, List<Agent>>? = null

    /** Menu agents, with the person's `~/.config/herdr-app/programs.json` applied. */
    fun knownAgents(): List<Agent> {
        val now = System.currentTimeMillis()
        cached?.let { (at, agents) -> if (now - at < CACHE_MS) return agents }
        val path = EnvironmentUtil.getValue("PATH").orEmpty()
        val agents =
            Programs.launchable(userOverrides()).map { (program, run) ->
                Agent(program, run, isInstalled(run.first(), path))
            }
        cached = now to agents
        return agents
    }

    /** The menu agent with program [key], or null for a login shell. */
    fun agent(key: String): Agent? =
        key.takeIf { it.isNotEmpty() }?.let { k -> knownAgents().find { it.program.key == k } }

    fun shell(): String =
        EnvironmentUtil.getValue("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"

    /** A login shell, like a new Herdr tab. */
    fun terminal(shell: String = shell()): List<String> = listOf(shell, "-l")

    /** [argv] in an interactive login shell that stays open after it exits, like the app. */
    fun wrap(argv: List<String>, shell: String = shell()): List<String> =
        if (isFish(shell))
            listOf(shell, "-lic", "command \$argv[2..]; exec \$argv[1] -li", shell) + argv
        else listOf(shell, "-lic", "\"\$@\"; exec \"\$0\" -li", shell) + argv

    /** A command [line] in an interactive login shell that stays open after it exits. */
    fun wrapLine(line: String, shell: String = shell()): List<String> =
        if (isFish(shell)) listOf(shell, "-lic", "eval \$argv[2]; exec \$argv[1] -li", shell, line)
        else listOf(shell, "-lic", "eval \"\$1\"; exec \"\$0\" -li", shell, line)

    private fun isFish(shell: String) = shell.substringAfterLast('/') == "fish"

    fun isInstalled(command: String, path: String): Boolean =
        if ('/' in command) Files.isExecutable(Path.of(command))
        else
            path
                .split(File.pathSeparator)
                .filter { it.isNotEmpty() }
                .any {
                    val file = Path.of(it, command)
                    Files.isRegularFile(file) && Files.isExecutable(file)
                }

    private fun userOverrides(): Map<String, Pair<String?, List<String>?>> {
        val file = Path.of(System.getProperty("user.home"), ".config", "herdr-app", "programs.json")
        if (!Files.isRegularFile(file)) return emptyMap()
        return try {
            parseOverrides(Files.readString(file))
        } catch (e: IOException) {
            thisLogger().info("Cannot read $file", e)
            emptyMap()
        }
    }

    /** `{programs: {key: {name?, run?}}}` from the app's `programs.json`. */
    fun parseOverrides(text: String): Map<String, Pair<String?, List<String>?>> =
        try {
            val programs =
                HcJson.parseToJsonElement(text).jsonObject["programs"] as? JsonObject
                    ?: return emptyMap()
            programs
                .mapNotNull { (key, value) ->
                    val entry = value as? JsonObject ?: return@mapNotNull null
                    val name = entry["name"]?.jsonPrimitive?.contentOrNull
                    val run =
                        (entry["run"] as? JsonArray)?.mapNotNull {
                            it.jsonPrimitive.contentOrNull
                        }
                    key to (name to run)
                }
                .toMap()
        } catch (_: SerializationException) {
            emptyMap()
        } catch (_: IllegalArgumentException) {
            emptyMap()
        }
}
