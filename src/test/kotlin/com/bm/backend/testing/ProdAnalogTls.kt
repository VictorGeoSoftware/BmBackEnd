package com.bm.backend.testing

import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.security.auth.x500.X500Principal

/** In-memory, single-connector TLS pin. No CA, files, tool invocation, or platform trust. */
internal class ProdAnalogTls {
    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val name = X500Principal("CN=BM synthetic loopback").encoded
    val certificate: X509Certificate = run {
        val now = Instant.now()
        val algorithm = seq(der(6, bytes(0x2a, 0x86, 0x48, 0xce, 0x3d, 4, 3, 2))) // ecdsa-with-SHA256
        val validity = seq(time(now.minusSeconds(60)), time(now.plusSeconds(3600)))
        val san = seq(der(6, bytes(0x55, 0x1d, 0x11)), der(4, seq(der(0x87, bytes(127, 0, 0, 1)))))
        val tbs = seq(der(0xa0, der(2, bytes(2))), der(2, bytes(1)), algorithm, name, validity, name, key.public.encoded,
            der(0xa3, seq(san)))
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(tbs); sign() }
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(seq(tbs, algorithm, der(3, bytes(0) + signature)))) as X509Certificate
    }
    val store: KeyStore = emptyStore().apply { setKeyEntry("fixture", key.private, charArrayOf(), arrayOf(certificate)) }
}

internal class ProdAnalogEndpoint(val port: Int, certificate: X509Certificate) {
    val pin: String = Base64.getEncoder().encodeToString(certificate.encoded)
    private val ssl = SSLContext.getInstance("TLS").apply {
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(emptyStore().apply { setCertificateEntry("fixture", certificate) })
        }
        init(null, trust.trustManagers, null)
    }
    val http: HttpClient = HttpClient.newBuilder().sslContext(ssl).proxy(ProxySelector.of(null)).version(HttpClient.Version.HTTP_1_1).build()
    fun request(path: String, token: String) = HttpRequest.newBuilder(URI("https://127.0.0.1:$port$path"))
        .timeout(Duration.ofSeconds(18)).header("Authorization", "Bearer $token")
    fun socket() = (ssl.socketFactory.createSocket(InetAddress.getByAddress(bytes(127, 0, 0, 1)), port) as javax.net.ssl.SSLSocket).apply {
        soTimeout = 4000
        sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
    }
}

private fun emptyStore() = KeyStore.getInstance("JKS").apply { load(null, null) }
private fun bytes(vararg value: Int) = ByteArray(value.size) { value[it].toByte() }
private fun seq(vararg value: ByteArray) = der(0x30, value.fold(byteArrayOf(), ByteArray::plus))
private fun der(tag: Int, value: ByteArray): ByteArray = bytes(tag) + when (val size = value.size) {
    in 0..127 -> bytes(size)
    in 128..255 -> bytes(0x81, size)
    else -> { require(size <= 0xffff); bytes(0x82, size ushr 8, size and 0xff) }
} + value
private val utc = DateTimeFormatter.ofPattern("uuuuMMddHHmmss'Z'").withZone(ZoneOffset.UTC)
private fun time(value: Instant) = der(0x18, utc.format(value).encodeToByteArray())
