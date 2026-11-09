package dev.knthmn.herdr.terminal

import dev.knthmn.herdr.core.Terminal
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The path of an [AgentTerminalFile] in [HerdrFileSystem]:
 * `<address>/<createdMs>/<programKey>/<label>`, with each segment URL-encoded and `-` for an
 * unknown `createdMs`. The address and `createdMs` identify the terminal. The program key and the
 * label show a restored tab before the first hc snapshot.
 */
data class TerminalPath(
    val address: String,
    val createdMs: Long?,
    val programKey: String,
    val label: String,
) {
    val identity: Pair<String, Long?>
        get() = address to createdMs

    fun encode(): String =
        listOf(address, createdMs?.toString() ?: UNKNOWN, programKey, label).joinToString("/") {
            URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20")
        }

    /** [t] is this terminal: the same address, and the same creation time when both are known. */
    fun matches(t: Terminal): Boolean =
        t.address == address &&
            (createdMs == null || t.createdMs == null || t.createdMs == createdMs)

    fun isAlive(t: Terminal?): Boolean = t != null && !t.finished && matches(t)

    /**
     * The terminal is gone, finished, or another terminal in [terminals], the table of a connected
     * core. Null [terminals] means that the core is not connected, so nothing is known.
     */
    fun isDeadIn(terminals: List<Terminal>?): Boolean =
        terminals != null && !isAlive(terminals.find { it.address == address })

    companion object {
        private const val UNKNOWN = "-"
        private const val SEGMENTS = 4

        fun decode(path: String): TerminalPath? {
            val parts =
                path.trim('/').split('/').takeIf { it.size == SEGMENTS }?.let(::decodeAll)
                    ?: return null
            val (address, created, programKey) = parts
            val createdMs = created.takeIf { it != UNKNOWN }?.toLongOrNull()
            val valid = address.isNotEmpty() && (created == UNKNOWN || createdMs != null)
            return if (valid) TerminalPath(address, createdMs, programKey, parts.last()) else null
        }

        private fun decodeAll(parts: List<String>): List<String>? =
            try {
                parts.map { URLDecoder.decode(it, StandardCharsets.UTF_8) }
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
