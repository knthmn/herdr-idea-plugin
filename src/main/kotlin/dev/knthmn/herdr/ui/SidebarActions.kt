package dev.knthmn.herdr.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import dev.knthmn.herdr.model.Agent
import dev.knthmn.herdr.model.AgentState
import dev.knthmn.herdr.model.Launchers
import dev.knthmn.herdr.model.ReadState
import dev.knthmn.herdr.model.Row
import dev.knthmn.herdr.model.WorktreeRoots
import dev.knthmn.herdr.settings.HerdrSettings
import dev.knthmn.herdr.terminal.SessionActions
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon

/** Toolbar and context menu actions of the sidebar. [selected] is the selected row, if any. */
class SidebarActions(private val project: Project, private val selected: () -> Row?) {
    private val sessions = SessionActions.getInstance(project)

    private val newDefault = NewDefaultSession()
    private val newSession: ActionGroup = NewSessionGroup()

    private val resume =
        rowAction(
            "Resume",
            "Run the session's resume command",
            AllIcons.Actions.Resume,
            { it.canResume },
            sessions::resume,
        )
    private val finish =
        rowAction(
            "Finish",
            "End the terminal's program",
            AllIcons.Actions.Suspend,
            { !it.finished },
            sessions::finish,
        )
    private val delete =
        rowAction(
            "Delete",
            "Remove the terminal from herdr-core",
            AllIcons.Actions.GC,
            { true },
            sessions::delete,
        )

    private val markRead =
        rowAction(
            "Mark as Read",
            null,
            null,
            { it.state == AgentState.UNREAD },
            { ReadState.getInstance().markRead(it.address) },
            hidden = true,
        )
    private val markUnread =
        rowAction(
            "Mark as Unread",
            null,
            null,
            { it.canMarkUnread },
            { ReadState.getInstance().markUnread(it.address) },
            hidden = true,
        )
    private val clearBlocked =
        rowAction(
            "Clear Blocked",
            "Remove the blocked label, for a question that is no longer open",
            null,
            { it.hasBlockedLabel },
            sessions::clearBlocked,
        )

    private val copy =
        DefaultActionGroup("Copy", true).apply {
            templatePresentation.icon = AllIcons.Actions.Copy
            add(copyAction("Address") { it.address })
            add(copyAction("Attach Command") { "hc attach ${it.address}" })
            add(copyAction("Working Directory") { it.cwd.ifEmpty { null } })
            add(copyAction("Resume Command") { it.resume })
            add(copyAction("Session File") { it.sessionFile })
        }

    private val selectInProject =
        folderAction("Select in Project View", AllIcons.General.Locate) { dir ->
            val file = LocalFileSystem.getInstance().findFileByNioFile(dir) ?: return@folderAction
            ToolWindowManager.getInstance(project)
                .getToolWindow(ToolWindowId.PROJECT_VIEW)
                ?.activate {
                    ProjectView.getInstance(project).select(null, file, true)
                }
        }
    private val reveal =
        folderAction(RevealFileAction.getActionName(), null, RevealFileAction::openDirectory)
    private val openTerminal =
        folderAction("Open in Terminal", AllIcons.Nodes.Console) { dir ->
            TerminalToolWindowTabsManager.getInstance(project)
                .createTabBuilder()
                .workingDirectory(dir.toString())
                .requestFocus(true)
                .createTab()
        }

    private val refresh =
        object : DumbAwareAction("Refresh Worktrees", null, AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) =
                WorktreeRoots.getInstance(project).refresh()
        }

    fun toolbar(): ActionGroup =
        DefaultActionGroup(
            newDefault,
            newSession,
            Separator.create(),
            resume,
            finish,
            delete,
            refresh,
        )

    fun popup(): ActionGroup =
        DefaultActionGroup(
            resume,
            finish,
            delete,
            Separator.create(),
            markRead,
            markUnread,
            clearBlocked,
            Separator.create(),
            copy,
            Separator.create(),
            selectInProject,
            reveal,
            openTerminal,
        )

    /** [hidden]: hide the action instead of disabling it when [enabled] is false. */
    private fun rowAction(
        text: String,
        description: String?,
        icon: Icon?,
        enabled: (Row) -> Boolean,
        perform: (Row) -> Unit,
        hidden: Boolean = false,
    ): AnAction =
        object : DumbAwareAction(text, description, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                val row = selected()
                val applies = row != null && enabled(row)
                e.presentation.isEnabled = applies && !sessions.isBusy(row)
                if (hidden) e.presentation.isVisible = applies
            }

            override fun actionPerformed(e: AnActionEvent) {
                selected()?.let(perform)
            }
        }

    private fun copyAction(text: String, value: (Row) -> String?): AnAction =
        object : DumbAwareAction(text) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                val copied = selected()?.let(value)
                e.presentation.isEnabledAndVisible = copied != null
                e.presentation.description = copied
            }

            override fun actionPerformed(e: AnActionEvent) {
                selected()?.let(value)?.let(CopyPasteManager::copyTextToClipboard)
            }
        }

    private fun folderAction(text: String, icon: Icon?, perform: (Path) -> Unit): AnAction =
        object : DumbAwareAction(text, null, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = folder() != null
            }

            override fun actionPerformed(e: AnActionEvent) {
                folder()?.let(perform)
            }

            private fun folder(): Path? =
                selected()
                    ?.cwd
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { Path.of(it) }
                    ?.takeIf { Files.isDirectory(it) }
        }

    /** Starts the default agent of the settings. The button shows the agent's name. */
    private inner class NewDefaultSession : DumbAwareAction() {
        init {
            templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }

        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            val agent = Launchers.agent(HerdrSettings.getInstance().defaultAgent)
            val name = agent?.program?.name ?: "Terminal"
            e.presentation.text = name
            e.presentation.description =
                "New $name session: ${agent?.run?.joinToString(" ") ?: "a login shell"}"
            e.presentation.icon =
                agent?.let { ProgramIcons.of(it.program) } ?: AllIcons.Nodes.Console
            e.presentation.isEnabled = agent?.installed ?: true
        }

        override fun actionPerformed(e: AnActionEvent) = sessions.newDefaultSession()
    }

    private inner class NewSessionGroup : ActionGroup("New Session", true) {
        init {
            templatePresentation.icon = AllIcons.General.ChevronDown
            templatePresentation.putClientProperty(ActionUtil.HIDE_DROPDOWN_ICON, true)
        }

        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            val terminal =
                object : DumbAwareAction("Terminal", "A login shell", AllIcons.Nodes.Console) {
                    override fun actionPerformed(e: AnActionEvent) = sessions.newSession(null)
                }
            val agents = Launchers.knownAgents().sortedByDescending { it.installed }.map(::agent)
            return (listOf(terminal, Separator.create()) + agents).toTypedArray()
        }

        private fun agent(agent: Agent): AnAction =
            object :
                DumbAwareAction(
                    if (agent.installed) agent.program.name
                    else "${agent.program.name} (not installed)",
                    agent.run.joinToString(" "),
                    ProgramIcons.of(agent.program),
                ) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = agent.installed
                }

                override fun actionPerformed(e: AnActionEvent) = sessions.newSession(agent)
            }
    }
}
