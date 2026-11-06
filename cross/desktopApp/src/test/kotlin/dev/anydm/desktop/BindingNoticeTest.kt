package dev.anydm.desktop

import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.BondedNetwork
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class BindingNoticeTest {
    private fun net(
        key: String,
        status: BindStatus,
        up: Boolean = true,
    ) = BondedNetwork(NetworkInfo(key, key, up, emptyList()), true, status, null, false)

    private fun notice(block: suspend TestScope.(FakeSource, BindingNotice) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val source = FakeSource()
            block(source, BindingNotice(source, backgroundScope))
        }

    private fun FakeSource.set(vararg nets: BondedNetwork) {
        networks.value = BondingState(nets.toList())
    }

    @Test
    fun `two networks up that the engine cannot bind show the notice`() =
        notice { source, notice ->
            assertFalse(notice.visible.value)
            source.set(net("en0", BindStatus.UNSUPPORTED), net("en1", BindStatus.UNSUPPORTED))
            assertTrue(notice.visible.value)
        }

    @Test
    fun `one network does not show it`() =
        notice { source, notice ->
            source.set(net("en0", BindStatus.UNSUPPORTED))
            assertFalse(notice.visible.value)
        }

    @Test
    fun `a network that is down does not count`() =
        notice { source, notice ->
            source.set(net("en0", BindStatus.UNSUPPORTED), net("en1", BindStatus.UNKNOWN, up = false))
            assertFalse(notice.visible.value)
        }

    @Test
    fun `networks not yet probed do not show it`() =
        notice { source, notice ->
            source.set(net("en0", BindStatus.UNKNOWN), net("en1", BindStatus.UNSUPPORTED))
            assertFalse(notice.visible.value)
        }

    @Test
    fun `binding that works on some network does not show it`() =
        notice { source, notice ->
            source.set(net("en0", BindStatus.ISOLATED), net("en1", BindStatus.UNSUPPORTED))
            assertFalse(notice.visible.value)
            source.set(net("en0", BindStatus.BEST_EFFORT), net("en1", BindStatus.UNSUPPORTED))
            assertFalse(notice.visible.value)
        }

    @Test
    fun `networks that all bind best-effort do not show it`() =
        notice { source, notice ->
            source.set(net("en0", BindStatus.BEST_EFFORT), net("en1", BindStatus.BEST_EFFORT))
            assertFalse(notice.visible.value)
        }

    @Test
    fun `best-effort beside an unsupported network does not show it, whichever comes first`() =
        notice { source, notice ->
            source.set(net("en0", BindStatus.UNSUPPORTED), net("en1", BindStatus.BEST_EFFORT))
            assertFalse(notice.visible.value)
        }

    @Test
    fun `once dismissed it stays away for the launch`() =
        notice { source, notice ->
            source.set(net("en0", BindStatus.UNSUPPORTED), net("en1", BindStatus.UNSUPPORTED))
            notice.dismiss()
            assertFalse(notice.visible.value)
            source.set(net("en0", BindStatus.UNSUPPORTED))
            source.set(net("en0", BindStatus.UNSUPPORTED), net("en2", BindStatus.UNSUPPORTED))
            assertFalse(notice.visible.value)
        }
}
