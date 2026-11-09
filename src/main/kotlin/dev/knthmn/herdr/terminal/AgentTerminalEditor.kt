package dev.knthmn.herdr.terminal

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import com.intellij.ui.components.JBPanelWithEmptyText
import dev.knthmn.herdr.core.CoreState
import dev.knthmn.herdr.core.HcClient
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

class AgentTerminalEditor(private val project: Project, private val file: AgentTerminalFile) :
    UserDataHolderBase(), FileEditor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private val opener = TerminalOpener.getInstance(project)
    private val host = TerminalHost()

    init {
        if (!resolve()) {
            scope.launch {
                combine(HcClient.getInstance().state, opener.terminalReady) { core, _ -> core }
                    .takeWhile { core ->
                        host.showStatus(core)
                        core !is CoreState.Connected || !resolve()
                    }
                    .collect {}
            }
        }
        IdeEventQueue.getInstance()
            .addDispatcher(
                object : IdeEventQueue.NonLockedEventDispatcher {
                    override fun dispatch(e: AWTEvent): Boolean {
                        if (
                            e is KeyEvent &&
                                e.id == KeyEvent.KEY_TYPED &&
                                SwingUtilities.isDescendingFrom(e.component, host)
                        ) {
                            ApplicationManager.getApplication()
                                .invokeLater({ opener.keep(file) }, project.disposed)
                        }
                        return false
                    }
                },
                this,
            )
    }

    /**
     * Attaches the terminal tab, or closes the editor when the terminal is dead. False when the
     * core is not connected yet.
     */
    private fun resolve(): Boolean {
        val ready = opener.tabOrNull(file)
        when {
            ready != null -> {
                host.attach(ready)
                if (ready.closeOnProcessTermination) {
                    scope.launch {
                        ready.view.sessionState.first { it is TerminalViewSessionState.Terminated }
                        opener.closeLater(file)
                    }
                }
            }
            opener.isDead(file) -> opener.closeLater(file)
            else -> return false
        }
        return true
    }

    override fun getComponent(): JComponent = host

    override fun getPreferredFocusedComponent(): JComponent? =
        host.tab?.content?.preferredFocusableComponent ?: host

    override fun getName(): String = file.name

    override fun getFile(): VirtualFile = file

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun setState(state: FileEditorState) = Unit

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() {
        scope.cancel()
        host.detach()
        opener.release(file)
    }
}

/** Shows the core status until the terminal tab is attached, then the tab. */
private class TerminalHost : JBPanelWithEmptyText(BorderLayout()) {
    var tab: TerminalToolWindowTab? = null
        private set

    fun showStatus(core: CoreState) {
        val status = emptyText
        status.clear()
        when (core) {
            CoreState.Connecting,
            is CoreState.Connected -> status.appendLine("Connecting to herdr-core")
            CoreState.NotFound -> status.appendLine("herdr-core not found")
            is CoreState.Unsupported -> status.appendLine("herdr-core ${core.version} is too old")
            is CoreState.Disconnected -> {
                status.appendLine("Not connected to herdr-core")
                status.appendLine(core.reason)
            }
        }
    }

    fun attach(ready: TerminalToolWindowTab) {
        tab = ready
        val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val hadFocus = focused != null && SwingUtilities.isDescendingFrom(focused, this)
        emptyText.clear()
        add(ready.content.component, BorderLayout.CENTER)
        revalidate()
        repaint()
        if (hadFocus) ready.content.preferredFocusableComponent?.requestFocusInWindow()
    }

    fun detach() {
        tab?.content?.component?.let(::remove)
    }
}

class AgentTerminalEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is AgentTerminalFile

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        AgentTerminalEditor(project, file as AgentTerminalFile)

    override fun getEditorTypeId(): String = "dev.knthmn.herdr.terminal"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
