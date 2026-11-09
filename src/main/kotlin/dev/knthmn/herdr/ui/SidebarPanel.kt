package dev.knthmn.herdr.ui

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.impl.ProjectViewPane
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.AutoScrollToSourceHandler
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import dev.knthmn.herdr.core.HcStatusService
import dev.knthmn.herdr.core.HcVersion
import dev.knthmn.herdr.model.FolderGroup
import dev.knthmn.herdr.model.ProjectSidebar
import dev.knthmn.herdr.model.Row
import dev.knthmn.herdr.model.SidebarModel
import dev.knthmn.herdr.model.SidebarState
import dev.knthmn.herdr.settings.HerdrConfigurable
import dev.knthmn.herdr.settings.HerdrSettings
import dev.knthmn.herdr.terminal.AgentTerminalFile
import dev.knthmn.herdr.terminal.SessionActions
import dev.knthmn.herdr.terminal.TerminalOpener
import java.awt.Component
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.Timer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private fun row(path: TreePath): Row? =
    (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Row

private const val FINISHED_KEY = "#finished"
private const val FOLDER_KEY = "#folder:"

private fun rowNode(row: Row): DefaultMutableTreeNode =
    DefaultMutableTreeNode(row).apply { row.children.forEach { add(rowNode(it)) } }

private fun key(node: DefaultMutableTreeNode): String =
    when (val item = node.userObject) {
        is Row -> item.address
        is FinishedGroup -> FINISHED_KEY
        is FolderGroup -> FOLDER_KEY + (item.path?.toString() ?: "")
        else -> ""
    }

private fun visit(node: DefaultMutableTreeNode, action: (DefaultMutableTreeNode) -> Unit) {
    action(node)
    node.children().asSequence().forEach { visit(it as DefaultMutableTreeNode, action) }
}

/** The tree node that holds finished terminals. */
data class FinishedGroup(val count: Int)

class SidebarPanel(private val project: Project, parent: Disposable) :
    SimpleToolWindowPanel(true, true), Disposable {
    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private val ageTimer = Timer(AGE_REFRESH_MS) { tree.repaint() }
    private var hc: Path? = null
    private var finishedExpanded = false
    private var pendingReveal: String? = null
    private val actions = SidebarActions(project, ::selectedRows)
    private val projectView: ProjectView
        get() = ProjectView.getInstance(project)

    private val projectViewPane: String
        get() = projectView.currentViewId ?: ProjectViewPane.ID

    /**
     * Opens the selected row on a single click or a keyboard move, like the Project view with "Open
     * Files with Single Click" or "Enable Preview Tab" on.
     */
    private val singleClick =
        object : AutoScrollToSourceHandler() {
            override fun isAutoScrollMode(): Boolean =
                UISettings.getInstance().openInPreviewTabIfPossible ||
                    projectView.isAutoscrollToSource(projectViewPane)

            override fun setAutoScrollMode(state: Boolean) = Unit

            override fun scrollToSource(tree: Component) {
                selectedRows().singleOrNull()?.let { open(it, preview = true) }
            }
        }

    init {
        Disposer.register(parent, this)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = SidebarRenderer()
        singleClick.install(tree)
        object : DoubleClickListener() {
                override fun onDoubleClick(e: MouseEvent): Boolean {
                    val row = tree.getPathForLocation(e.x, e.y)?.let(::row) ?: return false
                    open(row)
                    return true
                }
            }
            .installOn(tree)
        tree.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    pendingReveal = null
                    if (e.keyCode != KeyEvent.VK_ENTER) return
                    val rows = selectedRows()
                    val path = tree.selectionPath
                    when {
                        rows.isNotEmpty() -> rows.forEach { open(it) }
                        path == null -> Unit
                        tree.isExpanded(path) -> tree.collapsePath(path)
                        else -> tree.expandPath(path)
                    }
                }
            }
        )
        tree.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    pendingReveal = null
                }
            }
        )
        PopupHandler.installPopupMenu(tree, actions.popup(), POPUP_PLACE)
        toolbar =
            ActionManager.getInstance()
                .createActionToolbar(TOOLBAR_PLACE, actions.toolbar(), true)
                .apply { targetComponent = tree }
                .component
        setContent(ScrollPaneFactory.createScrollPane(tree, true))
        ageTimer.start()
        scope.launch { ProjectSidebar.getInstance(project).state.collect(::render) }
        scope.launch { HerdrSettings.getInstance().changes.collect { tree.repaint() } }
        project.messageBus
            .connect(this)
            .subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : FileEditorManagerListener {
                    override fun selectionChanged(event: FileEditorManagerEvent) {
                        onActiveFile(event.newFile)
                    }
                },
            )
        onActiveFile(FileEditorManager.getInstance(project).selectedFiles.firstOrNull())
    }

    /** Selects the row of the active terminal tab when the view option is on. */
    private fun onActiveFile(file: VirtualFile?) {
        pendingReveal = null
        if (file !is AgentTerminalFile) return
        if (!HerdrSettings.getInstance().selectOpened) return
        reveal(file.address)
    }

    /**
     * Selects the row of [address] without focus. If the tree does not have the row yet, the next
     * [update] selects it.
     */
    private fun reveal(address: String) {
        var node: DefaultMutableTreeNode? = null
        visit(root) { if (node == null && it.userObject is Row && key(it) == address) node = it }
        val found =
            node
                ?: run {
                    pendingReveal = address
                    return
                }
        pendingReveal = null
        val path = TreePath(found.path)
        if (!tree.isPathSelected(path)) {
            path.parentPath?.takeIf { it.lastPathComponent !== root }?.let(tree::expandPath)
            tree.selectionPath = path
            singleClick.cancelAllRequests()
        }
        TreeUtil.scrollToVisible(tree, path, false)
    }

    private fun selectedRows(): List<Row> =
        tree.selectionPaths.orEmpty().sortedBy(tree::getRowForPath).mapNotNull(::row)

    /**
     * Opens [row] with focus. A [preview] is a single click: it shows a live row without focus, in
     * the preview tab if the IDE uses one, and does not resume.
     */
    private fun open(row: Row, preview: Boolean = false) {
        if (preview) {
            val hc = hc ?: return
            if (row.finished) return
            val usePreview = UISettings.getInstance().openInPreviewTabIfPossible
            TerminalOpener.getInstance(project).open(hc, row, usePreview, focus = false)
            return
        }
        if (row.canResume && HerdrSettings.getInstance().resumeOnClick) {
            SessionActions.getInstance(project).resume(row)
            return
        }
        if (row.finished) return
        val hc =
            hc
                ?: run {
                    HcStatusService.getInstance().onUse(project)
                    return
                }
        TerminalOpener.getInstance(project).open(hc, row)
    }

    private fun render(state: SidebarState) {
        hc = (state as? SidebarState.Ready)?.hc
        setEmptyText(state)
        update((state as? SidebarState.Ready)?.model ?: SidebarModel.EMPTY)
    }

    private fun setEmptyText(state: SidebarState) {
        val text = tree.emptyText
        text.clear()
        when (state) {
            SidebarState.NotFound -> {
                text.appendLine("hc not found")
                text.appendLine(
                    "Install herdr-core or set the path",
                    SimpleTextAttributes.LINK_ATTRIBUTES,
                ) {
                    showSettings()
                }
            }
            is SidebarState.Unsupported -> {
                text.appendLine("herdr-core ${state.version} is too old")
                text.appendLine(
                    "Update to ${HcVersion.MINIMUM} or later",
                    SimpleTextAttributes.LINK_ATTRIBUTES,
                ) {
                    showSettings()
                }
            }
            SidebarState.Connecting -> text.appendLine("Connecting to herdr-core…")
            is SidebarState.Disconnected -> {
                text.appendLine("Not connected to herdr-core")
                text.appendLine(state.reason, SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
            }
            SidebarState.NoProject -> text.appendLine("No project folder")
            is SidebarState.Ready -> {
                text.appendLine("No terminals in this project")
                text.appendLine("Start a session", SimpleTextAttributes.LINK_ATTRIBUTES) {
                    SessionActions.getInstance(project).newDefaultSession()
                }
            }
        }
    }

    private fun showSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, HerdrConfigurable::class.java)
    }

    private fun update(model: SidebarModel) {
        val collapsed = mutableSetOf<String>()
        val selected =
            tree.selectionPaths.orEmpty().mapTo(HashSet()) {
                key(it.lastPathComponent as DefaultMutableTreeNode)
            }
        visit(root) { node ->
            val expanded = tree.isExpanded(TreePath(node.path))
            if (node.childCount > 0 && !expanded) collapsed += key(node)
            if (node.userObject is FinishedGroup) finishedExpanded = expanded
        }

        root.removeAllChildren()
        topNodes(model).forEach(root::add)
        treeModel.reload()

        visit(root) { node ->
            val path = TreePath(node.path)
            val expand =
                if (node.userObject is FinishedGroup) finishedExpanded else key(node) !in collapsed
            if (node !== root && node.childCount > 0 && expand) tree.expandPath(path)
            if (node !== root && key(node) in selected) tree.addSelectionPath(path)
        }
        singleClick.cancelAllRequests()
        pendingReveal?.let(::reveal)
    }

    private fun topNodes(model: SidebarModel): List<DefaultMutableTreeNode> {
        fun group(item: Any, children: List<DefaultMutableTreeNode>) =
            DefaultMutableTreeNode(item).apply { children.forEach(::add) }
        val live =
            if (model.groups.isEmpty()) model.rows.map(::rowNode)
            else model.groups.map { group(it, it.rows.map(::rowNode)) }
        val finished =
            model.finished
                .takeIf { it.isNotEmpty() }
                ?.let { rows ->
                    group(FinishedGroup(rows.size), rows.map(::DefaultMutableTreeNode))
                }
        return live + listOfNotNull(finished)
    }

    override fun dispose() {
        ageTimer.stop()
        scope.cancel()
    }

    companion object {
        private const val AGE_REFRESH_MS = 30_000
        private const val TOOLBAR_PLACE = "HerdrSidebarToolbar"
        private const val POPUP_PLACE = "HerdrSidebarPopup"
    }
}
