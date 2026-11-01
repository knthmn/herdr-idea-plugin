package dev.knthmn.herdr.terminal

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.knthmn.herdr.model.AgentState
import dev.knthmn.herdr.model.ProjectSidebar
import dev.knthmn.herdr.ui.ProgramIcons
import dev.knthmn.herdr.ui.StatusIcon
import javax.swing.Icon

/** The program icon with its status dot, for editor tabs of hc terminals. */
class AgentFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        if (file !is AgentTerminalFile) return null
        val row = project?.let { ProjectSidebar.getInstance(it).row(file.address) }
        return StatusIcon.badged(
            ProgramIcons.of(row?.program ?: file.program),
            row?.state ?: AgentState.NONE,
        )
    }
}
