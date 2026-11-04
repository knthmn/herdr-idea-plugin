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
        assertEquals(emptyList(), WorktreeRoots.nested(Path.of("/work/app"), roots))
        assertEquals(
            listOf(Root(Path.of("/work/app/.dev/worktrees/x"), "x")),
            WorktreeRoots.nested(
                Path.of("/work/app"),
                roots.map { if (it.branch == null) it.copy(branch = "x") else it },
            ),
        )
    }

    @Test
    fun `extra roots skip relative and covered folders`() {
        val worktrees = listOf(Root(Path.of("/wt/app/feat-x"), "feat/x"))
        assertEquals(
            listOf(Root(Path.of("/notes")), Root(Path.of("/wt"))),
            WorktreeRoots.extra(
                Path.of("/work/app"),
                worktrees,
                listOf("/notes", "/work/app/sub", "/wt/app/feat-x/src", "rel", "/notes/", "/wt"),
            ),
        )
    }
}
