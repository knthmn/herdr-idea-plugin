package dev.knthmn.herdr.settings

import com.intellij.util.xmlb.XmlSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

class HerdrSettingsStateTest {
    @Test
    fun `defaults and emptied keys survive a round trip`() {
        val state = HerdrSettingsState()
        assertEquals("resume", state.resumeLabel)
        state.titleLabel = ""
        val element = XmlSerializer.serialize(state)
        val loaded = XmlSerializer.deserialize(element, HerdrSettingsState::class.java)
        assertEquals("resume", loaded.resumeLabel)
        assertEquals("", loaded.titleLabel)
        assertEquals("blocked", loaded.blockedLabel)
    }

    @Test
    fun `view options default and survive a round trip`() {
        val state = HerdrSettingsState()
        assertEquals(false, state.groupByFolder)
        assertEquals("RECENT", state.sortOrder)
        state.groupByFolder = true
        state.sortOrder = "STATUS"
        val element = XmlSerializer.serialize(state)
        val loaded = XmlSerializer.deserialize(element, HerdrSettingsState::class.java)
        assertEquals(true, loaded.groupByFolder)
        assertEquals("STATUS", loaded.sortOrder)
    }
}
