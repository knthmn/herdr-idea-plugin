package dev.knthmn.herdr.terminal

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import java.awt.AWTEvent
import java.awt.event.KeyEvent
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AgentTerminalEditor(private val project: Project, private val file: AgentTerminalFile) :
    UserDataHolderBase(), FileEditor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

    init {
        if (file.tab.closeOnProcessTermination) {
            scope.launch {
                file.tab.view.sessionState.first { it is TerminalViewSessionState.Terminated }
                FileEditorManager.getInstance(project).closeFile(file)
            }
        }
        IdeEventQueue.getInstance()
            .addDispatcher(
                object : IdeEventQueue.NonLockedEventDispatcher {
                    override fun dispatch(e: AWTEvent): Boolean {
                        if (
                            e is KeyEvent &&
                                e.id == KeyEvent.KEY_TYPED &&
                                SwingUtilities.isDescendingFrom(e.component, component)
                        ) {
                            ApplicationManager.getApplication()
                                .invokeLater(
                                    { TerminalOpener.getInstance(project).keep(file) },
                                    project.disposed,
                                )
                        }
                        return false
                    }
                },
                this,
            )
    }

    override fun getComponent(): JComponent = file.tab.content.component

    override fun getPreferredFocusedComponent(): JComponent? =
        file.tab.content.preferredFocusableComponent

    override fun getName(): String = file.name

    override fun getFile(): VirtualFile = file

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun setState(state: FileEditorState) = Unit

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() {
        scope.cancel()
        if (file.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) != true) {
            Disposer.dispose(file.tab.content)
        }
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
