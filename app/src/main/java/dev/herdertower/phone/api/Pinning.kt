package dev.herdertower.phone.api

import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** The tower's certificate isn't the one this phone paired with. */
class CertificateMismatchException(val expected: String, val got: String) :
    CertificateException("The tower's certificate changed (expected ${expected.take(8)}…, got ${got.take(8)}…)")

/**
 * Trusts exactly one certificate: the one whose DER has [fingerprint] as its
 * SHA-256. There is no CA; the fingerprint came out of band (the pairing QR,
 * or the user comparing it with what the Mac printed).
 */
class PinnedTrustManager(fingerprint: String) : X509TrustManager {
    private val pin = requireNotNull(Fingerprint.normalize(fingerprint)) { "not a SHA-256 fingerprint" }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("The tower sent no certificate")
        val got = Fingerprint.of(leaf)
        if (!Fingerprint.matches(got, pin)) throw CertificateMismatchException(pin, got)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client certificates are not used")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    fun sslContext(): SSLContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@PinnedTrustManager), SecureRandom()) }

    companion object {
        /**
         * Hostnames are not checked (the certificate is named `herder-tower`
         * and the Mac's IP can change): the pin above already proves which
         * server this is.
         */
        val ANY_HOST = HostnameVerifier { _, _ -> true }
    }
}

/**
 * For pairing by hand: connects once, accepts whatever certificate the
 * address presents, and returns its fingerprint for the user to compare
 * with the one the Mac printed. Nothing is sent over this connection.
 */
object CertificateProbe {
    fun fingerprint(host: String, port: Int, timeoutMs: Int = 5000): String {
        var seen: X509Certificate? = null
        val capture = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                seen = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            }
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capture), SecureRandom()) }
        val raw = java.net.Socket()
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        raw.soTimeout = timeoutMs
        (ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket).use { it.startHandshake() }
        return Fingerprint.of(seen ?: throw CertificateException("The tower sent no certificate"))
    }
}
