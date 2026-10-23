package dev.anydm.engine.os

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.ftp.stripUserInfo
import dev.anydm.engine.platform.Clipboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A link the user copied (OSI-05): the link without any login, and what it would be. */
data class ClipboardLink(
    val link: String,
    val kind: DownloadKind,
)

/**
 * Looks at the clipboard while it is switched on and announces a copied link on [links] (OSI-05). It adds nothing: the
 * hotkey or a UI does. What counts as a link is [ClipboardLinks]'s rule. A link is announced once for as long as it stays on
 * the clipboard; what was there at the first successful look after the watch started is not news, however many looks it took
 * to get one. A clipboard with no text, or one that can't be read, is skipped and looked at again. The text itself is not
 * kept: only a fingerprint of it, to tell a change.
 */
class ClipboardWatch(
    private val clipboard: Clipboard,
    private val every: Duration = 500.milliseconds,
    private val maxLength: Int = ClipboardLinks.MAX_LENGTH,
) {
    private val announced = MutableSharedFlow<ClipboardLink>(extraBufferCapacity = 16)
    val links: SharedFlow<ClipboardLink> = announced.asSharedFlow()

    /** Follows [enabled] until cancelled: looks while it is true, stops when it is false. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run(enabled: Flow<Boolean>) {
        enabled.distinctUntilChanged().collectLatest { on -> if (on) look() }
    }

    private sealed interface Read {
        data class Text(
            val value: String,
        ) : Read

        data object NoText : Read

        data object Failed : Read
    }

    private suspend fun look() {
        var baselined = false
        var last: Long? = null
        while (true) {
            when (val read = read()) {
                is Read.Text -> {
                    val now = fingerprint(read.value)
                    if (!baselined) {
                        baselined = true
                        last = now
                    } else if (now != last) {
                        last = now
                        linkIn(read.value)?.let { announced.tryEmit(it) }
                    }
                }

                Read.NoText -> {
                    baselined = true
                }

                Read.Failed -> {
                    Unit
                }
            }
            delay(every)
        }
    }

    private suspend fun read(): Read =
        try {
            clipboard.text()?.let { Read.Text(it) } ?: Read.NoText
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Read.Failed
        }

    private fun fingerprint(text: String): Long = (text.length.toLong() shl 32) xor text.hashCode().toLong()

    private fun linkIn(text: String): ClipboardLink? {
        val parsed = ClipboardLinks.parse(text, maxLength, allowFiles = false) ?: return null
        return ClipboardLink(stripUserInfo(parsed.link), parsed.kind)
    }
}
