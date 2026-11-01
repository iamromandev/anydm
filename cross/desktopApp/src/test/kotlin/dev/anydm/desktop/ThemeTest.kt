package dev.anydm.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ThemeTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the theme says whether it is dark`() {
        val seen = mutableListOf<Boolean>()
        rule.setContent {
            AnydmTheme(dark = true) { seen += LocalDark.current }
            AnydmTheme(dark = false) { seen += LocalDark.current }
        }
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun `every protocol has a distinct badge in both schemes`() {
        listOf(false, true).forEach { dark ->
            val badges = Protocol.entries.map { ProtocolColors.badge(it, dark) }
            assertEquals(Protocol.entries.size, badges.map { it.container }.toSet().size)
            badges.forEach { assertNotEquals(it.container, it.content) }
        }
    }

    @Test
    fun `every status group has a color in both schemes`() {
        StatusGroup.entries.forEach {
            assertNotEquals(ProtocolColors.status(it, dark = false), ProtocolColors.status(it, dark = true))
        }
    }

    private fun roles(scheme: ColorScheme): Map<String, Color> =
        mapOf(
            "secondary" to scheme.secondary,
            "onSecondary" to scheme.onSecondary,
            "secondaryContainer" to scheme.secondaryContainer,
            "onSecondaryContainer" to scheme.onSecondaryContainer,
            "surfaceContainerLowest" to scheme.surfaceContainerLowest,
            "surfaceContainerLow" to scheme.surfaceContainerLow,
            "surfaceContainer" to scheme.surfaceContainer,
            "surfaceContainerHigh" to scheme.surfaceContainerHigh,
            "surfaceContainerHighest" to scheme.surfaceContainerHighest,
            "outline" to scheme.outline,
            "outlineVariant" to scheme.outlineVariant,
        )

    private fun schemes(): Pair<ColorScheme, ColorScheme> {
        lateinit var light: ColorScheme
        lateinit var dark: ColorScheme
        rule.setContent {
            AnydmTheme(dark = false) { light = MaterialTheme.colorScheme }
            AnydmTheme(dark = true) { dark = MaterialTheme.colorScheme }
        }
        return light to dark
    }

    @Test
    fun `no role is left at the material baseline`() {
        val (light, dark) = schemes()
        val lightBase = roles(lightColorScheme())
        val darkBase = roles(darkColorScheme())
        roles(light).forEach { (name, color) -> assertNotEquals(lightBase.getValue(name), color, "light $name") }
        roles(dark).forEach { (name, color) -> assertNotEquals(darkBase.getValue(name), color, "dark $name") }
    }

    @Test
    fun `the light and dark schemes differ in every such role`() {
        val (light, dark) = schemes()
        val darkRoles = roles(dark)
        roles(light).forEach { (name, color) -> assertNotEquals(darkRoles.getValue(name), color, name) }
    }
}
