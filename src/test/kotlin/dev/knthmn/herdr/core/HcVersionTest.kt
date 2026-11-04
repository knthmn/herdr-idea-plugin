package dev.knthmn.herdr.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HcVersionTest {
    @Test
    fun `reads the forms that hc and the release server print`() {
        assertEquals(HcVersion(0, 1, 17), HcVersion.parse("hc 0.1.17\n"))
        assertEquals(HcVersion(0, 1, 18), HcVersion.parse("0.1.18"))
        assertEquals(HcVersion(1, 20, 3), HcVersion.parse("v1.20.3-rc.1"))
    }

    @Test
    fun `rejects text without a version`() {
        assertNull(HcVersion.parse(""))
        assertNull(HcVersion.parse("hc dev"))
        assertNull(HcVersion.parse("0.1"))
    }

    @Test
    fun `compares by number, not by text`() {
        assertTrue(HcVersion(0, 1, 10) > HcVersion(0, 1, 9))
        assertTrue(HcVersion(0, 2, 0) > HcVersion(0, 1, 99))
        assertTrue(HcVersion(0, 1, 15) < HcVersion.MINIMUM)
        assertEquals("0.1.16", HcVersion.MINIMUM.toString())
    }

    @Test
    fun `a release check is fresh for three hours`() {
        val now = 1_000_000_000L
        val hour = 3_600_000L
        assertTrue(HcReleases.isFresh(now - 2 * hour, now))
        assertFalse(HcReleases.isFresh(now - 4 * hour, now))
        assertFalse(HcReleases.isFresh(0L, now))
        assertFalse(HcReleases.isFresh(now + hour, now))
    }

    @Test
    fun `an update is a newer known release`() {
        val installed = HcVersion(0, 1, 17)
        assertTrue(HcVersion.isUpdate(installed, HcVersion(0, 1, 18)))
        assertFalse(HcVersion.isUpdate(installed, installed))
        assertFalse(HcVersion.isUpdate(installed, HcVersion(0, 1, 16)))
        assertFalse(HcVersion.isUpdate(null, HcVersion(0, 1, 18)))
        assertFalse(HcVersion.isUpdate(installed, null))
        assertFalse(HcVersion.isUpdate(installed, HcVersion(0, 1, 18), skipped = "0.1.18"))
        assertTrue(HcVersion.isUpdate(installed, HcVersion(0, 1, 19), skipped = "0.1.18"))
    }

    @Test
    fun `hc is managed only directly in the install folder`() {
        val home = Files.createTempDirectory("hc-test")
        try {
            val dir = Files.createDirectories(home.resolve("bin"))
            val inside = Files.createFile(dir.resolve("herdr-core"))
            val nested = Files.createFile(Files.createDirectories(dir.resolve("sub")).resolve("hc"))
            val link = Files.createSymbolicLink(home.resolve("hc"), inside)
            val other = Files.createFile(home.resolve("other"))
            assertTrue(HcLocator.isManaged(inside, dir))
            assertTrue(HcLocator.isManaged(link, dir))
            assertFalse(HcLocator.isManaged(nested, dir))
            assertFalse(HcLocator.isManaged(other, dir))
        } finally {
            home.toFile().deleteRecursively()
        }
    }
}
