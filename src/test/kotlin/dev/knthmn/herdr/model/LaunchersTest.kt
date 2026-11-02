package dev.knthmn.herdr.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LaunchersTest {
    @Test
    fun `wrap matches the app's login shell wrapper`() {
        assertEquals(
            listOf(
                "/bin/fish",
                "-lic",
                "command \$argv[2..]; exec \$argv[1] -li",
                "/bin/fish",
                "pi",
            ),
            Launchers.wrap(listOf("pi"), "/bin/fish"),
        )
        assertEquals(
            listOf("/bin/zsh", "-lic", "\"\$@\"; exec \"\$0\" -li", "/bin/zsh", "pi"),
            Launchers.wrap(listOf("pi"), "/bin/zsh"),
        )
        assertEquals(Programs.of(null, Launchers.wrap(listOf("pi"), "/bin/zsh")).key, "pi")
        assertEquals(Programs.of(null, Launchers.wrap(listOf("pi"), "/bin/fish")).key, "pi")
    }

    @Test
    fun `overrides change run and name and add entries`() {
        val overrides =
            Launchers.parseOverrides(
                """{"programs":{"claude":{"run":["my-claude"]},"alt":{"name":"Alt","run":["alt"]},"lazygit":{"name":"Git"}}}"""
            )
        val agents =
            Programs.launchable(overrides).associate { (p, run) -> p.key to (p.name to run) }
        assertEquals(listOf("my-claude"), agents["claude"]?.second)
        assertEquals("Alt" to listOf("alt"), agents["alt"])
        assertEquals(listOf("pi"), agents["pi"]?.second)
        assertTrue("lazygit" !in agents)
    }

    @Test
    fun `broken overrides are ignored`() {
        assertEquals(emptyMap(), Launchers.parseOverrides("{not json"))
    }

    @Test
    fun `wrapLine runs the line in a login shell and shows its program`() {
        val line = "pi --session '/a b'"
        assertEquals(
            listOf("/bin/fish", "-lic", "eval \$argv[2]; exec \$argv[1] -li", "/bin/fish", line),
            Launchers.wrapLine(line, "/bin/fish"),
        )
        assertEquals(
            listOf("/bin/zsh", "-lic", "eval \"\$1\"; exec \"\$0\" -li", "/bin/zsh", line),
            Launchers.wrapLine(line, "/bin/zsh"),
        )
        assertEquals("pi", Programs.of(null, Launchers.wrapLine(line, "/bin/fish")).key)
        assertEquals("pi", Programs.of(null, Launchers.wrapLine(line, "/bin/zsh")).key)
        assertEquals(Row.SHELL, Programs.of(null, listOf("/bin/fish", "-li")).key)
        val claude = "cd '/a b' && FOO=1 claude --resume x"
        assertEquals("claude", Programs.of(null, Launchers.wrapLine(claude, "/bin/zsh")).key)
        assertEquals(
            "claude",
            Programs.of(null, Launchers.wrapLine("claude -r x;", "/bin/zsh")).key,
        )
    }
}
