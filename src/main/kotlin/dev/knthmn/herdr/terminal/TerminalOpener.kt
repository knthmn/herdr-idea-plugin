package dev.knthmn.herdr.terminal

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
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
    private val files = HashMap<String, VirtualFile>()

    fun open(hc: Path, row: Row) {
        val editors = FileEditorManager.getInstance(project)
        files.values.retainAll { editors.isFileOpen(it) }
        val existing = files[row.address]
        if (existing != null) {
            editors.openFile(existing, true)
            return
        }
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
        val file = AgentTerminalFile(row.address, row.program, tab, tabName(row))
        files[row.address] = file
        editors.openFile(file, true)
    }

    private fun tabName(row: Row): String = row.label.take(AgentTabTitleProvider.MAX_LENGTH)

    private fun workingDirectory(cwd: String): String? =
        cwd.takeIf { it.isNotEmpty() && Files.isDirectory(Path.of(it)) } ?: project.basePath

    companion object {
        fun getInstance(project: Project): TerminalOpener = project.service()
    }
}
