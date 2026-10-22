package dev.anydm.engine.net

import com.sun.jna.LastErrorException
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.net.NetworkInterface
import java.net.Socket

/**
 * Ties a socket to one network interface with the OS's own option (BND-02): `IP_BOUND_IF` on macOS, `SO_BINDTODEVICE` on
 * Linux, `IP_UNICAST_IF` on Windows. A Java socket may be IPv4 or dual-stack, so the IPv4 and the IPv6 form are both set
 * where the OS has two, and it is a failure only if none took. Any failure is a [BindFailure]: the caller then makes no
 * connection. Call it before `connect`.
 */
internal object NativeBind {
    private val os = System.getProperty("os.name").lowercase()
    private val mac = "mac" in os
    private val windows = "windows" in os
    private val linux = "linux" in os

    private interface Libc : Library {
        @Throws(LastErrorException::class)
        fun setsockopt(
            fd: Int,
            level: Int,
            name: Int,
            value: Pointer,
            length: Int,
        ): Int

        @Throws(LastErrorException::class)
        fun getsockopt(
            fd: Int,
            level: Int,
            name: Int,
            value: Pointer,
            length: IntByReference,
        ): Int
    }

    /** Winsock: the same two calls, on a SOCKET, which is pointer-sized. */
    private interface Winsock : StdCallLibrary {
        @Throws(LastErrorException::class)
        fun setsockopt(
            socket: Long,
            level: Int,
            name: Int,
            value: Pointer,
            length: Int,
        ): Int

        @Throws(LastErrorException::class)
        fun getsockopt(
            socket: Long,
            level: Int,
            name: Int,
            value: Pointer,
            length: IntByReference,
        ): Int
    }

    private val libc: Libc by lazy { Native.load("c", Libc::class.java) }
    private val winsock: Winsock by lazy { Native.load("ws2_32", Winsock::class.java) }

    fun bind(
        socket: Socket,
        network: NetworkInfo,
    ) {
        val fd = SocketFd.of(socket)
        try {
            when {
                mac -> bindMac(fd, network)

                windows -> bindWindows(fd, network)

                linux -> bindLinux(fd, network)

                // Another system's SOL_SOCKET numbers differ: an option set blindly could "succeed" and bind nothing.
                else -> throw BindFailure("binding a connection to a network is not supported on this system")
            }
        } catch (e: IllegalArgumentException) {
            throw BindFailure("can't bind to ${network.key}: ${e.message}", e)
        } catch (e: LastErrorException) {
            throw BindFailure("can't bind to ${network.key}: ${e.message}", e)
        } catch (e: UnsatisfiedLinkError) {
            throw BindFailure("can't bind to ${network.key}: the system call is missing: ${e.message}", e)
        }
    }

    /** The key of the interface [socket] is tied to, read back from the OS; null when it is tied to none. */
    fun boundTo(socket: Socket): String? {
        val fd = SocketFd.of(socket)
        return try {
            when {
                mac -> readMac(fd)
                windows -> readWindows(fd)
                linux -> readLinux(fd)
                else -> null
            }
        } catch (_: LastErrorException) {
            null
        }
    }

    // IPPROTO_IP and IPPROTO_IPV6 (the same on every OS here), then the option of each.
    private const val IPPROTO_IP = 0
    private const val IPPROTO_IPV6 = 41
    private const val MAC_IP_BOUND_IF = 25
    private const val MAC_IPV6_BOUND_IF = 125
    private const val WIN_IP_UNICAST_IF = 31
    private const val WIN_IPV6_UNICAST_IF = 31
    private const val SOL_SOCKET = 1
    private const val LINUX_SO_BINDTODEVICE = 25
    private const val NAME_MAX = 16

    private fun bindMac(
        fd: Int,
        network: NetworkInfo,
    ) {
        require(network.index > 0) { "${network.key} has no interface index" }
        val index = Memory(4).also { it.setInt(0, network.index) }
        setEither(
            { libc.setsockopt(fd, IPPROTO_IP, MAC_IP_BOUND_IF, index, 4) },
            { libc.setsockopt(fd, IPPROTO_IPV6, MAC_IPV6_BOUND_IF, index, 4) },
        )
    }

    private fun readMac(fd: Int): String? {
        val index = readInt { value, length -> libc.getsockopt(fd, IPPROTO_IP, MAC_IP_BOUND_IF, value, length) }
        val v6 = readInt { value, length -> libc.getsockopt(fd, IPPROTO_IPV6, MAC_IPV6_BOUND_IF, value, length) }
        return nameOfIndex(maxOf(index ?: 0, v6 ?: 0))
    }

    private fun bindLinux(
        fd: Int,
        network: NetworkInfo,
    ) {
        val name = network.key.toByteArray(Charsets.US_ASCII)
        require(name.size < NAME_MAX) { "${network.key} is not an interface name" }
        val value =
            Memory(NAME_MAX.toLong()).also {
                it.clear()
                it.write(0, name, 0, name.size)
            }
        if (libc.setsockopt(fd, SOL_SOCKET, LINUX_SO_BINDTODEVICE, value, name.size + 1) != 0) {
            throw LastErrorException("setsockopt returned non-zero")
        }
    }

    private fun readLinux(fd: Int): String? {
        val value = Memory(NAME_MAX.toLong()).also { it.clear() }
        val length = IntByReference(NAME_MAX)
        libc.getsockopt(fd, SOL_SOCKET, LINUX_SO_BINDTODEVICE, value, length)
        return value.getString(0).takeIf { length.value > 0 && it.isNotEmpty() }
    }

    private fun bindWindows(
        fd: Int,
        network: NetworkInfo,
    ) {
        require(network.index > 0) { "${network.key} has no interface index" }
        // IPv4 takes the index in network byte order, IPv6 in host order.
        val v4 = Memory(4).also { it.setInt(0, Integer.reverseBytes(network.index)) }
        val v6 = Memory(4).also { it.setInt(0, network.index) }
        setEither(
            { winsock.setsockopt(fd.toLong(), IPPROTO_IP, WIN_IP_UNICAST_IF, v4, 4) },
            { winsock.setsockopt(fd.toLong(), IPPROTO_IPV6, WIN_IPV6_UNICAST_IF, v6, 4) },
        )
    }

    private fun readWindows(fd: Int): String? {
        // A Java socket is IPv4 or dual-stack, and each family takes its own option: read both.
        val v4 = readInt { value, length -> winsock.getsockopt(fd.toLong(), IPPROTO_IP, WIN_IP_UNICAST_IF, value, length) }
        val v6 = readInt { value, length -> winsock.getsockopt(fd.toLong(), IPPROTO_IPV6, WIN_IPV6_UNICAST_IF, value, length) }
        // The IPv4 value is in network byte order, so it is only positive once swapped.
        return nameOfIndex(maxOf(v4?.let(Integer::reverseBytes) ?: 0, v6 ?: 0))
    }

    /** Runs both; a failure only if neither worked, with the first one's reason. */
    private fun setEither(vararg sets: () -> Int) {
        var first: LastErrorException? = null
        var done = false
        for (set in sets) {
            try {
                // Not only the exception: a call that returns non-zero did not take (JNA's error capture differs by system).
                if (set() != 0) throw LastErrorException("setsockopt returned non-zero")
                done = true
            } catch (e: LastErrorException) {
                if (first == null) first = e
            }
        }
        if (!done) throw checkNotNull(first)
    }

    private fun readInt(read: (Pointer, IntByReference) -> Int): Int? =
        try {
            val value = Memory(4)
            read(value, IntByReference(4))
            value.getInt(0).takeIf { it != 0 }
        } catch (_: LastErrorException) {
            null
        }

    private fun nameOfIndex(index: Int): String? = if (index <= 0) null else NetworkInterface.getByIndex(index)?.name
}
