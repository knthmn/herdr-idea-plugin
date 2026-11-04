package dev.knthmn.herdr.core

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.EnvironmentUtil
import com.intellij.util.io.HttpRequests
import java.io.IOException
import kotlin.time.Duration.Companion.minutes

/** Installs or updates herdr-core with the official install script. */
object HcInstaller {
    const val SCRIPT_URL = "https://releases.herdr.dev/core/install.sh"
    private val TIMEOUT = 5.minutes
    private const val ERROR_LINES = 5

    /** Runs the install script and returns its last line. Throws [HcException] on failure. */
    fun install(indicator: ProgressIndicator): String {
        val script = FileUtil.createTempFile("herdr-core-install", ".sh", true)
        try {
            indicator.text = "Downloading the herdr-core installer"
            try {
                HttpRequests.request(SCRIPT_URL).saveToFile(script, indicator)
            } catch (e: IOException) {
                throw HcException("download", "Cannot download $SCRIPT_URL: ${e.message}", e)
            }
            indicator.text = "Running the herdr-core installer"
            val command =
                GeneralCommandLine("/bin/sh", script.path)
                    .withEnvironment(EnvironmentUtil.getEnvironmentMap())
            val output =
                CapturingProcessHandler(command)
                    .runProcessWithProgressIndicator(indicator, TIMEOUT.inWholeMilliseconds.toInt())
            failure(output)?.let { throw it }
            return output.stdoutLines.lastOrNull { it.startsWith("hc ") } ?: "herdr-core installed"
        } finally {
            FileUtil.delete(script)
        }
    }

    private fun failure(output: ProcessOutput): HcException? =
        when {
            output.isCancelled -> HcException("cancelled", "The install was cancelled")
            output.isTimeout -> HcException("timeout", "The installer did not finish")
            output.exitCode != 0 ->
                HcException(
                    "exit",
                    (output.stderrLines + output.stdoutLines)
                        .filter { it.isNotBlank() }
                        .takeLast(ERROR_LINES)
                        .joinToString("\n")
                        .ifEmpty { "The installer exited ${output.exitCode}" },
                )
            else -> null
        }
}
