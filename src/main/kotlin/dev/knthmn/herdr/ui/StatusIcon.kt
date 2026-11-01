package dev.knthmn.herdr.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.knthmn.herdr.model.AgentState
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import javax.swing.Icon

/**
 * Status marks: filled when the agent needs you (blocked, error, unread), hollow when it does not
 * (running). Blocked is a diamond so that it differs from error by shape as well as color. Idle and
 * terminals without a status get no mark.
 */
private enum class Mark {
    DOT,
    RING,
    DIAMOND,
}

private class StatusMark(private val mark: Mark, private val color: Color) {
    fun paint(g: Graphics, x: Int, y: Int, size: Int, halo: Color) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val shape = shape(x.toDouble(), y.toDouble(), size.toDouble())
            g2.color = halo
            g2.stroke = BasicStroke(JBUI.scale(HALO).toFloat() * 2)
            g2.draw(shape)
            if (mark == Mark.RING) {
                g2.fill(shape)
                val stroke = JBUI.scale(RING_STROKE).toFloat()
                g2.color = color
                g2.stroke = BasicStroke(stroke)
                val inset = stroke / 2
                g2.draw(shape(x + inset.toDouble(), y + inset.toDouble(), size - stroke.toDouble()))
            } else {
                g2.color = color
                g2.fill(shape)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun shape(x: Double, y: Double, size: Double): Shape =
        when (mark) {
            Mark.DOT,
            Mark.RING -> Ellipse2D.Double(x, y, size, size)
            Mark.DIAMOND ->
                Path2D.Double().apply {
                    val half = size / 2
                    moveTo(x + half, y)
                    lineTo(x + size, y + half)
                    lineTo(x + half, y + size)
                    lineTo(x, y + half)
                    closePath()
                }
        }

    companion object {
        private const val HALO = 1
        private const val RING_STROKE = 2
    }
}

private class BadgedIcon(private val base: Icon, private val mark: StatusMark) : Icon {
    override fun getIconWidth(): Int = base.iconWidth

    override fun getIconHeight(): Int = base.iconHeight

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        base.paintIcon(c, g, x, y)
        val size = JBUI.scale(BADGE)
        mark.paint(g, x + iconWidth - size, y + iconHeight - size, size, UIUtil.getTreeBackground())
    }

    companion object {
        private const val BADGE = 8
    }
}

object StatusIcon {
    private val ORANGE = JBColor(0xE66D17, 0xE08855)
    private val RED = JBColor(0xDB3B4B, 0xDB5C5C)
    private val BLUE = JBColor(0x3574F0, 0x548AF7)
    private val GREEN = JBColor(0x208A3C, 0x57965C)

    private val marks =
        mapOf(
            AgentState.BLOCKED to StatusMark(Mark.DIAMOND, ORANGE),
            AgentState.ERROR to StatusMark(Mark.DOT, RED),
            AgentState.UNREAD to StatusMark(Mark.DOT, BLUE),
            AgentState.RUNNING to StatusMark(Mark.RING, GREEN),
        )

    /**
     * [base] with a status mark in its bottom-right corner; no mark when there is nothing to say.
     */
    fun badged(base: Icon, state: AgentState): Icon =
        marks[state]?.let { BadgedIcon(base, it) } ?: base
}
