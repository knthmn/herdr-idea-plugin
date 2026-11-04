package dev.knthmn.herdr.model

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import com.intellij.util.concurrency.AppExecutorUtil
import dev.knthmn.herdr.settings.HerdrProjectSettings
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The project root, the other checkouts of its git repository (`git worktree list`) and the extra
 * folders of the project settings.
 */
@Service(Service.Level.PROJECT)
class WorktreeRoots(private val project: Project, private val cs: CoroutineScope) {
    private val mutableRoots = MutableStateFlow(listOfNotNull(project.basePath?.let(::projectRoot)))
    val roots: StateFlow<List<Root>> = mutableRoots.asStateFlow()

    init {
        cs.launch(Dispatchers.IO) {
            while (true) {
                load()
                delay(REFRESH)
            }
        }
    }

    fun refresh() {
        cs.launch(Dispatchers.IO) { load() }
    }

    private fun load() {
        val base = project.basePath ?: return
        val list = gitWorktrees(base)
        val checkouts = parse(list)
        val worktrees = external(Path.of(base), checkouts)
        val extra = HerdrProjectSettings.getInstance(project).extraRoots
        mutableRoots.value =
            listOf(projectRoot(base)) +
                worktrees +
                extra(Path.of(base), worktrees, extra) +
                nested(Path.of(base), checkouts)
    }

    private fun gitWorktrees(base: String): String =
        try {
            val builder =
                ProcessBuilder("git", "worktree", "list", "--porcelain")
                    .directory(File(base))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
            EnvironmentUtil.getValue("PATH")?.let { builder.environment()["PATH"] = it }
            val process = builder.start()
            val text =
                CompletableFuture.supplyAsync(
                    { process.inputReader().readText() },
                    AppExecutorUtil.getAppExecutorService(),
                )
            if (process.waitFor(GIT_TIMEOUT_S, TimeUnit.SECONDS) && process.exitValue() == 0) {
                text.get()
            } else {
                process.destroyForcibly()
                ""
            }
        } catch (e: IOException) {
            thisLogger().info("git worktree list failed", e)
            ""
        } catch (e: ExecutionException) {
            thisLogger().info("git worktree list failed", e)
            ""
        }

    companion object {
        private val REFRESH = 60.seconds
        private const val GIT_TIMEOUT_S = 10L

        fun getInstance(project: Project): WorktreeRoots = project.service()

        private fun projectRoot(base: String) = Root(Path.of(base))

        /** Checkouts from `git worktree list --porcelain`, without bare and prunable entries. */
        fun parse(porcelain: String): List<Root> =
            porcelain
                .split(Regex("\n\\s*\n"))
                .map { it.lines() }
                .filter { lines -> lines.none { it == "bare" || it.startsWith("prunable") } }
                .mapNotNull { lines ->
                    val path =
                        lines.firstOrNull { it.startsWith("worktree ") }?.removePrefix("worktree ")
                    val branch =
                        lines
                            .firstOrNull { it.startsWith("branch ") }
                            ?.removePrefix("branch ")
                            ?.removePrefix("refs/heads/")
                    path?.let { Root(Path.of(it), branch) }
                }

        /**
         * The [extra] folders that are absolute and lie neither inside the project folder nor in a
         * checkout of [worktrees].
         */
        fun extra(base: Path, worktrees: List<Root>, extra: List<String>): List<Root> {
            val covered = listOf(base.normalize()) + worktrees.map { it.path.normalize() }
            return extra
                .mapNotNull { runCatching { Path.of(it).normalize() }.getOrNull() }
                .filter { it.isAbsolute && covered.none(it::startsWith) }
                .distinct()
                .map { Root(it) }
        }

        /**
         * The checkouts on a branch inside the project folder. They add no scope, only the branch
         * name for the sidebar.
         */
        fun nested(base: Path, worktrees: List<Root>): List<Root> {
            val project = base.normalize()
            return worktrees.filter {
                val path = it.path.normalize()
                it.branch != null && path != project && path.startsWith(project)
            }
        }

        /** The checkouts that neither contain the project folder nor lie inside it. */
        fun external(base: Path, worktrees: List<Root>): List<Root> {
            val project = base.normalize()
            return worktrees.filterNot {
                val path = it.path.normalize()
                path.startsWith(project) || project.startsWith(path)
            }
        }
    }
}
