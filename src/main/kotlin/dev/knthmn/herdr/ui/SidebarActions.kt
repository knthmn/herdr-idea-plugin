package dev.knthmn.herdr.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import dev.knthmn.herdr.model.Agent
import dev.knthmn.herdr.model.Launchers
import dev.knthmn.herdr.model.Row
import dev.knthmn.herdr.model.WorktreeRoots
import dev.knthmn.herdr.terminal.SessionActions
import javax.swing.Icon

/** Toolbar and context menu actions of the sidebar. [selected] is the selected row, if any. */
class SidebarActions(private val project: Project, private val selected: () -> Row?) {
    private val sessions = SessionActions.getInstance(project)

    val newSession: ActionGroup = NewSessionGroup()

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

    private val refresh =
        object : DumbAwareAction("Refresh Worktrees", null, AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) =
                WorktreeRoots.getInstance(project).refresh()
        }

    fun toolbar(): ActionGroup =
        DefaultActionGroup(newSession, Separator.create(), resume, finish, delete, refresh)

    fun popup(): ActionGroup = DefaultActionGroup(resume, finish, delete)

    private fun rowAction(
        text: String,
        description: String,
        icon: Icon,
        enabled: (Row) -> Boolean,
        perform: (Row) -> Unit,
    ): AnAction =
        object : DumbAwareAction(text, description, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                val row = selected()
                e.presentation.isEnabled = row != null && !sessions.isBusy(row) && enabled(row)
            }

            override fun actionPerformed(e: AnActionEvent) {
                selected()?.let(perform)
            }
        }

    private inner class NewSessionGroup : ActionGroup("New Session", true) {
        init {
            templatePresentation.icon = AllIcons.General.Add
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
