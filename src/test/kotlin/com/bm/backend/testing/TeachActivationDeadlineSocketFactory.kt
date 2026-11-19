package com.bm.backend.testing

import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

/** PgJDBC calls this on the admitted caller thread (loginTimeout=0). A single socket deadline
 * covers TCP connect, startup handshake, statements and close, not just individual reads.
 */
internal class TeachActivationDeadlineSocketFactory : SocketFactory() {
    companion object {
        private val provision = ThreadLocal<(() -> Socket)>()

        fun <T> connect(scheduler: ScheduledExecutorService, remainingMillis: () -> Long, run: () -> T): T {
            check(provision.get() == null)
            provision.set {
                val expiry = AtomicReference<ScheduledFuture<*>>()
                val socket = object : Socket(Proxy.NO_PROXY) {
                    override fun close() {
                        try { super.close() } finally { expiry.get()?.cancel(false) }
                    }
                }
                try {
                    expiry.set(scheduler.schedule({ runCatching { socket.close() } }, remainingMillis(), TimeUnit.MILLISECONDS))
                    if (socket.isClosed) expiry.get().cancel(false)
                    socket
                } catch (failure: Throwable) {
                    socket.close()
                    throw failure
                }
            }
            return try { run() } finally { provision.remove() }
        }
    }

    override fun createSocket(): Socket = checkNotNull(provision.get()).invoke()
    override fun createSocket(host: String, port: Int): Socket = error("Unsupported fixture socket method")
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = error("Unsupported fixture socket method")
    override fun createSocket(host: InetAddress, port: Int): Socket = error("Unsupported fixture socket method")
    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket = error("Unsupported fixture socket method")
}
