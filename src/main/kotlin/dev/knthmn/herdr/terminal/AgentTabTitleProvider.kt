package dev.knthmn.herdr.terminal

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.knthmn.herdr.model.ProjectSidebar

/** The current row label, for editor tabs of hc terminals. */
class AgentTabTitleProvider : EditorTabTitleProvider, DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? {
        if (file !is AgentTerminalFile) return null
        return ProjectSidebar.getInstance(project).row(file.address)?.label?.take(MAX_LENGTH)
    }

    companion object {
        const val MAX_LENGTH = 40
    }
}
