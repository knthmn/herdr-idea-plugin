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
    FINISHED("Finished"),
}

/**
 * A folder whose terminals are in scope. [branch] is set for a git worktree outside the project.
 */
data class Root(val path: Path, val branch: String? = null)

data class Row(
    val address: String,
    val program: Program,
    val label: String,
    val detail: String,
    val state: AgentState,
    val message: String?,
    val cwd: String,
    val sinceMs: Long?,
    val children: List<Row>,
    val terminal: Terminal,
    val resume: String? = null,
    val liveDescendants: Int = 0,
) {
    val finished: Boolean
        get() = terminal.finished

    /** The agent is gone and a shell holds the terminal, or the terminal finished. */
    val canResume: Boolean
        get() =
            resume != null &&
                (finished || (program.key == SHELL && terminal.status?.state.isNullOrEmpty()))

    val hasBlockedLabel: Boolean
        get() = !terminal.labels[BLOCKED].isNullOrBlank()

    /** A `done` agent that can show as unread again. */
    val canMarkUnread: Boolean
        get() = state == AgentState.IDLE && terminal.status?.state == "done"

    /** The pi session file in the [resume] command, `pi --session '<file>'`. */
    val sessionFile: String?
        get() = resume?.let(::sessionFile)

    companion object {
        const val SHELL = "shell"
        const val BLOCKED = "blocked"

        fun sessionFile(command: String): String? {
            val rest = command.substringAfter("--session ", "").trimStart()
            val out = StringBuilder()
            var quoted = false
            var i = 0
            while (i < rest.length) {
                val c = rest[i]
                when {
                    c == '\'' -> quoted = !quoted
                    c == '\\' && !quoted && i + 1 < rest.length -> out.append(rest[++i])
                    c.isWhitespace() && !quoted -> break
                    else -> out.append(c)
                }
                i++
            }
            return out.toString().takeIf { it.isNotEmpty() }
        }
    }
}

data class SidebarModel(val rows: List<Row>, val finished: List<Row> = emptyList()) {
    companion object {
        val EMPTY = SidebarModel(emptyList())

        /**
         * Terminals whose cwd or foreground cwd is under a root are in scope, together with all
         * their descendants. The first root is the project. Live terminals nest under their live
         * parent. Finished terminals are a flat list, newest first. A `done` terminal is unread
         * until [readAt] is at or after its last status change.
         */
        fun build(
            terminals: Collection<Terminal>,
            roots: List<Root>,
            readAt: (String) -> Long? = { null },
            normalize: (String) -> Path = { Path.of(it).normalize() },
        ): SidebarModel {
            val normalRoots = roots.map { it.copy(path = normalize(it.path.toString())) }
            val byParent = terminals.filter { it.parent != null }.groupBy { it.parent }
            val inScope = terminals.filter { t ->
                listOfNotNull(t.foreground?.cwd, t.cwd).any { cwd ->
                    cwd.isNotEmpty() && normalRoots.any { normalize(cwd).startsWith(it.path) }
                }
            }
            val shown = mutableSetOf<String>()
            fun collect(t: Terminal) {
                if (shown.add(t.address)) byParent[t.address].orEmpty().forEach(::collect)
            }
            inScope.forEach(::collect)

            val rows = RowFactory(byParent, normalRoots, readAt, normalize)

            val live = terminals.filter { it.address in shown && !it.finished }
            val liveAddresses = live.mapTo(HashSet()) { it.address }
            fun liveRow(t: Terminal): Row =
                rows.row(
                    t,
                    byParent[t.address]
                        .orEmpty()
                        .filter { it.address in liveAddresses }
                        .sortedWith(byRecent)
                        .map(::liveRow),
                )

            val tops = live.filter { it.parent !in liveAddresses }
            val finished =
                terminals
                    .filter { it.address in shown && it.finished }
                    .sortedByDescending { it.finishedMs ?: 0 }
                    .map { rows.row(it, emptyList()) }
            return SidebarModel(tops.sortedWith(byRecent).map(::liveRow), finished)
        }

        fun state(t: Terminal, readAt: Long?): AgentState =
            if (t.finished) AgentState.FINISHED
            else
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

        /** Why a finished terminal ended: `killed`, `exited 1`, `signal 9`. */
        fun reason(t: Terminal): String =
            when {
                t.reason == "killed" -> "killed"
                t.exitCode != null -> "exited ${t.exitCode}"
                t.signal != null -> "signal ${t.signal}"
                else -> t.reason?.replace('_', ' ') ?: "finished"
            }

        private class RowFactory(
            private val byParent: Map<String?, List<Terminal>>,
            private val roots: List<Root>,
            private val readAt: (String) -> Long?,
            private val normalize: (String) -> Path,
        ) {
            fun row(t: Terminal, children: List<Row>): Row {
                val cwd = t.foreground?.cwd?.takeUnless { t.finished || it.isEmpty() } ?: t.cwd
                val program =
                    Programs.of(
                        t.status?.app.takeUnless { t.finished },
                        t.foreground?.argv?.takeIf { it.isNotEmpty() && !t.finished } ?: t.argv,
                    )
                val blocked = blockedLabel(t, program)
                return Row(
                    address = t.address,
                    program = program,
                    label = label(t, program),
                    detail = detail(normalize(cwd), roots),
                    state = blocked?.let { AgentState.BLOCKED } ?: state(t, readAt(t.address)),
                    message = if (t.finished) reason(t) else blocked ?: t.status?.msg,
                    cwd = cwd,
                    sinceMs = if (t.finished) t.finishedMs else t.statusMs ?: t.createdMs,
                    children = children,
                    terminal = t,
                    resume = t.labels[RESUME]?.takeIf { it.isNotBlank() },
                    liveDescendants = ending(t),
                )
            }

            private fun ending(t: Terminal): Int =
                byParent[t.address].orEmpty().sumOf {
                    if (!it.finished && it.dieWithParent) 1 + ending(it) else 0
                }
        }

        /**
         * The `blocked` label, a workaround for dialogs that pi does not report as blocked. Ignored
         * once the agent is gone, since it cannot clear a label then.
         */
        private fun blockedLabel(t: Terminal, program: Program): String? =
            t.labels[BLOCKED]?.takeIf {
                it.isNotBlank() && !t.finished && program.key != Row.SHELL
            }

        private const val BLOCKED = Row.BLOCKED
        private const val RESUME = "resume"
        private const val TITLE = "title"

        private val byRecent = compareByDescending<Terminal> { it.statusMs ?: it.createdMs ?: 0 }

        private fun label(t: Terminal, program: Program): String =
            listOf(t.labels[TITLE], t.name, t.title).firstOrNull { !it.isNullOrBlank() }
                ?: program.name

        private fun detail(cwd: Path, roots: List<Root>): String {
            val project = roots.firstOrNull()?.path
            if (project != null && cwd.startsWith(project)) {
                return if (cwd == project) "" else project.relativize(cwd).toString()
            }
            val worktree = roots.drop(1).firstOrNull { cwd.startsWith(it.path) }
            if (worktree != null) {
                val name = worktree.branch ?: worktree.path.fileName?.toString().orEmpty()
                val sub = worktree.path.relativize(cwd).toString()
                return if (sub.isEmpty()) name else "$name/$sub"
            }
            return abbreviateHome(cwd.toString())
        }

        private fun abbreviateHome(path: String): String {
            val home = System.getProperty("user.home")
            return if (path == home || path.startsWith("$home/")) "~" + path.removePrefix(home)
            else path
        }
    }
}
