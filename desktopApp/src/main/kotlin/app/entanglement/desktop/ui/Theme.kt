package app.entanglement.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.painter.Painter

val Violet = Color(0xFF7C5CFF)
val Teal = Color(0xFF00B8BE)

private val Light = lightColorScheme(
    primary = Color(0xFF5B3FD9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1C0062),
    secondary = Color(0xFF00777B),
    secondaryContainer = Color(0xFFC9F3F4),
    onSecondaryContainer = Color(0xFF002021),
    background = Color(0xFFF8F7FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDEAF5),
    onSurfaceVariant = Color(0xFF4A4658),
    outlineVariant = Color(0xFFD7D3E3),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFC9BEFF),
    onPrimary = Color(0xFF2E0E99),
    primaryContainer = Color(0xFF452ABF),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFF4FD8DD),
    secondaryContainer = Color(0xFF00494C),
    onSecondaryContainer = Color(0xFFC9F3F4),
    background = Color(0xFF131218),
    surface = Color(0xFF1C1B22),
    surfaceVariant = Color(0xFF2A2833),
    onSurfaceVariant = Color(0xFFCAC5D6),
    outlineVariant = Color(0xFF3C3947),
)

@Composable
fun EntanglementTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** The app icon: two interlocked rings (same drawing as icons/make_icon.py). */
class RingsPainter : Painter() {
    override val intrinsicSize = Size(64f, 64f)

    override fun DrawScope.onDraw() {
        val s = size.minDimension
        val r = s * 0.27f
        val w = s * 0.095f
        val dx = s * 0.15f
        val c = Offset(size.width / 2, size.height / 2)
        val stroke = Stroke(width = w)
        val left = Offset(c.x - dx, c.y)
        val right = Offset(c.x + dx, c.y)
        // Ellipse box of the stroke centre line.
        fun ring(center: Offset, color: Color) =
            drawCircle(color, radius = r - w / 2, center = center, style = stroke)

        ring(left, Violet)
        ring(right, Teal)
        // Violet back on top at the lower crossing: the rings interlock.
        val crossing = Rect(c.x - s * 0.12f, c.y, c.x + s * 0.12f, size.height)
        clipRect(crossing.left, crossing.top, crossing.right, crossing.bottom) { ring(left, Violet) }
    }
}
