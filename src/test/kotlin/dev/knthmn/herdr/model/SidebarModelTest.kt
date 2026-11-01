package dev.knthmn.herdr.model

import dev.knthmn.herdr.core.Foreground
import dev.knthmn.herdr.core.Status
import dev.knthmn.herdr.core.Terminal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SidebarModelTest {
    private val root = Path.of("/work/app")

    private fun t(
        address: String,
        cwd: String = "/elsewhere",
        fg: String? = null,
        parent: String? = null,
        state: String? = null,
        name: String? = null,
        title: String? = null,
        since: Long = 0,
    ) =
        Terminal(
            address = address,
            name = name,
            title = title,
            cwd = cwd,
            foreground = fg?.let { Foreground(name = "node", cwd = it, argv = listOf("pi")) },
            parent = parent,
            status = state?.let { Status(state = it) },
            statusMs = since,
        )

    @Test
    fun `scope uses cwd or foreground cwd and adds descendants`() {
        val model =
            SidebarModel.build(
                listOf(
                    t("t1", cwd = "/home", fg = "/work/app", state = "done", since = 2),
                    t("t2", parent = "t1", cwd = "/work/app/.dev/worktrees/x", state = "working"),
                    t("t3", parent = "t2", cwd = "/tmp"),
                    t("t4", cwd = "/work/app/sub", state = "idle", since = 1),
                    t("t5", cwd = "/work/application"),
                    t("t6", cwd = "/other", state = "done"),
                ),
                root,
            )
        assertEquals(listOf("t1", "t4"), model.rows.map { it.address })
        val lead = model.rows.first()
        assertEquals("pi", lead.agent)
        assertEquals("t2", lead.children.single().address)
        assertEquals("t3", lead.children.single().children.single().address)
        assertEquals(".dev/worktrees/x", lead.children.single().detail)
        assertEquals("sub", model.rows[1].detail)
    }

    @Test
    fun `child in scope without its parent is a top-level row`() {
        val model =
            SidebarModel.build(
                listOf(t("t1", cwd = "/home"), t("t2", parent = "t1", cwd = "/work/app")),
                root,
            )
        assertEquals(listOf("t2"), model.rows.map { it.address })
    }

    @Test
    fun `rows are most recent first`() {
        val model =
            SidebarModel.build(
                listOf(
                    t("a", cwd = "/work/app", since = 1),
                    t("b", cwd = "/work/app", since = 3),
                    t("c", cwd = "/work/app", since = 2),
                ),
                root,
            )
        assertEquals(listOf("b", "c", "a"), model.rows.map { it.address })
    }

    @Test
    fun `states map from the reported status`() {
        val readAt = mapOf("read" to 10L, "stale" to 4L)
        val model =
            SidebarModel.build(
                listOf(
                    t("working", cwd = "/work/app", state = "working"),
                    t("blocked", cwd = "/work/app", state = "blocked"),
                    t("error", cwd = "/work/app", state = "error"),
                    t("unread", cwd = "/work/app", state = "done", since = 5),
                    t("stale", cwd = "/work/app", state = "done", since = 5),
                    t("read", cwd = "/work/app", state = "done", since = 5),
                    t("idle", cwd = "/work/app", state = "idle"),
                    t("shell", cwd = "/work/app"),
                ),
                root,
                readAt::get,
            )
        assertEquals(
            mapOf(
                "working" to AgentState.RUNNING,
                "blocked" to AgentState.BLOCKED,
                "error" to AgentState.ERROR,
                "unread" to AgentState.UNREAD,
                "stale" to AgentState.UNREAD,
                "read" to AgentState.IDLE,
                "idle" to AgentState.IDLE,
                "shell" to AgentState.NONE,
            ),
            model.rows.associate { it.address to it.state },
        )
    }

    @Test
    fun `label prefers name then title then foreground`() {
        val model =
            SidebarModel.build(
                listOf(
                    t("t1", cwd = "/work/app", name = "review", title = "x", since = 3),
                    t("t2", cwd = "/work/app", title = "pi title", since = 2),
                    t("t3", cwd = "/work/app", fg = "/work/app", title = "", since = 1),
                ),
                root,
            )
        assertEquals(listOf("review", "pi title", "node"), model.rows.map { it.label })
    }
}
