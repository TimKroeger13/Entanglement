package app.entanglement.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val Violet = Color(0xFF7C5CFF)
val Teal = Color(0xFF00B8BE)
val Amber = Color(0xFFE08A00)

private val Light = lightColorScheme(
    primary = Color(0xFF5B3FD9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1C0062),
    secondary = Color(0xFF00777B),
    background = Color(0xFFF8F7FC),
    surface = Color(0xFFF8F7FC),
    surfaceContainer = Color(0xFFFFFFFF),
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
    background = Color(0xFF131218),
    surface = Color(0xFF131218),
    surfaceContainer = Color(0xFF1C1B22),
    surfaceVariant = Color(0xFF2A2833),
    onSurfaceVariant = Color(0xFFCAC5D6),
    outlineVariant = Color(0xFF3C3947),
)

@Composable
fun EntanglementTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** The app symbol: two interlocked rings (solid colors only – gradients crash the emulator's software GPU). */
@Composable
fun Rings(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val r = s * 0.27f
        val w = s * 0.095f
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val left = Offset(c.x - s * 0.15f, c.y)
        val right = Offset(c.x + s * 0.15f, c.y)
        val stroke = Stroke(width = w)
        drawCircle(Violet, radius = r - w / 2, center = left, style = stroke)
        drawCircle(Teal, radius = r - w / 2, center = right, style = stroke)
        clipRect(c.x - s * 0.12f, c.y, c.x + s * 0.12f, this.size.height) {
            drawCircle(Violet, radius = r - w / 2, center = left, style = stroke)
        }
    }
}

/** The few icons the app needs (Material icon shapes), without the large icon library. */
object Icons {
    val Back = icon("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
    val More = icon(
        "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 " +
            "2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z",
    )
    val Add = icon("M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")

    private fun icon(path: String) = ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()
}
