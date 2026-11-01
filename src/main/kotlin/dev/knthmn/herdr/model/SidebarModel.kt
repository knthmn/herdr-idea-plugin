package dev.knthmn.herdr.model

import dev.knthmn.herdr.core.Terminal
import java.nio.file.Path

enum class AgentState(val text: String) {
    RUNNING("Running"),
    BLOCKED("Blocked"),
    ERROR("Error"),
    UNREAD("Unread"),
    IDLE("Idle"),
    NONE("No status"),
}

data class Row(
    val address: String,
    val agent: String?,
    val label: String,
    val detail: String,
    val state: AgentState,
    val message: String?,
    val cwd: String,
    val sinceMs: Long?,
    val children: List<Row>,
)

data class SidebarModel(val rows: List<Row>) {
    companion object {
        val EMPTY = SidebarModel(emptyList())

        /**
         * Terminals whose cwd or foreground cwd is under [root] are in scope, together with all
         * their descendants. A terminal nests under its parent when the parent is in the model. A
         * `done` terminal is unread until [readAt] is at or after its last status change.
         */
        fun build(
            terminals: Collection<Terminal>,
            root: Path,
            readAt: (String) -> Long? = { null },
            normalize: (String) -> Path = { Path.of(it).normalize() },
        ): SidebarModel {
            val normalRoot = normalize(root.toString())
            val byParent = terminals.filter { it.parent != null }.groupBy { it.parent }
            val inScope = terminals.filter { t ->
                listOfNotNull(t.foreground?.cwd, t.cwd).any {
                    it.isNotEmpty() && normalize(it).startsWith(normalRoot)
                }
            }
            val shown = mutableSetOf<String>()
            fun collect(t: Terminal) {
                if (shown.add(t.address)) byParent[t.address].orEmpty().forEach(::collect)
            }
            inScope.forEach(::collect)

            fun row(t: Terminal): Row {
                val cwd = t.foreground?.cwd ?: t.cwd
                return Row(
                    address = t.address,
                    agent = agent(t),
                    label = label(t),
                    detail = detail(normalize(cwd), normalRoot),
                    state = state(t, readAt(t.address)),
                    message = t.status?.msg,
                    cwd = cwd,
                    sinceMs = t.statusMs ?: t.createdMs,
                    children =
                        byParent[t.address]
                            .orEmpty()
                            .filter { it.address in shown }
                            .sortedWith(byRecent)
                            .map(::row),
                )
            }

            val tops = terminals.filter { it.address in shown && it.parent !in shown }
            return SidebarModel(tops.sortedWith(byRecent).map(::row))
        }

        fun state(t: Terminal, readAt: Long?): AgentState =
            when (t.status?.state) {
                "working" -> AgentState.RUNNING
                "blocked" -> AgentState.BLOCKED
                "error" -> AgentState.ERROR
                "done" ->
                    if (readAt != null && readAt >= (t.statusMs ?: 0)) AgentState.IDLE
                    else AgentState.UNREAD
                "idle" -> AgentState.IDLE
                else -> AgentState.NONE
            }

        private val byRecent = compareByDescending<Terminal> { it.statusMs ?: it.createdMs ?: 0 }

        private fun agent(t: Terminal): String? =
            t.status?.app?.takeIf { it.isNotBlank() }
                ?: t.foreground?.argv?.firstOrNull()?.substringAfterLast('/')?.takeIf {
                    it.isNotBlank()
                }

        private fun label(t: Terminal): String =
            t.name?.takeIf { it.isNotBlank() }
                ?: t.title?.takeIf { it.isNotBlank() }
                ?: t.foreground?.name?.takeIf { it.isNotBlank() }
                ?: t.address

        private fun detail(cwd: Path, root: Path): String =
            when {
                cwd == root -> ""
                cwd.startsWith(root) -> root.relativize(cwd).toString()
                else -> abbreviateHome(cwd.toString())
            }

        private fun abbreviateHome(path: String): String {
            val home = System.getProperty("user.home")
            return if (path == home || path.startsWith("$home/")) "~" + path.removePrefix(home)
            else path
        }
    }
}
