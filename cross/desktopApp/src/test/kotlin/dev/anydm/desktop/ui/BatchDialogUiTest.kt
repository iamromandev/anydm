package dev.anydm.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.desktop.theme.DesktopTheme
import dev.anydm.model.BatchItem
import dev.anydm.model.BatchKind
import dev.anydm.model.BatchOutcome
import dev.anydm.model.BatchPreview
import dev.anydm.store.BatchState
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BatchDialogUiTest {
    private val urls = (1..120).map { "https://x.test/img${it.toString().padStart(3, '0')}.png" }

    @Test
    fun `a preview names the count, the first links and how many more, and offers to add them all`() =
        runComposeUiTest {
            var added = 0
            val state = BatchState(kind = BatchKind.PATTERN, pattern = "https://x.test/img[001-120].png", preview = BatchPreview(120, urls))
            setContent {
                DesktopTheme(dark = false) {
                    BatchDialog(
                        state,
                        "Best",
                        onKind = {},
                        onText = {},
                        onAdd = { added += 1 },
                        onOpen = {},
                        onStartOver = {},
                        onClose = {},
                    )
                }
            }
            onNodeWithText("120 links").assertExists()
            onNodeWithText("https://x.test/img001.png").assertExists()
            onNodeWithText("…and 115 more").assertExists()
            onNodeWithText("Add 120 links").assertIsEnabled().performClick()
            assertEquals(1, added)
        }

    @Test
    fun `a refused preview shows why and keeps Add off`() =
        runComposeUiTest {
            val state =
                BatchState(
                    kind = BatchKind.PATTERN,
                    pattern = "f[1-1200]",
                    previewError = "The pattern names 1,200 links; the most is 1,000",
                )
            setContent {
                DesktopTheme(dark = true) {
                    BatchDialog(state, "Best", onKind = {}, onText = {}, onAdd = {}, onOpen = {}, onStartOver = {}, onClose = {})
                }
            }
            onNodeWithText("The pattern names 1,200 links; the most is 1,000").assertExists()
            onNodeWithText("Add").assertIsNotEnabled()
        }

    @Test
    fun `the tabs switch between a list and a pattern`() =
        runComposeUiTest {
            val picked = mutableListOf<BatchKind>()
            setContent {
                DesktopTheme(dark = false) {
                    BatchDialog(
                        BatchState(),
                        "Best",
                        onKind = { picked += it },
                        onText = {},
                        onAdd = {},
                        onOpen = {},
                        onStartOver = {},
                        onClose = {},
                    )
                }
            }
            onNodeWithTag("batch-list").assertExists()
            onNodeWithText("Pattern").performClick()
            onNodeWithText("List").performClick()
            assertEquals(listOf(BatchKind.PATTERN, BatchKind.LIST), picked)
        }

    @Test
    fun `results sum up, say why a link failed, and Open names the download`() =
        runComposeUiTest {
            val opened = mutableListOf<String>()
            var startedOver = 0
            val state =
                BatchState(
                    results =
                        listOf(
                            BatchItem("https://x.test/a.bin", BatchOutcome.ADDED, "d1", ""),
                            BatchItem("https://x.test/old.bin", BatchOutcome.DUPLICATE, "d0", "Already in your list"),
                            BatchItem("https://gone.test/v", BatchOutcome.ERROR, null, "Video unavailable"),
                        ),
                )
            setContent {
                DesktopTheme(dark = false) {
                    BatchDialog(state, "Best", onKind = {}, onText = {}, onAdd = {}, onOpen = { opened += it }, onStartOver = {
                        startedOver += 1
                    }, onClose = {})
                }
            }
            onNodeWithText("1 added · 1 already in your list · 1 failed").assertExists()
            onNodeWithText("Video unavailable").assertExists()
            onNodeWithText("In your list").assertExists()
            // Two Opens: the new download's and the held one's; the failed link has none.
            val opens = onAllNodesWithText("Open")
            opens.assertCountEquals(2)
            opens[1].performClick()
            assertEquals(listOf("d0"), opened)
            onNodeWithText("Add more").performClick()
            assertEquals(1, startedOver)
        }

    @Test
    fun `Esc closes the sheet`() =
        runComposeUiTest {
            var closed = 0
            setContent {
                DesktopTheme(dark = false) {
                    BatchDialog(BatchState(), "Best", onKind = {}, onText = {}, onAdd = {}, onOpen = {}, onStartOver = {}, onClose = {
                        closed +=
                            1
                    })
                }
            }
            onNodeWithTag("batch-list").performClick()
            onNodeWithTag("batch").performKeyInput { pressKey(Key.Escape) }
            assertEquals(1, closed)
        }
}
