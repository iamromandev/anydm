package dev.anydm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TorrentTreeTest {
    private fun tree(vararg paths: String) = buildTree(paths.mapIndexed { i, p -> i to p })

    private fun TreeRow.label() =
        when (this) {
            is TreeRow.FolderRow -> "${if (open) "v" else ">"}${folder.path}@$depth"
            is TreeRow.FileRow -> "$name#$index@$depth"
        }

    @Test
    fun `nested and unicode folders keep their order and indexes`() {
        val flat = flattenTree(tree("ä/日本/x", "ä/日本/y", "ä/z", "b"), emptySet(), false, 300)
        assertEquals(
            listOf("vä@0", "vä/日本@1", "x#0@2", "y#1@2", "z#2@1", "b#3@0"),
            flat.rows.map { it.label() },
        )
        assertEquals(0, flat.hiddenFiles)
    }

    @Test
    fun `a folder lists the files under it, however deep`() {
        val root = tree("a/b/c", "a/d", "e")
        assertEquals(listOf(0, 1), (root.children.first() as TreeFolder).files)
    }

    @Test
    fun `a path with only slashes keeps the file`() {
        assertNotNull(tree("/").children.singleOrNull())
    }

    @Test
    fun `up to the threshold folders open, past it they are collapsed until toggled`() {
        val root = tree("a/x", "a/y")
        assertEquals(3, flattenTree(root, emptySet(), false, 300).rows.size)
        assertEquals(1, flattenTree(root, emptySet(), true, 300).rows.size)
        assertEquals(3, flattenTree(root, setOf("a"), true, 300).rows.size)
        assertEquals(1, flattenTree(root, setOf("a"), false, 300).rows.size)
    }

    @Test
    fun `rows past the cap are counted as files and a higher cap reaches them`() {
        val root = tree(*Array(350) { "d/f$it" })
        val capped = flattenTree(root, setOf("d"), true, MAX_TREE_ROWS)
        assertEquals(MAX_TREE_ROWS, capped.rows.size)
        assertEquals(51, capped.hiddenFiles)
        val more = flattenTree(root, setOf("d"), true, MAX_TREE_ROWS * 2)
        assertEquals(351, more.rows.size)
        assertEquals(0, more.hiddenFiles)
    }

    @Test
    fun `a closed folder past the cap counts all its files`() {
        val root = tree("a", "b/x", "b/y", "c")
        val capped = flattenTree(root, emptySet(), true, 1)
        assertEquals(1, capped.rows.size)
        assertEquals(3, capped.hiddenFiles)
    }

    @Test
    fun `seed input needs plain positive numbers within the limits`() {
        assertEquals(1.5, parseSeedRatio("1.5"))
        assertEquals(2.0, parseSeedRatio(" 2 "))
        listOf("0", "0.0", "-1", "abc", "NaN", "Infinity", "1e999", "1.5d", "0x1p3", "", ".5", "1001").forEach {
            assertNull(parseSeedRatio(it), it)
        }
        assertEquals(90, parseSeedMinutes("90"))
        listOf("0", "-5", "1.5", "x", "99999999999", "5256001", "").forEach { assertNull(parseSeedMinutes(it), it) }
    }

    @Test
    fun `a duration reads like an eta`() {
        assertEquals("12s", formatDuration(12))
        assertEquals("1m 05s", formatDuration(65))
        assertEquals("1h 02m", formatDuration(3720))
    }
}
