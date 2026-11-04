package dev.knthmn.herdr.terminal

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import dev.knthmn.herdr.model.Row
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.plugins.terminal.startup.TerminalProcessType

/**
 * Opens one editor tab per hc terminal, running `hc attach` through [AttachHelper]. Like the IDE's
 * "Move to Editor" action, it creates a terminal tab, detaches it from the tool window, and shows
 * it in an editor.
 */
@Service(Service.Level.PROJECT)
class TerminalOpener(private val project: Project) {
    private val files = HashMap<String, AgentTerminalFile>()
    private val previews = HashSet<String>()

    /**
     * Opens [row] in its tab. With [preview], the tab is the IDE's preview tab, which the next
     * preview replaces.
     */
    fun open(hc: Path, row: Row, preview: Boolean = false, focus: Boolean = true) {
        val editors = FileEditorManager.getInstance(project)
        files.values.retainAll { editors.isFileOpen(it) }
        previews.retainAll(files.keys)
        val file = files.getOrPut(row.address) { createFile(hc, row) }
        val wasOpen = editors.isFileOpen(file)
        editors.openFileEditor(OpenFileDescriptor(project, file).setUsePreviewTab(preview), focus)
        if (!preview) previews -= row.address else if (!wasOpen) previews += row.address
    }

    /** Makes the preview tab of [file] a normal tab, like an edit does for a file. */
    fun keep(file: AgentTerminalFile) {
        if (!previews.remove(file.address)) return
        FileEditorManager.getInstance(project)
            .openFileEditor(OpenFileDescriptor(project, file), false)
    }

    private fun createFile(hc: Path, row: Row): AgentTerminalFile {
        val manager = TerminalToolWindowTabsManager.getInstance(project)
        val tab =
            manager
                .createTabBuilder()
                .shellCommand(AttachHelper.attachCommand(hc, row.address))
                .processType(TerminalProcessType.NON_SHELL)
                .workingDirectory(workingDirectory(row.cwd))
                .tabName(tabName(row))
                .closeOnProcessTermination(true)
                .requestFocus(false)
                .createTab()
        manager.detachTab(tab)
        return AgentTerminalFile(row.address, row.program, tab, tabName(row))
    }

    private fun tabName(row: Row): String = row.label.take(AgentTabTitleProvider.MAX_LENGTH)

    private fun workingDirectory(cwd: String): String? =
        cwd.takeIf { it.isNotEmpty() && Files.isDirectory(Path.of(it)) } ?: project.basePath

    companion object {
        fun getInstance(project: Project): TerminalOpener = project.service()
    }
}
