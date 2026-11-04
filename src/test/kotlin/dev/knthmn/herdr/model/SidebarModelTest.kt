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
    private val keys = LabelKeys("resume", "title", "blocked")

    private fun build(
        terminals: List<Terminal>,
        roots: List<Root>,
        readAt: (String) -> Long? = { null },
        keys: LabelKeys = this.keys,
        view: ViewOptions = ViewOptions(),
    ) = SidebarModel.build(terminals, roots, readAt, keys = keys, view = view)

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
            build(
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
            build(
                listOf(t("t1", cwd = "/home"), t("t2", parent = "t1", cwd = "/work/app")),
                root,
            )
        assertEquals(listOf("t2"), model.rows.map { it.address })
    }

    @Test
    fun `rows are most recent first`() {
        val model =
            build(
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
            build(
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
            build(
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
            build(
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
            build(
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
            build(
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
            build(
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
            build(
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
            build(
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

    @Test
    fun `empty label keys turn the label features off`() {
        val labels = mapOf("resume" to "pi", "title" to "Fixing x", "blocked" to "Pick one")
        val model =
            build(
                listOf(
                    t(
                        "t1",
                        cwd = "/work/app",
                        fg = "/work/app",
                        state = "working",
                        name = "review",
                        labels = labels,
                    )
                ),
                root,
                keys = LabelKeys(),
            )
        val row = model.rows.single()
        assertEquals("review", row.label)
        assertEquals(AgentState.RUNNING, row.state)
        assertEquals(null, row.resume)
        assertFalse(row.hasBlockedLabel)
    }

    @Test
    fun `label keys can be renamed`() {
        val model =
            build(
                listOf(t("t1", cwd = "/work/app", labels = mapOf("my.title" to "Fixing x"))),
                root,
                keys = LabelKeys(title = "my.title"),
            )
        assertEquals("Fixing x", model.rows.single().label)
    }

    @Test
    fun `session file is read from the resume command`() {
        assertEquals("/a/b c.jsonl", Row.sessionFile("pi --session '/a/b c.jsonl'"))
        assertEquals("/a/it's.jsonl", Row.sessionFile("pi --session '/a/it'\\''s.jsonl'"))
        assertEquals("/a/b", Row.sessionFile("pi --session /a/b --other"))
        assertEquals(null, Row.sessionFile("pi"))
    }

    @Test
    fun `default view has no groups`() {
        val model = build(listOf(t("a", cwd = "/work/app")), root)
        assertEquals(emptyList(), model.groups)
    }

    @Test
    fun `status sort puts attention first, then most recent`() {
        val model =
            build(
                listOf(
                    t("none", cwd = "/work/app", since = 9),
                    t("idle", cwd = "/work/app", state = "idle", since = 8),
                    t("running", cwd = "/work/app", state = "working", since = 7),
                    t("unread", cwd = "/work/app", state = "done", since = 6),
                    t("error", cwd = "/work/app", state = "error", since = 5),
                    t("old", cwd = "/work/app", state = "blocked", since = 1),
                    t("new", cwd = "/work/app", state = "blocked", since = 2),
                    t(
                        "label",
                        cwd = "/work/app",
                        fg = "/work/app",
                        state = "working",
                        since = 0,
                        labels = mapOf("blocked" to "question"),
                    ),
                ),
                root,
                view = ViewOptions(sort = SortOrder.STATUS),
            )
        assertEquals(
            listOf("new", "old", "label", "error", "unread", "running", "idle", "none"),
            model.rows.map { it.address },
        )
    }

    @Test
    fun `status sort applies to children`() {
        val model =
            build(
                listOf(
                    t("p", cwd = "/work/app"),
                    t("c1", parent = "p", cwd = "/work/app", state = "idle", since = 5),
                    t("c2", parent = "p", cwd = "/work/app", state = "error", since = 1),
                ),
                root,
                view = ViewOptions(sort = SortOrder.STATUS),
            )
        assertEquals(listOf("c2", "c1"), model.rows.single().children.map { it.address })
    }

    @Test
    fun `group by folder orders groups by root then path`() {
        val roots = root + Root(Path.of("/wt/app/feat-x"), "feat/x") + Root(Path.of("/extra/docs"))
        val model =
            build(
                listOf(
                    t("other", cwd = "/work/app", fg = "/tmp", since = 7),
                    t("extra", cwd = "/extra/docs", since = 6),
                    t("wtsub", cwd = "/wt/app/feat-x/src", since = 5),
                    t("wt", cwd = "/wt/app/feat-x", since = 4),
                    t("sub", cwd = "/work/app/sub", since = 3),
                    t("fg", cwd = "/home", fg = "/work/app", since = 2),
                    t("root", cwd = "/work/app", since = 1),
                ),
                roots,
                view = ViewOptions(groupByFolder = true),
            )
        assertEquals(
            listOf("app", "sub", "feat/x", "feat/x/src", "docs", "/tmp"),
            model.groups.map { it.label },
        )
        assertEquals(
            listOf(null, null, "/wt/app/feat-x", "/wt/app/feat-x/src", null, null),
            model.groups.map { it.location },
        )
        assertEquals(listOf("fg", "root"), model.groups.first().rows.map { it.address })
        assertEquals(model.groups.flatMap { it.rows }, model.rows)
        assertTrue(model.rows.all { it.detail.isEmpty() })
    }

    @Test
    fun `grouped children stay under their parent and keep a different folder`() {
        val model =
            build(
                listOf(
                    t("p", cwd = "/work/app/sub", since = 3),
                    t("same", parent = "p", cwd = "/work/app/sub", since = 2),
                    t("other", parent = "p", cwd = "/work/app/lib", since = 1),
                    t("done", cwd = "/work/app/sub", finished = 1),
                ),
                root,
                view = ViewOptions(groupByFolder = true, sort = SortOrder.STATUS),
            )
        val group = model.groups.single()
        assertEquals("sub", group.label)
        val parent = group.rows.single()
        assertEquals(listOf("", "lib"), parent.children.map { it.detail })
        assertEquals(listOf("done"), model.finished.map { it.address })
        assertEquals("sub", model.finished.single().detail)
    }

    @Test
    fun `a worktree inside the project groups by branch with its folder`() {
        val roots = root + Root(Path.of("/work/app/.dev/worktrees/x"), "feat/x")
        val model =
            build(
                listOf(
                    t("wt", cwd = "/work/app/.dev/worktrees/x", since = 2),
                    t("sub", cwd = "/work/app/.dev/worktrees/x/src", since = 1),
                ),
                roots,
                view = ViewOptions(groupByFolder = true),
            )
        assertEquals(listOf("feat/x", "feat/x/src"), model.groups.map { it.label })
        assertEquals(
            listOf(".dev/worktrees/x", ".dev/worktrees/x/src"),
            model.groups.map { it.location },
        )
        val flat = build(listOf(t("wt", cwd = "/work/app/.dev/worktrees/x")), roots)
        assertEquals(".dev/worktrees/x", flat.rows.single().detail)
    }

    @Test
    fun `status sort orders the rows inside a group`() {
        val model =
            build(
                listOf(
                    t("idle", cwd = "/work/app", state = "idle", since = 9),
                    t("blocked", cwd = "/work/app", state = "blocked", since = 1),
                    t("error", cwd = "/work/app", state = "error", since = 5),
                ),
                root,
                view = ViewOptions(groupByFolder = true, sort = SortOrder.STATUS),
            )
        assertEquals(
            listOf("blocked", "error", "idle"),
            model.groups.single().rows.map { it.address },
        )
    }

    @Test
    fun `a terminal without a status sorts by its creation time`() {
        val model =
            build(
                listOf(
                    t("old", cwd = "/work/app", state = "idle", since = 5),
                    t("new", cwd = "/work/app").copy(statusMs = null, createdMs = 9),
                ),
                root,
            )
        assertEquals(listOf("new", "old"), model.rows.map { it.address })
        assertEquals(9L, model.rows.first().sinceMs)
    }

    @Test
    fun `an empty foreground cwd falls back to the terminal cwd`() {
        val model =
            build(
                listOf(t("a", cwd = "/work/app/sub").copy(foreground = Foreground(cwd = ""))),
                root,
                view = ViewOptions(groupByFolder = true),
            )
        assertEquals("/work/app/sub", model.rows.first { it.address == "a" }.cwd)
        assertEquals(listOf("sub"), model.groups.map { it.label })
    }
}
