package dev.knthmn.herdr.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgramsTest {
    private fun key(app: String?, vararg argv: String) = Programs.of(app, argv.toList()).key

    @Test
    fun `reported app wins and matches aliases`() {
        assertEquals("claude", key("claude-code", "node", "x.js"))
        assertEquals("pi", key("pi"))
        val unknown = Programs.of("newagent", emptyList())
        assertEquals("agent", unknown.key)
        assertEquals("newagent", unknown.name)
        assertTrue(unknown.agent)
    }

    @Test
    fun `argv resolves programs, interpreters, shells and wrappers`() {
        assertEquals("pi", key(null, "/opt/homebrew/bin/pi"))
        assertEquals("shell", key(null, "/opt/homebrew/bin/fish", "-l"))
        assertEquals("shell", key(null))
        assertEquals("program", key(null, "some-tool"))
        assertEquals("codex", key(null, "zsh", "-ic", "\"$@\"; exec \"$0\" -i", "zsh", "codex"))
    }

    @Test
    fun `program icons are bundled`() {
        listOf("claude", "codex", "pi", "shell").forEach {
            val icon = Programs.of(null, listOf(it)).icon
            assertTrue(Programs::class.java.getResource("/herdr/$icon") != null, "missing $icon")
        }
        val fallback = Programs.of("newagent", emptyList()).icon
        assertTrue(
            Programs::class.java.getResource("/herdr/$fallback") != null,
            "missing $fallback",
        )
    }
}
