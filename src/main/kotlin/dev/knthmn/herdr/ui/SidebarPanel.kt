package dev.knthmn.herdr.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import dev.knthmn.herdr.model.ProjectSidebar
import dev.knthmn.herdr.model.Row
import dev.knthmn.herdr.model.SidebarModel
import dev.knthmn.herdr.model.SidebarState
import dev.knthmn.herdr.settings.HerdrConfigurable
import dev.knthmn.herdr.settings.HerdrSettings
import dev.knthmn.herdr.terminal.SessionActions
import dev.knthmn.herdr.terminal.TerminalOpener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.SwingUtilities
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
    private val actions = SidebarActions(project, ::selectedRows)

    init {
        Disposer.register(parent, this)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = SidebarRenderer()
        tree.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1) {
                        tree.getPathForLocation(e.x, e.y)?.let(::row)?.let(::open)
                    }
                }
            }
        )
        tree.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER) selectedRows().forEach(::open)
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
    }

    private fun selectedRows(): List<Row> =
        tree.selectionPaths.orEmpty().sortedBy(tree::getRowForPath).mapNotNull(::row)

    private fun open(row: Row) {
        if (row.canResume && HerdrSettings.getInstance().resumeOnClick) {
            SessionActions.getInstance(project).resume(row)
            return
        }
        if (row.finished) return
        val hc = hc ?: return
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
                    ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, HerdrConfigurable::class.java)
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
        model.rows.forEach { root.add(rowNode(it)) }
        finishedNode(model)?.let(root::add)
        treeModel.reload()

        visit(root) { node ->
            val path = TreePath(node.path)
            val expand =
                if (node.userObject is FinishedGroup) finishedExpanded else key(node) !in collapsed
            if (node !== root && node.childCount > 0 && expand) tree.expandPath(path)
            if (node !== root && key(node) in selected) tree.addSelectionPath(path)
        }
    }

    private fun finishedNode(model: SidebarModel): DefaultMutableTreeNode? =
        model.finished
            .takeIf { it.isNotEmpty() }
            ?.let { rows ->
                DefaultMutableTreeNode(FinishedGroup(rows.size)).apply {
                    rows.forEach { add(DefaultMutableTreeNode(it)) }
                }
            }

    private fun rowNode(row: Row): DefaultMutableTreeNode =
        DefaultMutableTreeNode(row).apply { row.children.forEach { add(rowNode(it)) } }

    private fun key(node: DefaultMutableTreeNode): String =
        when (val item = node.userObject) {
            is Row -> item.address
            is FinishedGroup -> FINISHED_KEY
            else -> ""
        }

    private fun visit(node: DefaultMutableTreeNode, action: (DefaultMutableTreeNode) -> Unit) {
        action(node)
        node.children().asSequence().forEach { visit(it as DefaultMutableTreeNode, action) }
    }

    override fun dispose() {
        ageTimer.stop()
        scope.cancel()
    }

    companion object {
        private const val AGE_REFRESH_MS = 30_000
        private const val TOOLBAR_PLACE = "HerdrSidebarToolbar"
        private const val POPUP_PLACE = "HerdrSidebarPopup"
        private const val FINISHED_KEY = "#finished"
    }
}
