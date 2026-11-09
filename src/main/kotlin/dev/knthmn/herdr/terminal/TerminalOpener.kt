package dev.knthmn.herdr.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import dev.knthmn.herdr.core.CoreState
import dev.knthmn.herdr.core.HcClient
import dev.knthmn.herdr.model.ProjectSidebar
import dev.knthmn.herdr.model.Row
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.plugins.terminal.startup.TerminalProcessType

/**
 * Opens one editor tab per hc terminal, running `hc attach` through [AttachHelper]. Like the IDE's
 * "Move to Editor" action, it creates a terminal tab, detaches it from the tool window, and shows
 * it in an editor. The IDE restores the editor tabs on startup, and the terminal tab is created
 * when the editor needs it.
 */
@Service(Service.Level.PROJECT)
class TerminalOpener(private val project: Project, cs: CoroutineScope) {
    private val tabs = HashMap<AgentTerminalFile, TerminalToolWindowTab>()
    private val mutableTerminalReady = MutableStateFlow(false)

    /** The Terminal tool window is registered, so terminal tabs can be created. */
    val terminalReady: StateFlow<Boolean> = mutableTerminalReady.asStateFlow()

    init {
        project.messageBus
            .connect(cs)
            .subscribe(
                ToolWindowManagerListener.TOPIC,
                object : ToolWindowManagerListener {
                    override fun toolWindowsRegistered(
                        ids: List<String>,
                        toolWindowManager: ToolWindowManager,
                    ) {
                        if (TERMINAL_TOOL_WINDOW in ids) mutableTerminalReady.value = true
                    }
                },
            )
        mutableTerminalReady.value = hasTerminalToolWindow()
        cs.launch(Dispatchers.EDT) {
            HcClient.getInstance().state.collect { if (it is CoreState.Connected) closeDead() }
        }
    }

    /**
     * Opens [row] in its tab. With [preview], the tab is the IDE's preview tab, which the next
     * preview replaces.
     */
    fun open(hc: Path, row: Row, preview: Boolean = false, focus: Boolean = true) {
        val file =
            HerdrFileSystem.getInstance()
                .file(
                    TerminalPath(row.address, row.terminal.createdMs, row.program.key, tabName(row))
                )
        if (file !in tabs) tabs[file] = createTab(hc, row.address, row.cwd)
        FileEditorManager.getInstance(project)
            .openFileEditor(OpenFileDescriptor(project, file).setUsePreviewTab(preview), focus)
    }

    /** Makes the preview tab of [file] a normal tab, like an edit does for a file. */
    fun keep(file: AgentTerminalFile) {
        val editors = FileEditorManagerEx.getInstanceEx(project)
        if (editors.getComposite(file)?.isPreview != true) return
        editors.openFileEditor(OpenFileDescriptor(project, file), false)
    }

    /**
     * The terminal tab of [file]. It is created when the core is connected and has the terminal
     * live, else this is null.
     */
    fun tabOrNull(file: AgentTerminalFile): TerminalToolWindowTab? =
        tabs[file] ?: createIfAlive(file)?.also { tabs[file] = it }

    /** The core is connected, and [file]'s terminal is gone, finished, or another terminal. */
    fun isDead(file: AgentTerminalFile): Boolean =
        file.ref.isDeadIn(
            (HcClient.getInstance().state.value as? CoreState.Connected)?.table?.terminals
        )

    /** Ends the terminal tab of [file] when its editor closes, unless the IDE reopens it. */
    fun release(file: AgentTerminalFile) {
        if (file.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) == true) return
        tabs.remove(file)?.let { Disposer.dispose(it.content) }
    }

    /** Closes the editor of [file] later, never inside the editor's creation. */
    fun closeLater(file: AgentTerminalFile) {
        ApplicationManager.getApplication()
            .invokeLater(
                { FileEditorManager.getInstance(project).closeFile(file) },
                project.disposed,
            )
    }

    private fun createIfAlive(file: AgentTerminalFile): TerminalToolWindowTab? {
        val core = HcClient.getInstance().state.value as? CoreState.Connected
        if (core == null || !hasTerminalToolWindow()) return null
        val terminal =
            core.table.terminals
                .find { it.address == file.address }
                ?.takeIf { file.ref.isAlive(it) } ?: return null
        val cwd = ProjectSidebar.getInstance(project).row(file.address)?.cwd ?: terminal.cwd
        return createTab(core.hc, file.address, cwd)
    }

    private fun hasTerminalToolWindow(): Boolean =
        ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW) != null

    /** Closes the tabs whose terminal is dead, including restored tabs that have no editor yet. */
    fun closeDead() {
        val editors = FileEditorManager.getInstance(project)
        editors.openFiles.filterIsInstance<AgentTerminalFile>().filter(::isDead).forEach {
            editors.closeFile(it)
            tabs.remove(it)?.let { tab -> Disposer.dispose(tab.content) }
        }
    }

    private fun createTab(hc: Path, address: String, cwd: String): TerminalToolWindowTab {
        val manager = TerminalToolWindowTabsManager.getInstance(project)
        val tab =
            manager
                .createTabBuilder()
                .shellCommand(AttachHelper.attachCommand(hc, address))
                .processType(TerminalProcessType.NON_SHELL)
                .workingDirectory(workingDirectory(cwd))
                .tabName(address)
                .closeOnProcessTermination(true)
                .requestFocus(false)
                .createTab()
        manager.detachTab(tab)
        return tab
    }

    private fun workingDirectory(cwd: String): String? =
        cwd.takeIf { it.isNotEmpty() && Files.isDirectory(Path.of(it)) } ?: project.basePath

    companion object {
        private const val TERMINAL_TOOL_WINDOW = "Terminal"

        fun getInstance(project: Project): TerminalOpener = project.service()
    }
}

private fun tabName(row: Row): String = row.label.take(AgentTabTitleProvider.MAX_LENGTH)
