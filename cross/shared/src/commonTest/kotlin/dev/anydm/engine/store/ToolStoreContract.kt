package dev.anydm.engine.store

import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.ToolRecord
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Instant

/** What every [ToolStore] must do (BIN-01), each test on a new database from [newDriver]. */
abstract class ToolStoreContract : StoreTestBase() {
    private fun record(
        tool: Tool = Tool.YT_DLP,
        version: String = "2026.10.01",
        checkedAt: Instant? = Instant.fromEpochMilliseconds(1_700_000_000_123),
    ) = ToolRecord(tool, version, "/cache/tools/${tool.id}/$version", checkedAt)

    @Test
    fun putThenGet() =
        runTest {
            val tools = newStore().tools
            tools.put(record())

            tools.get(Tool.YT_DLP) shouldBe record()
        }

    @Test
    fun aMissingCheckTimeStaysNull() =
        runTest {
            val tools = newStore().tools
            tools.put(record(checkedAt = null))

            tools.get(Tool.YT_DLP)!!.checkedAt shouldBe null
        }

    @Test
    fun putReplacesTheRecordOfTheSameTool() =
        runTest {
            val tools = newStore().tools
            tools.put(record(version = "1.0"))
            tools.put(record(version = "2.0"))

            tools.get(Tool.YT_DLP)!!.version shouldBe "2.0"
            tools.all().size shouldBe 1
        }

    @Test
    fun allListsEveryTool() =
        runTest {
            val tools = newStore().tools
            tools.put(record(Tool.YT_DLP))
            tools.put(record(Tool.DENO, version = "2.1.4"))

            tools.all().map { it.tool }.toSet() shouldBe setOf(Tool.YT_DLP, Tool.DENO)
        }

    @Test
    fun anUnknownToolIsNull() =
        runTest {
            newStore().tools.get(Tool.FFMPEG) shouldBe null
        }

    @Test
    fun aStoredNameThisVersionDoesNotKnowIsSkipped() =
        runTest {
            val tools = newStore().tools
            tools.put(record())
            driver.exec("INSERT INTO tool(name, version, path, checked_at) VALUES ('from-the-future', '1', '/x', NULL)")

            tools.all().map { it.tool } shouldBe listOf(Tool.YT_DLP)
        }
}
