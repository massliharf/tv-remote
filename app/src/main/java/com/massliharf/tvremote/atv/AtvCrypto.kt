package com.massliharf.tvremote.atv

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Client identity (self-signed RSA certificate) and pairing secret for Android TV Remote v2. */
object AtvCrypto {

    class Identity(val key: PrivateKey, val cert: X509Certificate)

    /** Loads the stored identity or creates and stores a new one. */
    fun identity(get: (String) -> String?, put: (String, String) -> Unit): Identity {
        val keyHex = get("atv_key")
        val certHex = get("atv_cert")
        if (keyHex != null && certHex != null) {
            try {
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(unhex(keyHex)))
                return Identity(key, parseCert(unhex(certHex)))
            } catch (_: Exception) {
                // fall through and regenerate
            }
        }
        val id = generate("atvremote")
        put("atv_key", hex(id.key.encoded))
        put("atv_cert", hex(id.cert.encoded))
        return id
    }

    fun generate(commonName: String): Identity {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        val algId = seq(oid(1, 2, 840, 113549, 1, 1, 11), byteArrayOf(0x05, 0x00)) // sha256WithRSA
        val name = seq(set(seq(oid(2, 5, 4, 3), tlv(0x0C, commonName.toByteArray(Charsets.UTF_8)))))
        val now = System.currentTimeMillis()
        val day = 24L * 3600 * 1000
        val validity = seq(utcTime(Date(now - day)), utcTime(Date(now + 20 * 365 * day)))
        val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
        val tbs = seq(
            tlv(0xA0, integer(BigInteger.valueOf(2))), // version v3
            integer(serial),
            algId,
            name,
            validity,
            name,
            kp.public.encoded, // SubjectPublicKeyInfo
        )
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(kp.private)
            update(tbs)
            sign()
        }
        val der = seq(tbs, algId, tlv(0x03, byteArrayOf(0) + sig))
        return Identity(kp.private, parseCert(der))
    }

    /**
     * The pairing secret: SHA-256 over both RSA keys and the last 4 hex digits of the code
     * shown on the TV. The first 2 hex digits must equal the hash's first byte, which lets us
     * reject a mistyped code locally. Returns null for a wrong code.
     */
    fun secret(client: X509Certificate, server: X509Certificate, code: String): ByteArray? {
        if (code.length != 6) return null
        val bytes = try { unhex(code) } catch (_: Exception) { return null }
        val c = client.publicKey as RSAPublicKey
        val s = server.publicKey as RSAPublicKey
        val md = MessageDigest.getInstance("SHA-256")
        md.update(magnitude(c.modulus))
        md.update(magnitude(c.publicExponent))
        md.update(magnitude(s.modulus))
        md.update(magnitude(s.publicExponent))
        md.update(bytes, 1, 2)
        val digest = md.digest()
        return if (digest[0] == bytes[0]) digest else null
    }

    private fun magnitude(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        var i = 0
        while (i < b.size - 1 && b[i] == 0.toByte()) i++
        return b.copyOfRange(i, b.size)
    }

    private fun parseCert(der: ByteArray) =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    // ---- Minimal DER encoding ----

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        when {
            len < 0x80 -> out.write(len)
            len < 0x100 -> { out.write(0x81); out.write(len) }
            len < 0x10000 -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xFF) }
            else -> { out.write(0x83); out.write(len shr 16); out.write((len shr 8) and 0xFF); out.write(len and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

    private fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })

    private fun integer(v: BigInteger) = tlv(0x02, v.toByteArray())

    private fun utcTime(d: Date): ByteArray {
        val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, f.format(d).toByteArray(Charsets.US_ASCII))
    }

    private fun oid(vararg arcs: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(arcs[0] * 40 + arcs[1])
        for (i in 2 until arcs.size) {
            var v = arcs[i]
            val stack = ArrayList<Int>()
            stack.add(v and 0x7F)
            v = v shr 7
            while (v > 0) {
                stack.add((v and 0x7F) or 0x80)
                v = v shr 7
            }
            for (j in stack.indices.reversed()) out.write(stack[j])
        }
        return tlv(0x06, out.toByteArray())
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
