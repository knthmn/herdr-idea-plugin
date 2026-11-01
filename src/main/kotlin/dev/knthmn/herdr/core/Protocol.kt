package dev.knthmn.herdr.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val HcJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
data class Terminal(
    val address: String,
    val name: String? = null,
    val title: String? = null,
    val cwd: String = "",
    val foreground: Foreground? = null,
    val parent: String? = null,
    val state: String = "live",
    val status: Status? = null,
    @SerialName("status_ms") val statusMs: Long? = null,
    @SerialName("created_ms") val createdMs: Long? = null,
)

@Serializable
data class Foreground(
    val name: String? = null,
    val cwd: String? = null,
    val argv: List<String> = emptyList(),
)

@Serializable
data class Status(
    val state: String,
    val msg: String? = null,
    val kind: String? = null,
    val app: String? = null,
)
