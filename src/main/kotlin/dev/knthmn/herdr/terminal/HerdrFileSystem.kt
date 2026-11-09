package dev.knthmn.herdr.terminal

import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.NonPhysicalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.containers.ContainerUtil
import dev.knthmn.herdr.core.CoreState
import dev.knthmn.herdr.core.HcClient

/**
 * Resolves `herdr://` URLs to [AgentTerminalFile]s, so that the IDE restores terminal tabs on
 * startup. The IDE can resolve the URLs before the project is open, so they do not name a project.
 */
class HerdrFileSystem : DeprecatedVirtualFileSystem(), NonPhysicalFileSystem {
    private val files =
        ContainerUtil.createConcurrentWeakValueMap<Pair<String, Long?>, AgentTerminalFile>()

    fun file(path: TerminalPath): AgentTerminalFile =
        files.computeIfAbsent(path.identity) { AgentTerminalFile(path) }

    override fun getProtocol(): String = PROTOCOL

    /** Null for a dead terminal, so that the IDE does not restore or reopen its tab. */
    override fun findFileByPath(path: String): VirtualFile? {
        val ref = TerminalPath.decode(path) ?: return null
        val core = HcClient.getInstance().state.value as? CoreState.Connected
        return if (ref.isDeadIn(core?.table?.terminals)) null else file(ref)
    }

    override fun refreshAndFindFileByPath(path: String): VirtualFile? = findFileByPath(path)

    override fun refresh(asynchronous: Boolean) = Unit

    override fun extractPresentableUrl(path: String): String =
        TerminalPath.decode(path)?.label ?: path

    companion object {
        const val PROTOCOL = "herdr"

        fun getInstance(): HerdrFileSystem =
            VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as HerdrFileSystem
    }
}
