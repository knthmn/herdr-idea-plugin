package dev.knthmn.herdr.model

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class WorktreeRootsTest {
    @Test
    fun `parse skips bare and prunable checkouts`() {
        val porcelain =
            """
            worktree /work/app
            HEAD abc
            branch refs/heads/main

            worktree /work/app/.dev/worktrees/x
            HEAD def
            detached

            worktree /home/me/.herdr/worktrees/app/feat-y
            HEAD 123
            branch refs/heads/feat/y

            worktree /gone
            HEAD 456
            branch refs/heads/old
            prunable gitdir file points to non-existent location

            worktree /bare.git
            bare
            """
                .trimIndent()
        val roots = WorktreeRoots.parse(porcelain)
        assertEquals(
            listOf(
                Root(Path.of("/work/app"), "main"),
                Root(Path.of("/work/app/.dev/worktrees/x"), null),
                Root(Path.of("/home/me/.herdr/worktrees/app/feat-y"), "feat/y"),
            ),
            roots,
        )
        assertEquals(
            listOf(Root(Path.of("/home/me/.herdr/worktrees/app/feat-y"), "feat/y")),
            WorktreeRoots.external(Path.of("/work/app"), roots),
        )
        assertEquals(
            listOf(
                Root(Path.of("/work/app/.dev/worktrees/x"), null),
                Root(Path.of("/home/me/.herdr/worktrees/app/feat-y"), "feat/y"),
            ),
            WorktreeRoots.external(Path.of("/work/app/sub"), roots),
        )
    }
}
