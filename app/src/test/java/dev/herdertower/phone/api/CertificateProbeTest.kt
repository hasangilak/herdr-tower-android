package dev.herdertower.phone.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Pairing by hand: the probe that reads the certificate closes its socket on every path. */
class CertificateProbeTest {
    @Test fun aFailedHandshakeClosesTheSocket() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            // Something that accepts the connection and never answers the TLS handshake.
            val closedByPhone = CompletableFuture.supplyAsync {
                server.accept().use { s ->
                    s.soTimeout = 3_000
                    try {
                        // The probe's ClientHello arrives first; then end of stream, once the probe gave up and closed.
                        val buf = ByteArray(4096)
                        while (s.getInputStream().read(buf) >= 0) Unit
                        true
                    } catch (e: SocketTimeoutException) {
                        false // still open: the socket was leaked
                    }
                }
            }
            try {
                CertificateProbe.fingerprint("127.0.0.1", server.localPort, timeoutMs = 500)
                fail("a handshake that never completes must fail")
            } catch (e: Exception) {
                // expected: the handshake timed out
            }
            assertEquals("the probe closed its socket after the failure", true, closedByPhone.get(5, TimeUnit.SECONDS))
        }
    }

    @Test fun nothingListeningFailsQuickly() {
        val port = ServerSocket(0).use { it.localPort } // free again: nothing listens there
        val t0 = System.currentTimeMillis()
        try {
            CertificateProbe.fingerprint("127.0.0.1", port, timeoutMs = 1_000)
            fail("nothing listens there")
        } catch (e: Exception) {
            assertTrue(System.currentTimeMillis() - t0 < 2_000)
        }
    }

    @Test fun repeatedFailuresDontLeakFileDescriptors() {
        val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.UnixOperatingSystemMXBean ?: return
        val port = ServerSocket(0).use { it.localPort }
        repeat(5) { runCatching { CertificateProbe.fingerprint("127.0.0.1", port, timeoutMs = 500) } } // warm up
        val before = os.openFileDescriptorCount
        repeat(200) { runCatching { CertificateProbe.fingerprint("127.0.0.1", port, timeoutMs = 500) } }
        System.gc()
        val after = os.openFileDescriptorCount
        assertTrue("open files went from $before to $after", after - before < 10)
    }
}
