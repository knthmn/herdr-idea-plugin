package dev.knthmn.herdr.core

import com.intellij.util.EnvironmentUtil
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

object HcLocator {
    const val DEFAULT_PATH_HINT = "~/.local/bin/hc"

    fun locate(configured: String): Path? =
        if (configured.isNotBlank()) {
            expandHome(configured.trim()).takeIf(::isExecutable)
        } else {
            findInPath() ?: expandHome(DEFAULT_PATH_HINT).takeIf(::isExecutable)
        }

    private fun findInPath(): Path? =
        EnvironmentUtil.getValue("PATH")
            ?.split(File.pathSeparator)
            ?.filter { it.isNotEmpty() }
            ?.map { Path.of(it, "hc") }
            ?.firstOrNull(::isExecutable)

    private fun expandHome(path: String): Path =
        if (path == "~" || path.startsWith("~/")) {
            Path.of(System.getProperty("user.home") + path.removePrefix("~"))
        } else {
            Path.of(path)
        }

    private fun isExecutable(path: Path): Boolean =
        Files.isRegularFile(path) && Files.isExecutable(path)
}
