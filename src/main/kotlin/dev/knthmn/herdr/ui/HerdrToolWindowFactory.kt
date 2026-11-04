package dev.knthmn.herdr.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory
import dev.knthmn.herdr.core.HcStatusService

class HerdrToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SidebarPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
        HcStatusService.getInstance().onUse(project)
    }

    companion object {
        const val ID = "Herdr"
    }
}

/** Checks herdr-core each time the Herdr tool window is shown. */
class HerdrToolWindowListener(private val project: Project) : ToolWindowManagerListener {
    override fun toolWindowShown(toolWindow: ToolWindow) {
        if (toolWindow.id == HerdrToolWindowFactory.ID) HcStatusService.getInstance().onUse(project)
    }
}
