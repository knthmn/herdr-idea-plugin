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
}
