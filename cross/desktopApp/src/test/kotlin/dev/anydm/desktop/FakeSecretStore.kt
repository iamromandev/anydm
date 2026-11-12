package dev.anydm.desktop

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A keychain in memory with a call log (account names only, never a secret), failure modes and a thread check.
 * A call from a thread in [forbidden] (the UI thread) is counted in [onForbiddenThread].
 */
class FakeSecretStore(
    initial: Map<String, String> = emptyMap(),
    /** When set, every call fails with this reason. */
    @Volatile var failWith: String? = null,
    /** When set, [get] (or [set]) waits for it before it returns, like a slow keychain. */
    @Volatile var getGate: CountDownLatch? = null,
    @Volatile var setGate: CountDownLatch? = null,
) : SecretStore {
    val values = ConcurrentHashMap<String, String>(initial)
    val calls = CopyOnWriteArrayList<String>()
    val threads = CopyOnWriteArrayList<String>()
    val forbidden = CopyOnWriteArrayList<Thread>()

    @Volatile
    var onForbiddenThread = 0

    private fun enter(call: String): SecretResult.Failed? {
        calls.add(call)
        threads.add(Thread.currentThread().name)
        if (Thread.currentThread() in forbidden) onForbiddenThread++
        return failWith?.let { SecretResult.Failed(it) }
    }

    override suspend fun get(account: String): SecretResult {
        val failed = enter("get $account")
        getGate?.await(10, TimeUnit.SECONDS)
        return failed ?: SecretResult.Ok(values[account])
    }

    override suspend fun set(
        account: String,
        secret: String,
    ): SecretResult {
        val failed = enter("set $account")
        setGate?.await(10, TimeUnit.SECONDS)
        if (failed != null) return failed
        values[account] = secret
        return SecretResult.Ok(null)
    }

    override suspend fun delete(account: String): SecretResult =
        enter("delete $account") ?: run {
            values.remove(account)
            SecretResult.Ok(null)
        }
}
