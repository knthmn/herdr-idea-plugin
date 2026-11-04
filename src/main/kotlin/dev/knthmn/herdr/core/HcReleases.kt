package dev.knthmn.herdr.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.io.HttpRequests
import dev.knthmn.herdr.settings.HerdrSettings
import java.io.IOException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The latest herdr-core release, from the release server, cached in the settings. */
@Service(Service.Level.APP)
class HcReleases {
    /** The latest release, or null when the release server cannot be read. */
    suspend fun latest(force: Boolean = false): HcVersion? =
        withContext(Dispatchers.IO) {
            val settings = HerdrSettings.getInstance()
            val now = System.currentTimeMillis()
            val cached = HcVersion.parse(settings.latestVersion)
            if (!force && cached != null && isFresh(settings.lastCheckMillis, now)) {
                return@withContext cached
            }
            fetch()?.also {
                settings.latestVersion = it.toString()
                settings.lastCheckMillis = now
            }
        }

    private fun fetch(): HcVersion? =
        try {
            val text =
                HttpRequests.request(LATEST_URL)
                    .connectTimeout(TIMEOUT_MS)
                    .readTimeout(TIMEOUT_MS)
                    .readString()
            HcJson.parseToJsonElement(text)
                .jsonObject["version"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.let(HcVersion::parse)
        } catch (e: IOException) {
            thisLogger().info("Cannot read $LATEST_URL: ${e.message}")
            null
        } catch (e: SerializationException) {
            thisLogger().warn("Unreadable $LATEST_URL", e)
            null
        } catch (e: IllegalArgumentException) {
            thisLogger().warn("Unexpected $LATEST_URL", e)
            null
        }

    companion object {
        const val LATEST_URL = "https://releases.herdr.dev/core/latest.json"
        val CHECK_INTERVAL = 3.hours
        private val TIMEOUT_MS = 10.seconds.inWholeMilliseconds.toInt()

        fun isFresh(checkedAt: Long, now: Long): Boolean =
            checkedAt in (now - CHECK_INTERVAL.inWholeMilliseconds)..now

        fun getInstance(): HcReleases = service()
    }
}
