package dev.knthmn.herdr.core

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.EnvironmentUtil
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed interface HcHealth {
    data object Missing : HcHealth

    data class Unsupported(val hc: Path, val version: HcVersion) : HcHealth

    /** [version] is null when hc prints no version that the plugin can read. */
    data class Usable(val hc: Path, val version: HcVersion?) : HcHealth
}

object HcLocator {
    const val INSTALL_DIR_HINT = "~/.local/bin"
    const val DEFAULT_PATH_HINT = "$INSTALL_DIR_HINT/hc"
    private const val VERSION_TIMEOUT_S = 5L

    private val versions = ConcurrentHashMap<Path, Pair<Long, HcVersion?>>()

    fun locate(configured: String): Path? =
        if (configured.isNotBlank()) {
            expandHome(configured.trim()).takeIf(::isExecutable)
        } else {
            findInPath()
                ?: expandHome(DEFAULT_PATH_HINT).takeIf(::isExecutable)
                ?: installDir().resolve("herdr-core").takeIf(::isExecutable)
        }

    /** The folder that the official installer writes. */
    fun installDir(): Path = expandHome(INSTALL_DIR_HINT)

    /** True when [hc] is in [dir], the folder of the official installer, so it can update it. */
    fun isManaged(hc: Path, dir: Path = installDir()): Boolean {
        val folder = dir.toAbsolutePath().normalize()
        return hc.toAbsolutePath().normalize().parent == folder ||
            realPath(hc)?.parent?.let { it == realPath(folder) } == true
    }

    /** The hc that [configured] or the default search finds, and whether the plugin can use it. */
    fun health(configured: String): HcHealth {
        val hc = locate(configured) ?: return HcHealth.Missing
        val version = version(hc)
        return if (version != null && version < HcVersion.MINIMUM) HcHealth.Unsupported(hc, version)
        else HcHealth.Usable(hc, version)
    }

    /** The version that `hc --version` prints. Null when it prints no version. */
    fun version(hc: Path): HcVersion? {
        val real = realPath(hc) ?: return null
        val modified =
            try {
                Files.getLastModifiedTime(real).toMillis()
            } catch (_: IOException) {
                return null
            }
        versions[real]
            ?.takeIf { it.first == modified }
            ?.let {
                return it.second
            }
        val version = readVersion(hc)
        versions[real] = modified to version
        return version
    }

    private fun readVersion(hc: Path): HcVersion? =
        try {
            val process =
                ProcessBuilder(hc.toString(), "--version").redirectErrorStream(true).start()
            process.outputStream.close()
            if (!process.waitFor(VERSION_TIMEOUT_S, TimeUnit.SECONDS)) {
                process.destroy()
                null
            } else {
                val output = process.inputReader().readText()
                HcVersion.parse(output).also {
                    if (it == null) logger<HcLocator>().warn("Unknown hc version: $output")
                }
            }
        } catch (e: IOException) {
            logger<HcLocator>().warn("Cannot read the hc version", e)
            null
        }

    private fun realPath(path: Path): Path? =
        try {
            path.toRealPath()
        } catch (_: IOException) {
            null
        }

    private fun findInPath(): Path? =
        EnvironmentUtil.getValue("PATH")
            ?.split(File.pathSeparator)
            ?.filter { it.isNotEmpty() }
            ?.map { Path.of(it, "hc") }
            ?.firstOrNull(::isExecutable)

    fun expandHome(path: String): Path =
        if (path == "~" || path.startsWith("~/")) {
            Path.of(System.getProperty("user.home") + path.removePrefix("~"))
        } else {
            Path.of(path)
        }

    fun abbreviateHome(path: String): String {
        val home = System.getProperty("user.home")
        return if (path == home || path.startsWith("$home/")) "~" + path.removePrefix(home)
        else path
    }

    private fun isExecutable(path: Path): Boolean =
        Files.isRegularFile(path) && Files.isExecutable(path)
}
