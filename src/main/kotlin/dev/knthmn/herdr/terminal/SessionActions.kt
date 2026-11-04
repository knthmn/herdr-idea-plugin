package dev.knthmn.herdr.terminal

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import dev.knthmn.herdr.core.HcCommands
import dev.knthmn.herdr.core.HcException
import dev.knthmn.herdr.core.HcLocator
import dev.knthmn.herdr.core.HcStatusService
import dev.knthmn.herdr.model.Agent
import dev.knthmn.herdr.model.Launchers
import dev.knthmn.herdr.model.ProjectSidebar
import dev.knthmn.herdr.model.Row
import dev.knthmn.herdr.settings.HerdrSettings
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private fun describe(rows: List<Row>): String {
    val subject = rows.singleOrNull()?.label ?: "${rows.size} terminals"
    val children = rows.sumOf { it.liveDescendants }
    return if (children > 0) "$subject and $children child terminals" else subject
}

/** New, resume, finish, delete and label changes for hc terminals, from the sidebar. */
@Service(Service.Level.PROJECT)
class SessionActions(private val project: Project, private val cs: CoroutineScope) {
    private val hc = HcCommands.getInstance()
    private val busy: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun isBusy(row: Row): Boolean = row.address in busy

    /** Starts the default agent of the settings, or a login shell. */
    fun newDefaultSession() {
        cs.launch(Dispatchers.IO) {
            newSession(Launchers.agent(HerdrSettings.getInstance().defaultAgent))
        }
    }

    /** Starts [agent], or a login shell, in the project root and opens it. */
    fun newSession(agent: Agent?) {
        val cwd = project.basePath ?: return
        cs.launch {
            report("Cannot start ${agent?.program?.name ?: "a terminal"}") {
                if (agent != null && !agent.installed) {
                    throw HcException("not_installed", "${agent.run.first()} is not on PATH")
                }
                val argv = agent?.let { Launchers.wrap(it.run) } ?: Launchers.terminal()
                open(hc.run(argv, cwd, keep = true))
            }
        }
    }

    /**
     * Runs the row's `resume` command: typed into the shell that now holds the terminal, or in a
     * new terminal that replaces a finished one. Then opens the terminal.
     */
    fun resume(row: Row) {
        val command = row.resume ?: return
        val t = row.terminal
        guarded(row, "Cannot resume ${row.label}") {
            if (!t.finished) {
                open(row.address)
                hc.keys(row.address, "ctrl+u")
                hc.send(row.address, command)
                return@guarded
            }
            val parent = t.parent?.takeIf { ProjectSidebar.getInstance(project).isLive(it) }
            val labels = t.labels.filterKeys { !it.startsWith("herdr.") }
            val argv = Launchers.wrapLine(command)
            val cwd = t.foreground?.cwd?.takeIf { it.isNotEmpty() } ?: t.cwd
            val address =
                try {
                    hc.run(argv, cwd, t.name, t.keep, labels, parent, !t.dieWithParent)
                } catch (e: HcException) {
                    if (e.kind != "name_taken") throw e
                    hc.run(argv, cwd, null, t.keep, labels, parent, !t.dieWithParent)
                }
            hc.rm(row.address)
            open(address)
        }
    }

    fun finish(rows: List<Row>) {
        if (rows.isEmpty()) return
        val title = if (rows.size == 1) "Finish Terminal" else "Finish Terminals"
        if (!confirm("End ${describe(rows)}?", title, "Finish")) return
        rows.forEach { row -> guarded(row, "Cannot finish ${row.label}") { hc.kill(row.address) } }
    }

    fun delete(rows: List<Row>) {
        val live = rows.filter { !it.finished }
        if (live.isNotEmpty()) {
            val them = if (live.size == 1) "it" else "them"
            val title = if (rows.size == 1) "Delete Terminal" else "Delete Terminals"
            val message = "End ${describe(live)} and remove $them from herdr-core?"
            if (!confirm(message, title, "Delete")) return
        }
        rows.forEach { row ->
            guarded(row, "Cannot delete ${row.label}") {
                if (!row.finished) {
                    hc.kill(row.address)
                    val sidebar = ProjectSidebar.getInstance(project)
                    withTimeoutOrNull(FINISH_TIMEOUT) {
                        while (sidebar.terminal(row.address)?.finished == false) delay(POLL)
                    }
                }
                hc.rm(row.address)
            }
        }
    }

    fun clearBlocked(row: Row) {
        guarded(row, "Cannot clear the blocked label of ${row.label}") {
            hc.label(row.address, mapOf(row.keys.blocked to ""))
        }
    }

    private fun confirm(message: String, title: String, action: String): Boolean =
        !HerdrSettings.getInstance().confirmEnd ||
            Messages.showOkCancelDialog(
                project,
                message,
                title,
                action,
                Messages.getCancelButton(),
                Messages.getWarningIcon(),
            ) == Messages.OK

    private fun guarded(row: Row, failure: String, block: suspend () -> Unit) {
        if (!busy.add(row.address)) return
        cs.launch {
            try {
                report(failure, block)
            } finally {
                busy.remove(row.address)
            }
        }
    }

    private suspend fun report(failure: String, block: suspend () -> Unit) {
        if (!HcStatusService.getInstance().ensureUsable(project)) return
        try {
            block()
        } catch (e: HcException) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(HcStatusService.NOTIFICATIONS)
                .createNotification(
                    StringUtil.escapeXmlEntities(failure),
                    StringUtil.escapeXmlEntities(e.message.orEmpty()),
                    NotificationType.ERROR,
                )
                .notify(project)
        }
    }

    /** Opens [address] in an editor tab once the sidebar has its row. */
    private suspend fun open(address: String) {
        val sidebar = ProjectSidebar.getInstance(project)
        val row =
            withTimeoutOrNull(OPEN_TIMEOUT) {
                var row = sidebar.row(address)
                while (row == null) {
                    delay(POLL)
                    row = sidebar.row(address)
                }
                row
            } ?: return
        val path = HcLocator.locate(HerdrSettings.getInstance().hcPath) ?: return
        withContext(Dispatchers.EDT) { TerminalOpener.getInstance(project).open(path, row) }
    }

    companion object {
        private val OPEN_TIMEOUT = 5.seconds
        private val FINISH_TIMEOUT = 5.seconds
        private val POLL = 100.milliseconds

        fun getInstance(project: Project): SessionActions = project.service()
    }
}
