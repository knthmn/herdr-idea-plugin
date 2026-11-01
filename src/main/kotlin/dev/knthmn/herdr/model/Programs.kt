package dev.knthmn.herdr.model

import dev.knthmn.herdr.core.HcJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How a terminal shows: its icon and name, from the Herdr app's program table. */
data class Program(
    val key: String,
    val name: String,
    val icon: String?,
    val tint: Boolean,
    val agent: Boolean,
)

@Serializable
private data class ProgramEntry(
    val name: String? = null,
    val icon: String? = null,
    val tint: Boolean? = null,
    @SerialName("match") val also: List<String> = emptyList(),
)

@Serializable
private data class ProgramTable(
    val shells: List<String> = emptyList(),
    val interpreters: List<String> = emptyList(),
    val programs: Map<String, ProgramEntry> = emptyMap(),
)

/** A port of the Herdr app's `programs.rs` resolution, without the Windows PowerShell case. */
object Programs {
    private val table: ProgramTable by lazy {
        val text =
            Programs::class.java.getResourceAsStream("/herdr/programs.json")?.use {
                it.readBytes().decodeToString()
            }
        text?.let { HcJson.decodeFromString(ProgramTable.serializer(), it) } ?: ProgramTable()
    }

    private val scriptSuffixes = listOf(".js", ".mjs", ".cjs", ".ts", ".py", ".rb")

    /** From the agent's own report ([app]), else the program it runs ([argv]). */
    fun of(app: String?, argv: List<String>): Program {
        val reported = app?.trim().orEmpty()
        if (reported.isNotEmpty()) {
            return find(reported)?.let { (key, e) -> shown(key, e, null, agent = true) }
                ?: shown("agent", entry("agent"), reported, agent = true)
        }
        return fromArgv(argv)
    }

    private fun fromArgv(argv: List<String>): Program {
        if (isShellWrapper(argv)) return fromArgv(argv.drop(SHELL_WRAPPER_WORDS))
        val first = argv.firstOrNull()?.let(::base) ?: return shown("shell", entry("shell"), null)
        val match = (if (first in table.interpreters) scriptTarget(argv) else null) ?: find(first)
        return when {
            match != null -> shown(match.first, match.second, null)
            first in table.shells -> shown("shell", entry("shell"), first)
            else -> shown("program", entry("program"), first)
        }
    }

    /** `node .../vite.js dev`, `npx vite`: the program an interpreter runs, when it's known. */
    private fun scriptTarget(argv: List<String>): Pair<String, ProgramEntry>? =
        argv
            .drop(1)
            .filterNot { it.startsWith("-") }
            .firstNotNullOfOrNull { arg ->
                val name = base(arg)
                val stem =
                    scriptSuffixes.firstNotNullOfOrNull { suffix ->
                        name.removeSuffix(suffix).takeIf { it != name }
                    } ?: name
                find(stem)?.takeIf { (key, _) -> key !in table.interpreters }
            }

    /**
     * The app starts commands in the person's shell: `zsh -ic '"$@"; exec "$0" -i' zsh <cmd>...`.
     */
    private fun isShellWrapper(argv: List<String>): Boolean =
        argv.size > SHELL_WRAPPER_WORDS &&
            base(argv[0]) in table.shells &&
            argv[1].startsWith("-") &&
            argv[1].endsWith("c") &&
            ("\"$@\"" in argv[2] || argv[2] == "command $argv[2..]; exec $argv[1] -i")

    private fun find(name: String): Pair<String, ProgramEntry>? {
        val lower = name.lowercase()
        return table.programs.entries
            .firstOrNull { (k, _) -> k == name || k.lowercase() == lower }
            ?.toPair()
            ?: table.programs.entries
                .firstOrNull { (_, e) -> e.also.any { it == name || it.lowercase() == lower } }
                ?.toPair()
    }

    private fun entry(key: String) = table.programs[key] ?: ProgramEntry()

    private fun shown(key: String, e: ProgramEntry, name: String?, agent: Boolean = false) =
        Program(
            key = key,
            name = name ?: e.name ?: key,
            icon = e.icon,
            tint = e.tint ?: true,
            agent = agent,
        )

    private fun base(s: String): String {
        val last = s.substringAfterLast('/').substringAfterLast('\\').trimStart('-')
        return if (last.endsWith(".exe", ignoreCase = true)) last.dropLast(EXE.length) else last
    }

    private const val SHELL_WRAPPER_WORDS = 4
    private const val EXE = ".exe"
}
