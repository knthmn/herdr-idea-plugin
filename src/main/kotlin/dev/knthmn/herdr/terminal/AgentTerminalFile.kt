package dev.knthmn.herdr.terminal

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.ex.FakeFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.openapi.vfs.VirtualFileWithoutContent
import com.intellij.testFramework.LightVirtualFile
import dev.knthmn.herdr.model.Program
import dev.knthmn.herdr.model.Programs
import javax.swing.Icon

/** An hc terminal shown in the editor area. Get it from [HerdrFileSystem.file]. */
class AgentTerminalFile internal constructor(val ref: TerminalPath) :
    LightVirtualFile(ref.label), VirtualFileWithoutContent {
    val address: String
        get() = ref.address

    val program: Program by lazy { Programs.of(ref.programKey, emptyList()) }

    init {
        fileType = AgentTerminalFileType
        isWritable = false
    }

    override fun getFileSystem(): VirtualFileSystem = HerdrFileSystem.getInstance()

    override fun getPath(): String = ref.encode()

    override fun equals(other: Any?): Boolean =
        other is AgentTerminalFile && other.ref.identity == ref.identity

    override fun hashCode(): Int = ref.identity.hashCode()
}

object AgentTerminalFileType : FakeFileType() {
    override fun getName(): String = "Herdr terminal"

    override fun getDescription(): String = "herdr-core terminal"

    override fun getIcon(): Icon = AllIcons.Nodes.Console

    override fun isMyFileType(file: VirtualFile): Boolean = file is AgentTerminalFile
}
