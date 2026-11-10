package flai.app

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

object Keys {
    val clientPriv: RSAPrivateKey = run {
        val pem = """-----BEGIN PRIVATE KEY-----
MIICeQIBADANBgkqhkiG9w0BAQEFAASCAmMwggJfAgEAAoGBAOXnEA2r5NIpSqlk
HYpRFA0S1wEY5xdSBN90la+AukB42drUo22QTUOOmJtX2tGAoiCOAmEub5wT3Mo8
6Z8aF5m5bwTLmxaCPMs8OK9KNUAdG+Y6fz2D6ntbo/KmkI/jvFXssPDtRzWpWPzU
34k2GQnEsCkCOJFLDN5fKwhpOsLrAgMBAAECgYEAtw99oOU2loF6idbGDtTwelh+
CsmheNg+OQ/EALMnmx6fmbenSh6U5y4sJcWW3yrl7bYl/uvSwxytufs2qxFDqAlN
H//eJzrJqUFOFvpIjvqUvFt1jtcQDAvkVRaOZa2UNQ0oJ40vv/uxvXDErJD7A3sr
socSWH41YIpo8hDX9QECQQD+e5Wi03OuYXbyE8xYE6Yl68/5mDQqGdgUdQRMTuMg
SSGfLLwBo+HHgrCBeT7C0k4RPyRPU0d9Zj0UZ1t+SOpbAkEA50X2Le4Z7wy4RfKw
l3nR1gvUPcDg3aWb9YLVatckISjcJnD+EWVVUT9QbyzZHr428QWnPbkjk0OAXaxr
hotOsQJBANxzqNQOA4eLzSO2PO4Lz4HIPzlEPeQ+0S4flKKaMC12zNZ8RT3SfzN7
JnZy1wikcJaGMWk6hpK+L6luRBcpCUMCQQCJxYqmx92ZI2mu+akrQ322Viukcf9C
6sIimkMKt4klW9Lx74Pa1xPhJ6eB4ySghM3z/NspEN5Zfu+8XddcOiaxAkEA48MX
2Q9v+Vpgz1f3yUyTVQPkQNyVDU95iTa1JWdcfhj8yvyNMiVmQiK2EM0/e0RgxDm/
3jsPZTOCOyEjZY2Svw==
-----END PRIVATE KEY-----"""
        val body = pem.lines().filterNot { it.contains("-----") }.joinToString("")
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(body, Base64.DEFAULT))) as RSAPrivateKey
    }

    val serverPub: PublicKey = run {
        val pem = "MIGJAoGBAKnQIJHWpQDPTUCdGvnv+DQk35Lrdvb/+0dz2wkzf4ZiDGBRCd11+uRO" +
                "r/VM8l3WMjqmodqkz/w8noAUO/yJeWzJSSFPAaCh3stTLxRTKgdlI+ungY+7sMpY" +
                "xbs77dM4MAtzSIKZp6Gfxp1Wq3OFB3ww8DzRzkqTijb6j56RTQedAgMBAAE="
        val d = Base64.decode(pem, Base64.DEFAULT)

        fun tlv(i: Int): Triple<Byte, Int, Int> {
            val tag = d[i]
            val b = d[i + 1].toInt() and 0xff
            if (b < 0x80) return Triple(tag, i + 2, b)
            val n = b and 0x7f
            var v = 0
            for (k in 1..n) v = (v shl 8) or (d[i + 1 + k].toInt() and 0xff)
            return Triple(tag, i + 2 + n, v)
        }

        var (_, bodyStart, bodyLen) = tlv(0)
        var i = bodyStart
        var (_, start1, len1) = tlv(i)
        val n = BigInteger(1, d.copyOfRange(start1, start1 + len1))
        i = start1 + len1
        var (_, start2, len2) = tlv(i)
        val e = BigInteger(1, d.copyOfRange(start2, start2 + len2))
        KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
    }
}

object Aes {
    fun rsaEncryptChunk(raw: ByteArray): String {
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.ENCRYPT_MODE, Keys.serverPub)
        val out = ByteArrayOutputStream()
        for (i in raw.indices step 117)
            out.write(c.doFinal(raw, i, minOf(117, raw.size - i)))
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun rsaDecryptBlocks(raw: ByteArray): ByteArray {
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.DECRYPT_MODE, Keys.clientPriv)
        val out = ByteArrayOutputStream()
        for (i in raw.indices step 128)
            out.write(c.doFinal(raw, i, minOf(128, raw.size - i)))
        return out.toByteArray()
    }

    fun wrap(payload: ByteArray): Pair<ByteArray, String> {
        val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOf(16)))
        val body = Base64.encodeToString(c.doFinal(payload), Base64.NO_WRAP)
        return body.toByteArray(Charsets.UTF_8) to rsaEncryptChunk(key)
    }

    fun unwrap(body: ByteArray, tokenHdr: String): ByteArray {
        val key = rsaDecryptBlocks(Base64.decode(tokenHdr, Base64.DEFAULT)).toString(Charsets.US_ASCII).encodeToByteArray()
        val d = Cipher.getInstance("AES/CBC/PKCS5Padding")
        d.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOfRange(0, 16)))
        return d.doFinal(Base64.decode(body, Base64.DEFAULT))
    }
}

object Net {
    const val UA = "Dart/3.12 (dart:io)"
    const val DEVICE_ID = "4422b395ac08fca96924d08112acbe2b96f4c718"
    const val VERSION = "2.6.0+187"
    const val FLAI_ID = "211648"
    const val BASE = "https://iapi.njwebguide.com"
    val BASES = listOf(BASE, "https://iapi.zerkworld.com")

    @Volatile
    var PROXY_HOST = "127.0.0.1"

    @Volatile
    var PROXY_PORT = 8899

    @Volatile
    var PROXY_AUTH = "flai_proxy_user1:Kodp220ckwxioewxc"

    fun headers(token: String?): MutableMap<String, String> {
        val h = linkedMapOf(
            "User-Agent" to UA,
            "deviceid" to DEVICE_ID,
            "version" to VERSION,
            "channel" to "official",
            "os" to "android",
            "flaiid" to FLAI_ID,
            "theme" to "light",
            "accept-language" to "zh-Hans",
        )
        if (token != null) h["Authorization"] = "bearer $token"
        return h
    }

    private val trustCtx = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }), SecureRandom())
    }

    private fun tunnelFactory(host: String, timeout: Int): SSLSocketFactory {
        val ctx = trustCtx
        return object : SSLSocketFactory() {
            fun tunneled(): Socket {
                val s = Socket()
                s.connect(InetSocketAddress(PROXY_HOST, PROXY_PORT), timeout)
                s.soTimeout = timeout
                val auth = "Basic " + Base64.encodeToString(PROXY_AUTH.toByteArray(), Base64.NO_WRAP)
                s.getOutputStream().write(
                    ("CONNECT $host:443 HTTP/1.1\r\nHost: $host:443\r\n" +
                            "Proxy-Authorization: $auth\r\n\r\n").toByteArray()
                )
                val buf = StringBuilder()
                val ins = s.getInputStream()
                while (!buf.contains("\r\n\r\n")) {
                    val b = ins.read()
                    if (b < 0) throw IOException("proxy closed")
                    buf.append(b.toChar())
                }
                if (!buf.lines().first().contains(" 200 ")) throw IOException("proxy: ${buf.take(60)}")
                return s
            }

            fun wrap(s: Socket): Socket = ctx.socketFactory.createSocket(s, host, 443, true)

            override fun getDefaultCipherSuites(): Array<String> = arrayOf()
            override fun getSupportedCipherSuites(): Array<String> = arrayOf()
            override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean) = wrap(tunneled())
            override fun createSocket(host: String?, port: Int) = wrap(tunneled())
            override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int) = wrap(tunneled())
            override fun createSocket(address: java.net.InetAddress?, port: Int) = wrap(tunneled())
            override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int) = wrap(tunneled())
        }
    }

    private fun conn(url: String, token: String?, timeout: Int, extra: Map<String, String>? = null): HttpURLConnection {
        val u = URL(url)
        val c = u.openConnection() as HttpURLConnection
        if (url.startsWith("https://")) {
            val https = c as javax.net.ssl.HttpsURLConnection
            https.sslSocketFactory = tunnelFactory(u.host, timeout)
        }
        headers(token).forEach { (k, v) -> c.setRequestProperty(k, v) }
        extra?.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.connectTimeout = timeout
        c.readTimeout = timeout
        return c
    }

    fun request(method: String, url: String, body: ByteArray?, token: String?, timeout: Int = 120000, extra: Map<String, String>? = null): Triple<Int, ByteArray, Map<String, List<String>>> {
        val c = conn(url, token, timeout, extra)
        c.requestMethod = method
        if (body != null) {
            c.doOutput = true
            c.getOutputStream().use { it.write(body); it.flush() }
        }
        return try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            Triple(code, stream?.readBytes() ?: ByteArray(0), c.headerFields)
        } finally {
            c.disconnect()
        }
    }

    fun directGet(url: String, timeout: Int = 15000): ByteArray? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            try {
                if (c.responseCode in 200..299) c.inputStream.readBytes() else null
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun jdecode(raw: ByteArray): org.json.JSONObject = try {
        org.json.JSONObject(raw.decodeToString())
    } catch (e: Exception) {
        org.json.JSONObject()
    }
}

object Flai {
    fun api(method: String, path: String, obj: org.json.JSONObject?, token: String?, base: String): Pair<Int, ByteArray> {
        val h = Net.headers(token).toMutableMap()
        if (path.startsWith("/v2") && h.containsKey("Authorization"))
            h["Authorization"] = h["Authorization"]!!.replaceFirst("bearer", "Bearer")
        var body: ByteArray? = null
        if (obj != null) {
            val payload = obj.toString().toByteArray(Charsets.UTF_8)
            val (b, tok) = Aes.wrap(payload)
            body = b
            h["Token"] = tok
            h["Content-Type"] = "application/json"
        }
        val (st, resp, hdrs) = Net.request(method, base + path, body, token)
        val tokHdr = hdrs["Token"]?.firstOrNull() ?: hdrs["token"]?.firstOrNull()
        val plain = when {
            tokHdr != null -> try { Aes.unwrap(resp, tokHdr) } catch (e: Exception) { resp }
            resp.isNotEmpty() && resp.size % 128 == 0 -> try { Aes.rsaDecryptBlocks(resp) } catch (e: Exception) { resp }
            else -> resp
        }
        return st to plain
    }

    fun login(email: String, pwd: String, base: String): String? {
        val obj = org.json.JSONObject()
            .put("email", email)
            .put("password", pwd)
            .put("remember_password", true)
            .put("device_id", Net.DEVICE_ID)
        val (st, raw) = api("POST", "/user/login", obj, null, base)
        val data = Net.jdecode(raw).optJSONObject("data")
        return data?.optString("token")?.takeIf { it.length > 20 }
    }

    fun my(token: String, base: String): org.json.JSONObject? {
        val (st, raw) = api("GET", "/user/my", null, token, base)
        val d = Net.jdecode(raw)
        return if (st == 200 && d.optInt("code") == 200) d.optJSONObject("data") else null
    }

    fun voiceList(token: String, base: String, page: Int = 1): org.json.JSONArray? {
        val (st, raw) = api("GET", "/voice/index?p=$page", null, token, base)
        val d = Net.jdecode(raw)
        return if (st == 200 && d.optInt("code") == 200) d.optJSONArray("data") else null
    }

    fun voiceInfoByCode(code: String, token: String, base: String): org.json.JSONObject? {
        val (st, raw) = api("GET", "/voice/info?code=$code&from_page=1", null, token, base)
        val d = Net.jdecode(raw)
        return if (st == 200 && d.optInt("code") == 200) d.optJSONObject("data") else null
    }

    fun tts(text: String, last: List<String>, speaker: String, speed: Double, token: String, base: String): ByteArray? {
        val obj = org.json.JSONObject()
            .put("content", org.json.JSONArray().put(text))
            .put("last_content", org.json.JSONArray(last))
            .put("speaker", speaker)
            .put("speed", speed)
        val (body, tok) = Aes.wrap(obj.toString().toByteArray(Charsets.UTF_8))
        val extra = mapOf(
            "token" to tok,
            "Content-Type" to "text/plain; charset=utf-8",
        )
        val (st, data) = Net.request("POST", "$base/tts", body, token, 120000, extra)
        if (st == 200 && data.isNotEmpty()) return data
        return null
    }
}

class TokenPool(val base: String, val report: (String) -> Unit) {
    inner class Entry(val token: String, @Volatile var ok: Boolean?, var user: String?)

    private val entries = mutableListOf<Entry>()
    private val lock = Any()
    private var idx = 0

    fun add(token: String) {
        synchronized(lock) {
            if (token.length > 40 && entries.none { it.token == token })
                entries.add(Entry(token, null, null))
        }
    }

    fun size(): Int = entries.count { it.ok == true }

    fun total(): Int = entries.size

    fun validate(): Boolean {
        synchronized(lock) {
            for (e in entries) {
                try {
                    val u = Flai.my(e.token, base)
                    if (u != null) {
                        e.ok = true
                        e.user = "${u.optString("nickname")} flai_id=${u.optString("flai_id")}"
                        report("[ok] ${e.user}  ${e.token.take(14)}...")
                    } else {
                        e.ok = false
                        report("[x] ${e.token.take(14)}... 无效")
                    }
                } catch (ex: Exception) {
                    e.ok = false
                    report("[x] ${e.token.take(14)}... ${ex.javaClass.simpleName}: ${ex.message}")
                }
            }
        }
        return size() > 0
    }

    fun next(): String? {
        synchronized(lock) {
            val valid = entries.filter { it.ok == true }
            if (valid.isEmpty()) return null
            val e = valid[idx % valid.size]
            idx++
            return e.token
        }
    }

    fun drop(token: String) {
        synchronized(lock) {
            entries.firstOrNull { it.token == token && it.ok == true }?.let {
                it.ok = false
                report("[!] token 剔除 ${token.take(14)}... 剩 ${size()}")
            }
        }
    }
}

fun md5hex(data: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }
