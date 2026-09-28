package dev.anydm.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter

/** The tray's icon: a ring with a downward arrow. Drawn, so no image file is needed yet. */
object TrayIcon : Painter() {
    override val intrinsicSize = Size(64f, 64f)

    override fun DrawScope.onDraw() {
        val stroke = size.minDimension / 10
        val colour = Color(0xFF2563EB)
        drawCircle(colour, radius = size.minDimension / 2 - stroke, style = Stroke(stroke))
        val cx = size.width / 2
        drawLine(colour, Offset(cx, size.height * 0.25f), Offset(cx, size.height * 0.68f), stroke)
        drawLine(colour, Offset(cx - size.width * 0.18f, size.height * 0.5f), Offset(cx, size.height * 0.68f), stroke)
        drawLine(colour, Offset(cx + size.width * 0.18f, size.height * 0.5f), Offset(cx, size.height * 0.68f), stroke)
    }
}
