package dev.knthmn.herdr.terminal

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.knthmn.herdr.ui.ProgramIcons
import javax.swing.Icon

class AgentFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        (file as? AgentTerminalFile)?.let { ProgramIcons.of(it.program) }
}
