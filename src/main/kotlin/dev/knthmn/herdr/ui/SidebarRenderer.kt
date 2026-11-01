package dev.knthmn.herdr.ui

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import dev.knthmn.herdr.model.AgentState
import dev.knthmn.herdr.model.Row
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class SidebarRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val item = (value as? DefaultMutableTreeNode)?.userObject as? Row ?: return
        icon = StatusIcon.badged(AgentIcons.of(item.agent), item.state)
        val emphasis =
            when (item.state) {
                AgentState.UNREAD,
                AgentState.BLOCKED,
                AgentState.ERROR -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
            }
        append(item.label, emphasis)
        if (item.detail.isNotEmpty()) {
            append("  ${item.detail}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        item.sinceMs?.let {
            append(
                "  ${age((System.currentTimeMillis() - it).milliseconds)}",
                SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
            )
        }
        toolTipText =
            listOfNotNull(
                    item.state.text,
                    item.message?.takeIf { it.isNotBlank() },
                    "${item.address} · ${item.cwd}",
                )
                .joinToString("<br>", "<html>", "</html>") { StringUtil.escapeXmlEntities(it) }
    }

    private fun age(duration: Duration): String =
        when {
            duration.inWholeMinutes < 1 -> "<1m"
            duration.inWholeHours < 1 -> "${duration.inWholeMinutes}m"
            duration.inWholeDays < 1 -> "${duration.inWholeHours}h"
            else -> "${duration.inWholeDays}d"
        }
}
