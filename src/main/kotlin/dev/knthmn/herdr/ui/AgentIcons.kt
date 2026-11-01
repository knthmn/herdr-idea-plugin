package dev.knthmn.herdr.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object AgentIcons {
    val PI: Icon = IconLoader.getIcon("/icons/pi.svg", AgentIcons::class.java)

    fun of(agent: String?): Icon =
        when (agent) {
            "pi" -> PI
            else -> AllIcons.Nodes.Console
        }
}
