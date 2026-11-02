package dev.knthmn.herdr.model

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import dev.knthmn.herdr.core.CoreState
import dev.knthmn.herdr.core.HcClient
import dev.knthmn.herdr.core.Terminal
import dev.knthmn.herdr.terminal.AgentTerminalFile
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SidebarState {
    data object NotFound : SidebarState

    data object Connecting : SidebarState

    data class Disconnected(val reason: String) : SidebarState

    data object NoProject : SidebarState

    data class Ready(val hc: Path, val model: SidebarModel) : SidebarState
}

/**
 * The sidebar content of one project, derived from the shared core connection. An agent is read
 * when its editor tab is selected, and stays read while the tab is selected.
 */
@Service(Service.Level.PROJECT)
class ProjectSidebar(private val project: Project, cs: CoroutineScope) {
    private val realPaths = ConcurrentHashMap<String, Path>()
    private val readState = ReadState.getInstance()

    @Volatile private var selected: String? = null

    init {
        project.messageBus
            .connect(cs)
            .subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : FileEditorManagerListener {
                    override fun selectionChanged(event: FileEditorManagerEvent) {
                        select((event.newFile as? AgentTerminalFile)?.address)
                    }
                },
            )
    }

    val state: StateFlow<SidebarState> =
        combine(
                HcClient.getInstance().state,
                readState.readAt,
                WorktreeRoots.getInstance(project).roots,
                ::toSidebarState,
            )
            .flowOn(Dispatchers.Default)
            .stateIn(
                cs,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT.inWholeMilliseconds),
                SidebarState.Connecting,
            )

    @Volatile private var rows: Map<String, Row> = emptyMap()

    init {
        cs.launch(Dispatchers.EDT) { state.collect(::refreshTabs) }
    }

    /** The latest row of [address], if it is in this project. */
    fun row(address: String): Row? = rows[address]

    /** The latest terminal [address] of the core, in this project or not. */
    fun terminal(address: String): Terminal? =
        (HcClient.getInstance().state.value as? CoreState.Connected)?.table?.terminals?.find {
            it.address == address
        }

    fun isLive(address: String): Boolean = terminal(address)?.finished == false

    /** Redraws the editor tabs whose icon or status changed. */
    private fun refreshTabs(state: SidebarState) {
        val next =
            (state as? SidebarState.Ready)
                ?.model
                ?.let { it.rows.flatMap(::flatten) + it.finished }
                .orEmpty()
                .associateBy { it.address }
        val previous = rows
        rows = next
        val editors = FileEditorManagerEx.getInstanceEx(project)
        editors.openFiles
            .filterIsInstance<AgentTerminalFile>()
            .filter { file ->
                val before = previous[file.address]
                val after = next[file.address]
                before?.state != after?.state ||
                    before?.program != after?.program ||
                    before?.label != after?.label
            }
            .forEach(editors::updateFilePresentation)
    }

    private fun flatten(row: Row): List<Row> = listOf(row) + row.children.flatMap(::flatten)

    private fun select(address: String?) {
        selected = address
        address?.let(readState::markRead)
    }

    private fun toSidebarState(
        core: CoreState,
        readAt: Map<String, Long>,
        roots: List<Root>,
    ): SidebarState {
        return when {
            core is CoreState.NotFound -> SidebarState.NotFound
            core is CoreState.Connecting -> SidebarState.Connecting
            core is CoreState.Disconnected -> SidebarState.Disconnected(core.reason)
            roots.isEmpty() -> SidebarState.NoProject
            core is CoreState.Connected -> {
                val terminals = core.table.terminals
                readState.retain(terminals.mapTo(HashSet()) { it.address })
                terminals
                    .find { it.address == selected && it.status?.state == "done" }
                    ?.let {
                        readState.markRead(it.address, it.statusMs ?: System.currentTimeMillis())
                    }
                SidebarState.Ready(
                    core.hc,
                    SidebarModel.build(terminals, roots, readAt::get, ::realPath),
                )
            }
            else -> SidebarState.Connecting
        }
    }

    private fun realPath(path: String): Path =
        realPaths.getOrPut(path) {
            val normal = Path.of(path).normalize()
            try {
                normal.toRealPath()
            } catch (_: IOException) {
                normal
            }
        }

    companion object {
        private val STOP_TIMEOUT = 5.seconds

        fun getInstance(project: Project): ProjectSidebar = project.service()
    }
}
