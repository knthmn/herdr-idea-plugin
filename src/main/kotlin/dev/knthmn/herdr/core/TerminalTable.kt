package dev.knthmn.herdr.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Live terminals of the local core, kept as raw JSON so that partial event data can be merged
 * without knowing every field.
 */
class TerminalTable private constructor(val seq: Long, private val raw: Map<String, JsonObject>) {

    val terminals: List<Terminal> by lazy {
        raw.values.mapNotNull {
            runCatching { HcJson.decodeFromJsonElement(Terminal.serializer(), it) }.getOrNull()
        }
    }

    fun apply(event: JsonObject, nowMs: Long): TerminalTable {
        val eventSeq = event["seq"]?.jsonPrimitive?.longOrNull ?: return this
        val address = event["terminal"]?.jsonPrimitive?.contentOrNull
        val type = event["type"]?.jsonPrimitive?.contentOrNull
        val data = event["data"] as? JsonObject ?: JsonObject(emptyMap())
        if (eventSeq <= seq || address == null || !isLocal(address)) return this
        val next = raw.toMutableMap()
        when (type) {
            "created" -> next[address] = data
            "finished",
            "removed" -> next.remove(address)
            else -> {
                val current = next[address]
                if (current != null) next[address] = JsonObject(current + delta(type, data, nowMs))
            }
        }
        return TerminalTable(eventSeq, next)
    }

    private fun delta(type: String?, data: JsonObject, nowMs: Long): Map<String, JsonElement> =
        when (type) {
            "foreground" -> mapOf("foreground" to data)
            "status" -> data + ("status_ms" to JsonPrimitive(nowMs))
            else -> data
        }

    companion object {

        fun fromSnapshot(result: JsonObject): TerminalTable? {
            val seq = result["seq"]?.jsonPrimitive?.longOrNull ?: return null
            val terminals = result["terminals"] as? JsonArray ?: return null
            val raw =
                terminals
                    .map { it.jsonObject }
                    .filter { it["state"]?.jsonPrimitive?.contentOrNull != "finished" }
                    .mapNotNull { obj ->
                        obj["address"]?.jsonPrimitive?.contentOrNull?.takeIf(::isLocal)?.let {
                            it to obj
                        }
                    }
                    .toMap()
            return TerminalTable(seq, raw)
        }

        private fun isLocal(address: String) = '/' !in address
    }
}
