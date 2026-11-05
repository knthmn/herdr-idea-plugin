package dev.knthmn.herdr.model

import dev.knthmn.herdr.core.HcLocator
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

enum class SortOrder {
    RECENT,
    STATUS,
}

data class ViewOptions(val groupByFolder: Boolean = false, val sort: SortOrder = SortOrder.RECENT)

/**
 * Top-level live rows whose cwd is [path]. A null [path] holds the rows without a cwd. [location]
 * is the folder of a worktree group, whose [label] is the branch.
 */
data class FolderGroup(
    val path: Path?,
    val label: String,
    val rows: List<Row>,
    val location: String? = null,
)

/** A folder whose terminals are in scope. [branch] is set for a checked-out git worktree. */
data class Root(val path: Path, val branch: String? = null)

/** The terminal labels that the plugin reads. An empty key turns its feature off. */
data class LabelKeys(val resume: String = "", val title: String = "", val blocked: String = "") {
    fun resumeOf(t: Terminal): String? = value(t, resume)

    fun titleOf(t: Terminal): String? = value(t, title)

    fun blockedOf(t: Terminal): String? = value(t, blocked)

    private fun value(t: Terminal, key: String): String? =
        key.takeIf { it.isNotBlank() }?.let { t.labels[it] }?.takeIf { it.isNotBlank() }

    companion object {
        /** The keys that the example agent integrations in docs/ write. */
        val DEFAULT = LabelKeys("resume", "title", "blocked")
    }
}

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
    val keys: LabelKeys = LabelKeys(),
) {
    val finished: Boolean
        get() = terminal.finished

    /** The agent is gone and a shell holds the terminal, or the terminal finished. */
    val canResume: Boolean
        get() =
            resume != null &&
                (finished || (program.key == SHELL && terminal.status?.state.isNullOrEmpty()))

    val hasBlockedLabel: Boolean
        get() = keys.blockedOf(terminal) != null

    /** A `done` agent that can show as unread again. */
    val canMarkUnread: Boolean
        get() = state == AgentState.IDLE && terminal.status?.state == "done"

    /** The pi session file in the [resume] command, `pi --session '<file>'`. */
    val sessionFile: String?
        get() = resume?.let(::sessionFile)

    companion object {
        const val SHELL = "shell"

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

/**
 * [rows] are the top-level live rows in display order. [groups] is empty unless the rows are
 * grouped by folder.
 */
data class SidebarModel(
    val rows: List<Row>,
    val finished: List<Row> = emptyList(),
    val groups: List<FolderGroup> = emptyList(),
) {
    companion object {
        val EMPTY = SidebarModel(emptyList())

        /**
         * Terminals whose cwd or foreground cwd is under a root are in scope, together with all
         * their descendants. The first root is the project. Live terminals nest under their live
         * parent, in the order of [view]. Finished terminals are a flat list, newest first. A
         * `done` terminal is unread until [readAt] is at or after its last status change.
         */
        fun build(
            terminals: Collection<Terminal>,
            roots: List<Root>,
            readAt: (String) -> Long? = { null },
            normalize: (String) -> Path = { Path.of(it).normalize() },
            keys: LabelKeys = LabelKeys(),
            view: ViewOptions = ViewOptions(),
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

            val rows = RowFactory(byParent, normalRoots, readAt, normalize, keys)

            val live = terminals.filter { it.address in shown && !it.finished }
            val liveAddresses = live.mapTo(HashSet()) { it.address }
            val order = RowOrder.of(view.sort)
            fun liveRow(t: Terminal): Row =
                rows.row(
                    t,
                    byParent[t.address]
                        .orEmpty()
                        .filter { it.address in liveAddresses }
                        .map(::liveRow)
                        .sortedWith(order),
                )

            val tops = live.filter { it.parent !in liveAddresses }.map(::liveRow).sortedWith(order)
            val finished =
                terminals
                    .filter { it.address in shown && it.finished }
                    .sortedByDescending { it.finishedMs ?: 0 }
                    .map { rows.row(it, emptyList()) }
            if (!view.groupByFolder) return SidebarModel(tops, finished)
            val groups = group(tops, normalRoots, normalize)
            return SidebarModel(groups.flatMap { it.rows }, finished, groups)
        }

        /**
         * Groups [tops] by their cwd. Groups sort by the first root that holds the folder, then by
         * path. Folders under no root and rows without a cwd come last.
         */
        private fun group(
            tops: List<Row>,
            roots: List<Root>,
            normalize: (String) -> Path,
        ): List<FolderGroup> {
            val folderOf = { row: Row -> row.cwd.takeIf { it.isNotEmpty() }?.let(normalize) }
            fun rootIndex(path: Path?): Int =
                path?.let { p -> roots.indexOfFirst { p.startsWith(it.path) } }?.takeIf { it >= 0 }
                    ?: roots.size
            val order =
                compareBy<Path?> { if (it == null) 1 else 0 }.thenBy { rootIndex(it) }.thenBy { it }
            return tops.groupBy(folderOf).toSortedMap(order).map { (path, rows) ->
                folderGroup(path, roots, rows.map { trimDetail(it, path, folderOf) })
            }
        }

        /**
         * A folder in a worktree other than the project shows the branch and the folder. Other
         * folders show their detail, or the project folder name.
         */
        private fun folderGroup(path: Path?, roots: List<Root>, rows: List<Row>): FolderGroup {
            if (path == null) return FolderGroup(null, "No folder", rows)
            val worktree =
                roots
                    .drop(1)
                    .filter { it.branch != null && path.startsWith(it.path) }
                    .maxByOrNull { it.path.nameCount }
            if (worktree?.branch != null) {
                val sub = worktree.path.relativize(path).toString()
                val label = if (sub.isEmpty()) worktree.branch else "${worktree.branch}/$sub"
                return FolderGroup(path, label, rows, location(path, roots.firstOrNull()?.path))
            }
            val label = detail(path, roots).ifEmpty { path.fileName?.toString() ?: path.toString() }
            return FolderGroup(path, label, rows)
        }

        private fun location(path: Path, project: Path?): String =
            if (project != null && path.startsWith(project)) project.relativize(path).toString()
            else HcLocator.abbreviateHome(path.toString())

        private fun trimDetail(row: Row, folder: Path?, folderOf: (Row) -> Path?): Row =
            row.copy(
                detail = if (folderOf(row) == folder) "" else row.detail,
                children = row.children.map { trimDetail(it, folder, folderOf) },
            )

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
            private val keys: LabelKeys,
        ) {
            fun row(t: Terminal, children: List<Row>): Row {
                val cwd = t.foreground?.cwd?.takeUnless { t.finished || it.isEmpty() } ?: t.cwd
                val program =
                    Programs.of(
                        t.status?.app.takeUnless { t.finished },
                        t.foreground?.argv?.takeIf { it.isNotEmpty() && !t.finished } ?: t.argv,
                    )
                val blocked = blockedLabel(t, program, keys)
                return Row(
                    address = t.address,
                    program = program,
                    label = label(t, program, keys),
                    detail = detail(normalize(cwd), roots),
                    state = blocked?.let { AgentState.BLOCKED } ?: state(t, readAt(t.address)),
                    message = if (t.finished) reason(t) else blocked ?: t.status?.msg,
                    cwd = cwd,
                    sinceMs = if (t.finished) t.finishedMs else t.statusMs ?: t.createdMs,
                    children = children,
                    terminal = t,
                    resume = keys.resumeOf(t),
                    liveDescendants = ending(t),
                    keys = keys,
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
        private fun blockedLabel(t: Terminal, program: Program, keys: LabelKeys): String? =
            keys.blockedOf(t)?.takeIf { !t.finished && program.key != Row.SHELL }

        private fun label(t: Terminal, program: Program, keys: LabelKeys): String =
            listOf(keys.titleOf(t), t.name, t.title).firstOrNull { !it.isNullOrBlank() }
                ?: program.name

        private fun detail(cwd: Path, roots: List<Root>): String {
            val project = roots.firstOrNull()?.path
            val branch =
                roots
                    .drop(1)
                    .filter { it.branch != null && cwd.startsWith(it.path) }
                    .maxByOrNull { it.path.nameCount }
            if (branch != null) return rootDetail(cwd, branch)
            if (project != null && cwd.startsWith(project)) {
                return if (cwd == project) "" else project.relativize(cwd).toString()
            }
            val worktree = roots.drop(1).firstOrNull { cwd.startsWith(it.path) }
            if (worktree != null) return rootDetail(cwd, worktree)
            return HcLocator.abbreviateHome(cwd.toString())
        }

        private fun rootDetail(cwd: Path, root: Root): String {
            val name = root.branch ?: root.path.fileName?.toString().orEmpty()
            val sub = root.path.relativize(cwd).toString()
            return if (sub.isEmpty()) name else "$name/$sub"
        }
    }
}
