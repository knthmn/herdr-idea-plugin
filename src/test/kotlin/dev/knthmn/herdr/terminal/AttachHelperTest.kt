package dev.knthmn.herdr.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttachHelperTest {
    @Test
    fun `maps os and arch to the bundled folder`() {
        assertEquals("darwin-aarch64", AttachHelper.platform("Mac OS X", "aarch64"))
        assertEquals("darwin-x86_64", AttachHelper.platform("Mac OS X", "x86_64"))
        assertEquals("linux-x86_64", AttachHelper.platform("Linux", "amd64"))
        assertEquals("linux-aarch64", AttachHelper.platform("Linux", "arm64"))
    }

    @Test
    fun `has no folder for other systems`() {
        assertNull(AttachHelper.platform("Windows 11", "amd64"))
        assertNull(AttachHelper.platform("Linux", "riscv64"))
    }
}
