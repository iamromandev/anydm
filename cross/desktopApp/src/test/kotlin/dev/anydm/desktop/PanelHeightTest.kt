package dev.anydm.desktop

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class PanelHeightTest {
    @Test
    fun `a height inside the bounds is kept`() = assertEquals(300.dp, clampPanelHeight(300.dp, available = 800.dp))

    @Test
    fun `a height below the minimum is raised to it`() = assertEquals(160.dp, clampPanelHeight(40.dp, available = 800.dp))

    @Test
    fun `a height that would leave the list too little room is cut to the rest`() =
        assertEquals(360.dp, clampPanelHeight(900.dp, available = 600.dp))

    @Test
    fun `when both limits cannot be met the minimum wins`() = assertEquals(160.dp, clampPanelHeight(280.dp, available = 200.dp))

    @Test
    fun `the list keeps at least 240 dp`() = assertEquals(PanelTokens.KeepListMin, 240.dp)

    @Test
    fun `a taller window gives a kept height back and a shorter one clamps it`() {
        assertEquals(400.dp, clampPanelHeight(400.dp, available = 800.dp))
        assertEquals(260.dp, clampPanelHeight(400.dp, available = 500.dp))
        assertEquals(160.dp, clampPanelHeight(400.dp, available = 300.dp))
    }

    @Test
    fun `a drag on a shrunken window starts from the clamped height`() =
        assertEquals(210.dp, draggedPanelHeight(clampPanelHeight(400.dp, available = 500.dp), dragDown = 50.dp, available = 500.dp))

    @Test
    fun `custom limits apply`() = assertEquals(120.dp, clampPanelHeight(500.dp, available = 200.dp, min = 50.dp, keepListMin = 80.dp))

    @Test
    fun `dragging up grows the panel and down shrinks it`() {
        assertEquals(330.dp, draggedPanelHeight(280.dp, dragDown = (-50).dp, available = 800.dp))
        assertEquals(230.dp, draggedPanelHeight(280.dp, dragDown = 50.dp, available = 800.dp))
    }

    @Test
    fun `a drag cannot pass the limits`() {
        assertEquals(160.dp, draggedPanelHeight(280.dp, dragDown = 900.dp, available = 800.dp))
        assertEquals(560.dp, draggedPanelHeight(280.dp, dragDown = (-900).dp, available = 800.dp))
    }
}
