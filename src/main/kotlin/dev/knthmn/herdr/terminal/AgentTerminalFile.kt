package dev.knthmn.herdr.terminal

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.ex.FakeFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.testFramework.LightVirtualFile
import javax.swing.Icon

/** An hc terminal shown in the editor area. */
class AgentTerminalFile(
    val address: String,
    val agent: String?,
    val tab: TerminalToolWindowTab,
    name: String,
) : LightVirtualFile(name) {
    init {
        fileType = AgentTerminalFileType
        isWritable = false
    }
}

object AgentTerminalFileType : FakeFileType() {
    override fun getName(): String = "Herdr terminal"

    override fun getDescription(): String = "herdr-core terminal"

    override fun getIcon(): Icon = AllIcons.Nodes.Console

    override fun isMyFileType(file: VirtualFile): Boolean = file is AgentTerminalFile
}
