package dev.threex.mobile.domain

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

data class QuickSetup(
    val preset: ConnectionPreset,
    val name: String,
    val clientName: String,
    val publicHost: String,
    val port: Int,
    val sni: String,
    val target: String,
    val certificate: String = "",
    val certificateKey: String = "",
)

object PresetBuilder {
    fun validate(input: QuickSetup) {
        require(input.name.isNotBlank() && input.clientName.matches(Regex("[A-Za-z0-9_.@-]{1,64}"))) { "Укажите имя подключения и имя клиента (латиница, цифры, . _ @ -)" }
        require(input.port in 1..65535) { "Порт должен быть от 1 до 65535" }
        require(input.publicHost.isNotBlank() && input.publicHost.none { it.isWhitespace() || it in "/?#@" }) { "Укажите публичный IP или домен сервера без порта" }
        require(input.sni.matches(Regex("[A-Za-z0-9.-]+\\.[A-Za-z0-9-]+"))) { "Укажите домен SNI" }
        if (input.preset == ConnectionPreset.XHTTP_TLS) {
            require(input.certificate.startsWith('/') && input.certificateKey.startsWith('/')) { "Нужны абсолютные пути сертификата и ключа на сервере" }
        } else require(input.target.matches(Regex("[A-Za-z0-9.-]+:[0-9]{1,5}")) && input.target.substringAfterLast(':').toInt() in 1..65535) { "Укажите REALITY target в формате domain:443" }
    }
    fun client(email: String, flow: String = "", limitGiB: Long = 0, days: Int = 0): JSONObject {
        require(email.matches(Regex("[A-Za-z0-9_.@-]{1,64}"))) { "Некорректное имя клиента" }
        require(limitGiB in 0..1_000_000 && days in 0..36500) { "Некорректный лимит или срок" }
        return JSONObject().put("id", UUID.randomUUID().toString()).put("email", email).put("enable", true)
            .put("flow", flow).put("limitIp", 0).put("totalGB", Math.multiplyExact(limitGiB, 1073741824L))
            .put("expiryTime", if (days == 0) 0L else System.currentTimeMillis() + days * 86400000L)
            .put("subId", randomHex(16)).put("tgId", 0).put("reset", 0)
    }
    fun inbound(input: QuickSetup, keys: JSONObject? = null): JSONObject {
        validate(input)
        val vision = input.preset == ConnectionPreset.REALITY_VISION
        val stream = JSONObject().put("network", if (vision) "tcp" else "xhttp")
        if (!vision) stream.put("xhttpSettings", JSONObject().put("path", "/${randomHex(12)}").put("mode", "auto"))
        if (input.preset == ConnectionPreset.XHTTP_TLS) {
            stream.put("security", "tls").put("tlsSettings", JSONObject().put("serverName", input.sni)
                .put("minVersion", "1.2").put("certificates", JSONArray().put(JSONObject()
                    .put("certificateFile", input.certificate).put("keyFile", input.certificateKey))))
        } else {
            require(!keys?.optString("privateKey").isNullOrBlank() && !keys?.optString("publicKey").isNullOrBlank()) { "Сервер не выдал ключи REALITY" }
            stream.put("security", "reality").put("realitySettings", JSONObject()
                .put("show", false).put("dest", input.target).put("xver", 0).put("serverNames", JSONArray(listOf(input.sni)))
                .put("privateKey", keys!!.getString("privateKey")).put("shortIds", JSONArray(listOf(randomHex(8))))
                .put("settings", JSONObject().put("publicKey", keys.getString("publicKey")).put("fingerprint", "chrome").put("serverName", input.sni).put("spiderX", "/")))
        }
        return JSONObject().put("remark", input.name).put("enable", true).put("listen", "").put("port", input.port)
            .put("protocol", "vless").put("total", 0).put("expiryTime", 0)
            .put("shareAddr", input.publicHost).put("shareAddrStrategy", "custom")
            .put("settings", JSONObject().put("decryption", "none").put("clients", JSONArray().put(client(input.clientName, if (vision) "xtls-rprx-vision" else ""))))
            .put("streamSettings", stream).put("sniffing", JSONObject().put("enabled", false))
    }
    private fun randomHex(size: Int): String = ByteArray(size).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
}
