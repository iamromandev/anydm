package dev.anydm.desktop.chrome

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.anydm.desktop.theme.DesktopTheme
import dev.anydm.model.SummaryDto
import dev.anydm.store.Connection
import dev.anydm.store.ListFilter
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SourceListUiTest {
    @Test
    fun `counts show with separators, and a click picks the filter`() =
        runComposeUiTest {
            var picked = ListFilter.ALL
            setContent {
                DesktopTheme(dark = true) {
                    SourceList(
                        items = sourceItems(SummaryDto(all = 1234, downloading = 3, seeding = 0, completed = 1231)),
                        selected = ListFilter.ALL,
                        onSelect = { picked = it },
                        host = "127.0.0.1:8046",
                        connection = Connection.Live,
                        onChangeServer = {},
                        onSettings = {},
                        onClearFinished = {},
                        width = 190.dp,
                        onWidth = {},
                    )
                }
            }
            onNodeWithText("1,234").assertExists()
            onNodeWithText("127.0.0.1:8046").assertExists()
            onNodeWithText("Active").performClick()
            assertEquals(ListFilter.ACTIVE, picked)
        }
}
