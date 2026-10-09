package dev.anydm.desktop.chrome

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private fun glyph(
    name: String,
    draw: PathBuilder.() -> Unit,
): ImageVector =
    ImageVector
        .Builder(name, 16.dp, 16.dp, 16f, 16f)
        .path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = draw,
        ).build()

/** The few icons the chrome needs, drawn as 16-unit strokes and tinted by `Icon`. */
object Glyphs {
    val Add =
        glyph("add") {
            moveTo(8f, 3f)
            lineTo(8f, 13f)
            moveTo(3f, 8f)
            lineTo(13f, 8f)
        }

    /** Three lines with bullets: many links. */
    val Many =
        glyph("many") {
            moveTo(6f, 4f)
            lineTo(13f, 4f)
            moveTo(6f, 8f)
            lineTo(13f, 8f)
            moveTo(6f, 12f)
            lineTo(13f, 12f)
            moveTo(3f, 4f)
            lineTo(3.01f, 4f)
            moveTo(3f, 8f)
            lineTo(3.01f, 8f)
            moveTo(3f, 12f)
            lineTo(3.01f, 12f)
        }
    val Pause =
        glyph("pause") {
            moveTo(5.5f, 3.5f)
            lineTo(5.5f, 12.5f)
            moveTo(10.5f, 3.5f)
            lineTo(10.5f, 12.5f)
        }
    val Resume =
        glyph("resume") {
            moveTo(5f, 3f)
            lineTo(13f, 8f)
            lineTo(5f, 13f)
            close()
        }

    /** A magnifying glass: search. */
    val Search =
        glyph("search") {
            moveTo(7f, 3f)
            arcTo(4f, 4f, 0f, false, true, 7f, 11f)
            arcTo(4f, 4f, 0f, false, true, 7f, 3f)
            moveTo(10f, 10f)
            lineTo(13.5f, 13.5f)
        }

    /** A page with a downward arrow: open a `.torrent`. */
    val Torrent =
        glyph("torrent") {
            moveTo(4f, 2f)
            lineTo(10f, 2f)
            lineTo(13f, 5f)
            lineTo(13f, 14f)
            lineTo(4f, 14f)
            close()
            moveTo(8.5f, 6f)
            lineTo(8.5f, 11f)
            moveTo(6.5f, 9f)
            lineTo(8.5f, 11f)
            lineTo(10.5f, 9f)
        }

    /** Three sliders: settings. */
    val Settings =
        glyph("settings") {
            moveTo(2.5f, 4f)
            lineTo(13.5f, 4f)
            moveTo(2.5f, 8f)
            lineTo(13.5f, 8f)
            moveTo(2.5f, 12f)
            lineTo(13.5f, 12f)
            moveTo(10f, 2.5f)
            lineTo(10f, 5.5f)
            moveTo(5f, 6.5f)
            lineTo(5f, 9.5f)
            moveTo(8.5f, 10.5f)
            lineTo(8.5f, 13.5f)
        }
    val ChevronDown =
        glyph("chevron-down") {
            moveTo(4f, 6f)
            lineTo(8f, 10f)
            lineTo(12f, 6f)
        }

    val Remove =
        glyph("remove") {
            moveTo(4f, 4f)
            lineTo(12f, 12f)
            moveTo(12f, 4f)
            lineTo(4f, 12f)
        }

    /** An arrow into a tray: save. */
    val Save =
        glyph("save") {
            moveTo(8f, 2.5f)
            lineTo(8f, 10f)
            moveTo(5f, 7f)
            lineTo(8f, 10f)
            lineTo(11f, 7f)
            moveTo(3f, 11f)
            lineTo(3f, 13.5f)
            lineTo(13f, 13.5f)
            lineTo(13f, 11f)
        }

    /** A folder with an arrow into it: move to a category. */
    val Move =
        glyph("move") {
            moveTo(2.5f, 4f)
            lineTo(6f, 4f)
            lineTo(7.5f, 5.5f)
            lineTo(13.5f, 5.5f)
            lineTo(13.5f, 12.5f)
            lineTo(2.5f, 12.5f)
            lineTo(2.5f, 4f)
            moveTo(5.5f, 9f)
            lineTo(9f, 9f)
            moveTo(7.5f, 7.5f)
            lineTo(9f, 9f)
            lineTo(7.5f, 10.5f)
        }

    /** A circling arrow: retry. */
    val Retry =
        glyph("retry") {
            moveTo(12.5f, 8f)
            arcTo(4.5f, 4.5f, 0f, true, true, 10.9f, 4.6f)
            moveTo(11f, 2f)
            lineTo(11f, 4.8f)
            lineTo(8.2f, 4.8f)
        }
    val ChevronRight =
        glyph("chevron-right") {
            moveTo(6f, 4f)
            lineTo(10f, 8f)
            lineTo(6f, 12f)
        }
}
