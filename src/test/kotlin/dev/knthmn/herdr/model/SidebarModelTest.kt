package dev.knthmn.herdr.model

import dev.knthmn.herdr.core.Foreground
import dev.knthmn.herdr.core.Status
import dev.knthmn.herdr.core.Terminal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SidebarModelTest {
    private val root = listOf(Root(Path.of("/work/app")))

    private fun t(
        address: String,
        cwd: String = "/elsewhere",
        fg: String? = null,
        parent: String? = null,
        state: String? = null,
        name: String? = null,
        title: String? = null,
        since: Long = 0,
        labels: Map<String, String> = emptyMap(),
        finished: Long? = null,
        argv: List<String> = emptyList(),
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
            labels = labels,
            state = if (finished != null) "finished" else "live",
            finishedMs = finished,
            argv = argv,
            dieWithParent = parent != null,
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
        assertEquals("pi", lead.program.key)
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
        assertEquals(listOf("review", "pi title", "Pi"), model.rows.map { it.label })
    }

    @Test
    fun `title label wins over name`() {
        val model =
            SidebarModel.build(
                listOf(
                    t(
                        "t1",
                        cwd = "/work/app",
                        name = "review",
                        labels = mapOf("title" to "Fixing x"),
                    )
                ),
                root,
            )
        assertEquals("Fixing x", model.rows.single().label)
    }

    @Test
    fun `external worktrees are in scope and show the branch`() {
        val roots = root + Root(Path.of("/wt/app/feat-x"), "feat/x")
        val model =
            SidebarModel.build(
                listOf(
                    t("t1", cwd = "/wt/app/feat-x", since = 2),
                    t("t2", cwd = "/wt/app/feat-x/src", since = 1),
                    t("t3", cwd = "/wt/other"),
                ),
                roots,
            )
        assertEquals(listOf("t1", "t2"), model.rows.map { it.address })
        assertEquals(listOf("feat/x", "feat/x/src"), model.rows.map { it.detail })
    }

    @Test
    fun `finished terminals are a flat list, newest first`() {
        val model =
            SidebarModel.build(
                listOf(
                    t("t1", cwd = "/work/app"),
                    t("t2", parent = "t1", cwd = "/tmp", finished = 1),
                    t("t3", cwd = "/work/app", finished = 2, labels = mapOf("resume" to "pi -c")),
                    t("t4", parent = "t3", cwd = "/tmp"),
                ),
                root,
            )
        assertEquals(listOf("t4", "t1"), model.rows.map { it.address }.sorted().reversed())
        assertEquals(emptyList(), model.rows.single { it.address == "t1" }.children)
        assertEquals(listOf("t3", "t2"), model.finished.map { it.address })
        assertEquals(AgentState.FINISHED, model.finished.first().state)
        assertTrue(model.finished.first().canResume)
        assertFalse(model.finished[1].canResume)
    }

    @Test
    fun `resume needs a shell when live`() {
        val model =
            SidebarModel.build(
                listOf(
                    t(
                        "shell",
                        cwd = "/work/app",
                        argv = listOf("fish", "-l"),
                        labels = mapOf("resume" to "pi"),
                        since = 2,
                    ),
                    t(
                        "agent",
                        cwd = "/work/app",
                        fg = "/work/app",
                        state = "idle",
                        labels = mapOf("resume" to "pi"),
                        since = 1,
                    ),
                ),
                root,
            )
        assertEquals(listOf(true, false), model.rows.map { it.canResume })
    }

    @Test
    fun `live descendants count those that end with the parent`() {
        val model =
            SidebarModel.build(
                listOf(
                    t("t1", cwd = "/work/app"),
                    t("t2", parent = "t1", cwd = "/tmp"),
                    t("t3", parent = "t2", cwd = "/tmp"),
                    t("t4", parent = "t1", cwd = "/tmp", finished = 1),
                ),
                root,
            )
        assertEquals(2, model.rows.single().liveDescendants)
    }

    @Test
    fun `finished reason`() {
        assertEquals("killed", SidebarModel.reason(Terminal("t", reason = "killed", exitCode = 0)))
        assertEquals(
            "exited 1",
            SidebarModel.reason(Terminal("t", reason = "exited", exitCode = 1)),
        )
        assertEquals("signal 9", SidebarModel.reason(Terminal("t", signal = 9)))
        assertEquals("server stop", SidebarModel.reason(Terminal("t", reason = "server_stop")))
    }

    @Test
    fun `blocked label marks a live agent blocked`() {
        val blocked = mapOf("blocked" to "Pick a library")
        val model =
            SidebarModel.build(
                listOf(
                    t(
                        "agent",
                        cwd = "/work/app",
                        fg = "/work/app",
                        state = "working",
                        labels = blocked,
                        since = 3,
                    ),
                    t(
                        "shell",
                        cwd = "/work/app",
                        argv = listOf("fish", "-l"),
                        labels = blocked,
                        since = 2,
                    ),
                    t("gone", cwd = "/work/app", labels = blocked, finished = 1),
                ),
                root,
            )
        val agent = model.rows.single { it.address == "agent" }
        assertEquals(AgentState.BLOCKED, agent.state)
        assertEquals("Pick a library", agent.message)
        assertEquals(AgentState.NONE, model.rows.single { it.address == "shell" }.state)
        assertEquals(AgentState.FINISHED, model.finished.single().state)
    }
}
