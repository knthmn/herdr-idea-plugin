package dev.knthmn.herdr.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class TerminalTableTest {
    private fun obj(text: String): JsonObject = HcJson.parseToJsonElement(text).jsonObject

    private val snapshot =
        obj(
            """
            {"seq":10,"terminals":[
              {"address":"t1","cwd":"/p","state":"live","status":{"state":"working"},"title":"one"},
              {"address":"t2","cwd":"/p","state":"finished"},
              {"address":"box/t3","cwd":"/p","state":"live"}
            ]}
            """
        )

    @Test
    fun `snapshot keeps live local terminals only`() {
        val table = TerminalTable.fromSnapshot(snapshot)!!
        assertEquals(10, table.seq)
        assertEquals(listOf("t1"), table.terminals.map { it.address })
    }

    @Test
    fun `recorded event sequence`() {
        var table = TerminalTable.fromSnapshot(snapshot)!!
        val events =
            listOf(
                """{"type":"created","terminal":"t22","seq":11,"data":{"address":"t22","cwd":"/tmp","name":"probe","parent":"t1","state":"live","status":null,"title":"","created_ms":5}}""",
                """{"type":"status","terminal":"t22","seq":12,"data":{"records":{},"reported":true,"status":{"app":"x","state":"working"}}}""",
                """{"type":"foreground","terminal":"t22","seq":13,"data":{"cwd":"/private/tmp","name":"bash","pid":1}}""",
                """{"type":"title","terminal":"t22","seq":14,"data":{"title":"hello"}}""",
            )
        events.forEach { table = table.apply(obj(it), nowMs = 99) }

        val probe = table.terminals.single { it.address == "t22" }
        assertEquals("working", probe.status?.state)
        assertEquals(99, probe.statusMs)
        assertEquals("hello", probe.title)
        assertEquals("t1", probe.parent)
        assertEquals("/tmp", probe.cwd)
        assertEquals("/private/tmp", probe.foreground?.cwd)
        assertEquals("bash", probe.foreground?.name)

        table =
            table.apply(
                obj("""{"type":"finished","terminal":"t22","seq":15,"data":{"exit_code":0}}"""),
                nowMs = 100,
            )
        assertNull(table.terminals.find { it.address == "t22" })
    }

    @Test
    fun `old and remote events are ignored`() {
        val table = TerminalTable.fromSnapshot(snapshot)!!
        val stale =
            table.apply(obj("""{"type":"removed","terminal":"t1","seq":10,"data":{}}"""), nowMs = 0)
        val remote =
            table.apply(
                obj(
                    """{"type":"created","terminal":"box/t9","seq":11,"data":{"address":"box/t9"}}"""
                ),
                nowMs = 0,
            )
        assertEquals(listOf("t1"), stale.terminals.map { it.address })
        assertEquals(listOf("t1"), remote.terminals.map { it.address })
    }
}
