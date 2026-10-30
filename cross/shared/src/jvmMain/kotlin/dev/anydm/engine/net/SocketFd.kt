package dev.anydm.engine.net

import java.io.FileDescriptor
import java.lang.reflect.Method
import java.net.Socket
import java.net.SocketImpl

/**
 * The native handle of a Java [Socket] (a file descriptor on macOS and Linux, a SOCKET on Windows), which the options in
 * [NativeBind] are set on. JDK 21 has no public way to get it, so it is read from the socket's implementation by
 * reflection; that needs `--add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED`, which the
 * packaged app and the tests are started with. Without them [of] fails with a [BindFailure], so no connection is made.
 */
internal object SocketFd {
    private val implField by lazy { Socket::class.java.getDeclaredField("impl").also { it.isAccessible = true } }
    private val handleMethod: Method by lazy {
        SocketImpl::class.java
            .getDeclaredMethod(
                "getFileDescriptor",
            ).also { it.isAccessible = true }
    }
    private val fdField by lazy { FileDescriptor::class.java.getDeclaredField("fd").also { it.isAccessible = true } }

    /** The handle of [socket], which the JDK creates on first use; an option read below makes it. */
    fun of(socket: Socket): Int =
        try {
            socket.tcpNoDelay
            val impl = implField.get(socket) as SocketImpl
            fdField.getInt(handleMethod.invoke(impl))
        } catch (e: ReflectiveOperationException) {
            throw BindFailure("the socket's handle can't be read: ${e.message}", e)
        } catch (e: RuntimeException) {
            // InaccessibleObjectException: the JVM was started without the --add-opens above.
            throw BindFailure("the socket's handle can't be read; the JVM needs --add-opens for java.base: ${e.message}", e)
        }
}
