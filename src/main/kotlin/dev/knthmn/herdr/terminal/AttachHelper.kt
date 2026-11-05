package dev.knthmn.herdr.terminal

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.diagnostic.thisLogger
import java.nio.file.Files
import java.nio.file.Path

/**
 * The bundled `herdr-attach` binary. It runs `hc attach` in its own pty and removes kitty graphics
 * from the output, which the IDE terminal shows as text.
 */
object AttachHelper {
    private const val NAME = "herdr-attach"

    /** The command that attaches to [address], through the helper when it exists. */
    fun attachCommand(hc: Path, address: String): List<String> =
        listOfNotNull(locate()?.toString(), hc.toString(), "attach", address)

    fun platform(osName: String, osArch: String): String? {
        val os =
            when {
                osName.startsWith("Mac", ignoreCase = true) -> "darwin"
                osName.startsWith("Linux", ignoreCase = true) -> "linux"
                else -> return null
            }
        val arch =
            when (osArch.lowercase()) {
                "aarch64",
                "arm64" -> "aarch64"
                "amd64",
                "x86_64" -> "x86_64"
                else -> return null
            }
        return "$os-$arch"
    }

    private fun locate(): Path? {
        val root =
            (AttachHelper::class.java.classLoader as? PluginAwareClassLoader)
                ?.pluginDescriptor
                ?.pluginPath ?: return null
        val platform =
            platform(System.getProperty("os.name"), System.getProperty("os.arch")) ?: return null
        val file = root.resolve("bin").resolve(platform).resolve(NAME)
        if (!Files.isRegularFile(file)) {
            thisLogger().info("No $NAME for $platform, attaching without it")
            return null
        }
        if (!Files.isExecutable(file) && !file.toFile().setExecutable(true)) {
            thisLogger().warn("Cannot make $file executable, attaching without it")
            return null
        }
        return file
    }
}
