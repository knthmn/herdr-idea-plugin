package dev.knthmn.herdr.terminal

import dev.knthmn.herdr.core.Terminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalPathTest {
    @Test
    fun `round trips a label with special characters`() {
        val path = TerminalPath("t1j", 1767225600000, "pi", "Fix a/b 100% ? #1 – café")
        assertEquals(path, TerminalPath.decode(path.encode()))
    }

    @Test
    fun `encodes each segment without slashes or plus signs`() {
        val encoded = TerminalPath("t4", 5, "claude", "a/b c+d").encode()
        assertEquals("t4/5/claude/a%2Fb%20c%2Bd", encoded)
    }

    @Test
    fun `an unknown creation time encodes as a dash`() {
        val path = TerminalPath("t4", null, "shell", "zsh")
        assertEquals("t4/-/shell/zsh", path.encode())
        assertEquals(path, TerminalPath.decode(path.encode()))
    }

    @Test
    fun `malformed paths decode to null`() {
        assertNull(TerminalPath.decode("t4/5/pi"))
        assertNull(TerminalPath.decode("t4/5/pi/a/b"))
        assertNull(TerminalPath.decode("t4/soon/pi/label"))
        assertNull(TerminalPath.decode("/5/pi/label"))
        assertNull(TerminalPath.decode("t4/5/pi/%zz"))
    }

    @Test
    fun `matches by address and creation time`() {
        val path = TerminalPath("t4", 5, "pi", "x")
        assertTrue(path.matches(Terminal(address = "t4", createdMs = 5)))
        assertTrue(path.matches(Terminal(address = "t4")))
        assertFalse(path.matches(Terminal(address = "t4", createdMs = 6)))
        assertFalse(path.matches(Terminal(address = "t5", createdMs = 5)))
        assertTrue(path.copy(createdMs = null).matches(Terminal(address = "t4", createdMs = 6)))
    }

    @Test
    fun `a finished or missing terminal is not alive`() {
        val path = TerminalPath("t4", 5, "pi", "x")
        assertTrue(path.isAlive(Terminal(address = "t4", createdMs = 5)))
        assertFalse(path.isAlive(Terminal(address = "t4", createdMs = 5, state = "finished")))
        assertFalse(path.isAlive(null))
    }

    @Test
    fun `a terminal is dead only in the table of a connected core`() {
        val path = TerminalPath("t4", 5, "pi", "x")
        assertFalse(path.isDeadIn(null))
        assertFalse(path.isDeadIn(listOf(Terminal(address = "t4", createdMs = 5))))
        assertTrue(path.isDeadIn(emptyList()))
        assertTrue(
            path.isDeadIn(listOf(Terminal(address = "t4", createdMs = 5, state = "finished")))
        )
        assertTrue(path.isDeadIn(listOf(Terminal(address = "t4", createdMs = 6))))
    }
}
