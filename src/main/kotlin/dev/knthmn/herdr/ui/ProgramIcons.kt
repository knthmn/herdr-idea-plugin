package dev.knthmn.herdr.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.IconUtil
import dev.knthmn.herdr.model.Program
import java.awt.Component
import java.awt.Graphics
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

/** Icons from the Herdr app's program table. One-color glyphs follow the theme's icon color. */
object ProgramIcons {
    private val cache = ConcurrentHashMap<String, Icon>()

    fun of(program: Program): Icon {
        val path = program.icon ?: return AllIcons.Nodes.Console
        return cache.getOrPut("${program.tint}:$path") {
            val icon = IconLoader.getIcon("/herdr/$path", ProgramIcons::class.java)
            if (program.tint) TintedIcon(icon) else icon
        }
    }
}

private class TintedIcon(private val base: Icon) : Icon {
    private val tinted = ConcurrentHashMap<Boolean, Icon>()

    override fun getIconWidth(): Int = base.iconWidth

    override fun getIconHeight(): Int = base.iconHeight

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val bright = JBColor.isBright()
        tinted
            .getOrPut(bright) { IconUtil.colorize(base, if (bright) LIGHT else DARK) }
            .paintIcon(c, g, x, y)
    }

    companion object {
        private val LIGHT = java.awt.Color(0x6C707E)
        private val DARK = java.awt.Color(0xCED0D6)
    }
}
