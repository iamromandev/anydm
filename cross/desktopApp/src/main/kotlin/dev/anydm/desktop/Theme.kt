package dev.anydm.desktop

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The corner radius Plexo's buttons, cards and menus share. */
val PlexoRadius = 8.dp

/** The family names the reference uses; the fonts themselves are bundled in `resources/fonts`. */
object PlexoFontNames {
    const val SANS = "Manrope"
    const val MONO = "Roboto Mono"
}

/** One color a user can give a network, with the color of a glyph drawn on top of it. */
@Immutable
data class Swatch(
    val id: String,
    val label: String,
    val solid: Color,
    val onSolid: Color,
)

/** What a kind of network looks like: its solid color and the tinted surface, border and text that go with it. */
@Immutable
data class KindColors(
    val solid: Color,
    val bg: Color,
    val border: Color,
    val text: Color,
    val onSolid: Color,
)

/** Every color token of one theme, named as the reference names them. */
@Immutable
class PlexoColors(
    val dark: Boolean,
    val bg: Color,
    val bgSecondary: Color,
    val bgTertiary: Color,
    val inputBg: Color,
    val trackBg: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val border: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    val footerBorder: Color,
    val iconMuted: Color,
    val iconMutedStrong: Color,
    val heroTop: Color,
    val heroBottom: Color,
    val heroBorder: Color,
    val checkboxShadow: Color,
    val checkboxInsetShadow: Color,
    val scrollbarThumb: Color,
    val accent: Color,
    val accentOnSolid: Color,
    val success: Color,
    val danger: Color,
    val dangerBg: Color,
    val dangerBorder: Color,
    val nodeAccent: Color,
    val wifi: KindColors,
    val usb: KindColors,
    val ethernet: KindColors,
    val neutral: KindColors,
    val swatches: List<Swatch>,
)

private fun hex(value: Long) = Color(0xFF000000 or value)

private fun kind(
    solid: Long,
    bg: Long,
    border: Long,
    text: Long,
    onSolid: Long,
) = KindColors(hex(solid), hex(bg), hex(border), hex(text), hex(onSolid))

private fun swatches(vararg entries: Pair<Int, Int>): List<Swatch> {
    val names = listOf("teal", "amber", "steel", "rose", "violet", "lime", "cyan", "coral")
    return names.mapIndexed { index, name ->
        Swatch(name, name.replaceFirstChar { it.uppercase() }, hex(entries[index].first.toLong()), hex(entries[index].second.toLong()))
    }
}

val DarkColors =
    PlexoColors(
        dark = true,
        bg = hex(0x1B1E20),
        bgSecondary = hex(0x202325),
        bgTertiary = hex(0x202325),
        inputBg = hex(0x242729),
        trackBg = hex(0x262A2D),
        text = hex(0xEAE7E2),
        textSecondary = hex(0xA9ADB2),
        textTertiary = hex(0x8D9196),
        border = hex(0x2B2F33),
        borderSubtle = hex(0x23262A),
        borderStrong = hex(0x33383C),
        footerBorder = hex(0x2B2F33),
        iconMuted = hex(0x33383C),
        iconMutedStrong = hex(0x3A3F44),
        heroTop = hex(0x202325),
        heroBottom = hex(0x1B1E20),
        heroBorder = hex(0x2B2F33),
        checkboxShadow = Color(0f, 0f, 0f, 0.4f),
        checkboxInsetShadow = Color(0f, 0f, 0f, 0.3f),
        scrollbarThumb = hex(0x3A3F44),
        accent = hex(0xD8A44C),
        accentOnSolid = hex(0x1B1408),
        success = hex(0x4EA89A),
        danger = hex(0xEDA3A3),
        dangerBg = hex(0x2C1C1C),
        dangerBorder = hex(0x4A2A2A),
        nodeAccent = hex(0xF7F4EF),
        wifi = kind(0x4EA89A, 0x22312E, 0x3A5450, 0x7FC9BB, 0x10201D),
        usb = kind(0xD8A44C, 0x33291A, 0x54421F, 0xE8BB6D, 0x221806),
        ethernet = kind(0x7E93BD, 0x232A38, 0x3A4660, 0xA7B8DB, 0x141A26),
        neutral = kind(0x8D9196, 0x26292C, 0x3A3F44, 0xA9ADB2, 0x16181A),
        swatches =
            swatches(
                0x4EA89A to 0x10201D,
                0xD8A44C to 0x221806,
                0x7E93BD to 0x141A26,
                0xC97B7B to 0x210F0F,
                0x9C8FD6 to 0x17131F,
                0xA3C66A to 0x161F0D,
                0x6DB8C9 to 0x0D1A1E,
                0xD99168 to 0x241209,
            ),
    )

val LightColors =
    PlexoColors(
        dark = false,
        bg = hex(0xFFFFFF),
        bgSecondary = hex(0xFAFAFA),
        bgTertiary = hex(0xF0F0F0),
        inputBg = hex(0xFFFFFF),
        trackBg = hex(0xE4E4E6),
        text = hex(0x1D1D1F),
        textSecondary = hex(0x6E6E73),
        textTertiary = hex(0x737377),
        border = hex(0xE0E0E2),
        borderSubtle = hex(0xECECEE),
        borderStrong = hex(0xB9B9BB),
        footerBorder = hex(0xD8D8DA),
        iconMuted = hex(0xC2C2C6),
        iconMutedStrong = hex(0xB0B0B2),
        heroTop = hex(0xFBFBFC),
        heroBottom = hex(0xF4F5F7),
        heroBorder = hex(0xE2E4E8),
        checkboxShadow = Color(0f, 0f, 0f, 0.15f),
        checkboxInsetShadow = Color(0f, 0f, 0f, 0.04f),
        scrollbarThumb = hex(0xD0D0D2),
        accent = hex(0xD97706),
        accentOnSolid = hex(0xFFFFFF),
        success = hex(0x1F8A70),
        danger = hex(0xDC2626),
        dangerBg = hex(0xFEF2F2),
        dangerBorder = hex(0xFECACA),
        nodeAccent = hex(0x5F6E78),
        wifi = kind(0x1F8A70, 0xE6F6F2, 0xB8E6DC, 0x0E6B54, 0xFFFFFF),
        usb = kind(0xD97706, 0xFEF3C7, 0xFDE68A, 0x92400E, 0xFFFFFF),
        ethernet = kind(0x4F64A5, 0xEDF0F8, 0xCBD3ED, 0x374984, 0xFFFFFF),
        neutral = kind(0x6B7280, 0xF3F4F6, 0xE5E7EB, 0x4B5563, 0xFFFFFF),
        swatches =
            swatches(
                0x1F8A70 to 0xFFFFFF,
                0xD97706 to 0xFFFFFF,
                0x4F64A5 to 0xFFFFFF,
                0xB5525A to 0xFFFFFF,
                0x6C5AC4 to 0xFFFFFF,
                0x5B8A1E to 0xFFFFFF,
                0x0E7D93 to 0xFFFFFF,
                0xC4572A to 0xFFFFFF,
            ),
    )

fun colorsOf(choice: ThemeChoice): PlexoColors = if (choice == ThemeChoice.DARK) DarkColors else LightColors

/** The bundled fonts: Manrope for text, Roboto Mono for figures and labels. */
@Immutable
class PlexoFonts(
    val sans: FontFamily,
    val mono: FontFamily,
)

// One variable file serves every weight: each entry sets the file's weight axis.
@OptIn(ExperimentalTextApi::class)
private fun bundled(path: String) =
    FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map {
            Font(path, weight = it, variationSettings = FontVariation.Settings(FontVariation.weight(it.weight)))
        },
    )

val DefaultFonts =
    PlexoFonts(
        sans = bundled("fonts/Manrope-Variable.woff2"),
        mono = bundled("fonts/RobotoMono-Variable.woff2"),
    )

/** The text styles the screens use, in the reference's sizes. */
@Immutable
class PlexoType(
    fonts: PlexoFonts,
) {
    val title = TextStyle(fontFamily = fonts.sans, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    val menu = TextStyle(fontFamily = fonts.sans, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val rowName = TextStyle(fontFamily = fonts.sans, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontFamily = fonts.sans, fontSize = 13.sp)
    val button = TextStyle(fontFamily = fonts.sans, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val hint = TextStyle(fontFamily = fonts.sans, fontSize = 12.5.sp)
    val detail = TextStyle(fontFamily = fonts.mono, fontSize = 11.5.sp)
    val speed = TextStyle(fontFamily = fonts.mono, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    val badge = TextStyle(fontFamily = fonts.mono, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    val groupLabel = TextStyle(fontFamily = fonts.mono, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.9.sp)
    val footer = TextStyle(fontFamily = fonts.mono, fontSize = 11.5.sp)
}

private val LocalColors = compositionLocalOf { DarkColors }
private val LocalType = compositionLocalOf { PlexoType(DefaultFonts) }

/** The tokens of the theme in force. */
object Plexo {
    val colors: PlexoColors
        @Composable @ReadOnlyComposable
        get() = LocalColors.current

    val type: PlexoType
        @Composable @ReadOnlyComposable
        get() = LocalType.current
}

@Composable
fun PlexoTheme(
    choice: ThemeChoice,
    content: @Composable () -> Unit,
) {
    val type = remember { PlexoType(DefaultFonts) }
    CompositionLocalProvider(LocalColors provides colorsOf(choice), LocalType provides type, content = content)
}

/** Text in the theme's colors; one line cut with an ellipsis unless [maxLines] says otherwise. */
@Composable
fun Txt(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Plexo.colors.text,
    maxLines: Int = 1,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
