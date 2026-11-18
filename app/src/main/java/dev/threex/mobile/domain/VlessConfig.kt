package dev.threex.mobile.domain

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

class VlessLink(val host: String, val port: Int, val uuid: String, val name: String, val options: Map<String, String>) {
    companion object {
        fun parse(input: String): VlessLink {
            val uri = try { URI(input.trim()) } catch (_: Exception) { error("Некорректная ссылка VLESS") }
            require(uri.scheme == "vless") { "Поддерживаются ссылки vless://" }
            val host = uri.host?.removeSurrounding("[", "]") ?: error("В ссылке отсутствует адрес сервера")
            require(uri.port in 1..65535) { "В ссылке отсутствует корректный порт" }
            val uuid = try { UUID.fromString(uri.userInfo).toString() } catch (_: Exception) { error("Некорректный UUID клиента") }
            val options = mutableMapOf<String, String>()
            uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.forEach {
                val parts = it.split('=', limit = 2)
                val key = decode(parts[0])
                require(key !in options) { "Повторяющийся параметр VLESS" }
                options[key] = decode(parts.getOrElse(1) { "" })
            }
            val transport = options["type"] ?: "tcp"
            require(transport in listOf("tcp", "raw", "xhttp")) { "Пока поддерживаются TCP/RAW и XHTTP" }
            val security = options["security"] ?: "none"
            require(security in listOf("reality", "tls")) { "Для подключения требуется REALITY или TLS" }
            require(options["encryption"].orEmpty() in listOf("", "none")) { "Этот вариант VLESS encryption ещё не поддерживается" }
            require(options["allowInsecure"].orEmpty() !in listOf("1", "true")) { "Отключение проверки TLS не поддерживается" }
            val flow = options["flow"].orEmpty()
            require(flow in listOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")) { "Неизвестный flow" }
            require(transport != "xhttp" || flow.isEmpty()) { "XHTTP не использует Vision flow" }
            require(options["headerType"].orEmpty() in listOf("", "none")) { "TCP HTTP header не поддерживается" }
            if (security == "reality") {
                require(!options["sni"].isNullOrBlank()) { "REALITY требует SNI" }
                require(options["pbk"]?.matches(Regex("[A-Za-z0-9_-]{43}")) == true) { "Некорректный публичный ключ REALITY" }
                require(options["sid"].orEmpty().matches(Regex("(?:[0-9a-fA-F]{2}){0,8}"))) { "Некорректный short ID" }
            }
            require(options["fm"].isNullOrBlank() && options["ech"].isNullOrBlank() && options["pqv"].isNullOrBlank()) { "FinalMask, ECH и ML-DSA в ссылках пока не поддерживаются" }
            if (!options["extra"].isNullOrBlank()) {
                require(transport == "xhttp") { "extra допустим только для XHTTP" }
                val extra = try { JSONObject(options.getValue("extra")) } catch (_: Exception) { throw IllegalArgumentException("Некорректный XHTTP extra") }
                val allowed = setOf("mode", "xPaddingBytes", "xPaddingObfsMode", "xPaddingKey", "xPaddingHeader", "xPaddingPlacement", "xPaddingMethod", "uplinkHTTPMethod", "sessionIDPlacement", "sessionIDKey", "sessionIDTable", "sessionIDLength", "sessionPlacement", "sessionKey", "seqPlacement", "seqKey", "uplinkDataPlacement", "uplinkDataKey", "scMaxEachPostBytes", "headers", "xmux", "scMinPostsIntervalMs", "noGRPCHeader")
                require(extra.keys().asSequence().all { it in allowed }) { "Этот XHTTP extra содержит неподдерживаемые поля" }
            }
            require(options["mode"].orEmpty() in listOf("", "auto", "packet-up", "stream-up", "stream-one")) { "Неизвестный режим XHTTP" }
            return VlessLink(host, uri.port, uuid, decode(uri.rawFragment.orEmpty()).ifBlank { host }, options)
        }
        private fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }
    fun xrayConfig(): String {
        val network = options["type"] ?: "tcp"
        val security = options.getValue("security")
        val stream = JSONObject().put("network", network).put("security", security)
        if (security == "reality") {
            stream.put("realitySettings", JSONObject().put("serverName", options["sni"])
                .put("fingerprint", options["fp"] ?: "chrome").put("publicKey", options["pbk"])
                .put("shortId", options["sid"].orEmpty()).put("spiderX", options["spx"] ?: "/"))
        } else {
            val tls = JSONObject().put("serverName", options["sni"] ?: host).put("fingerprint", options["fp"] ?: "chrome").put("allowInsecure", false)
            options["alpn"]?.takeIf { it.isNotBlank() }?.let { tls.put("alpn", JSONArray(it.split(','))) }
            stream.put("tlsSettings", tls)
        }
        if (network == "xhttp") {
            val extra = options["extra"]?.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
            // 3x-ui exports mode both on the URL and in extra; Xray expects it outside extra.
            val mode = options["mode"] ?: extra.optString("mode", "auto")
            val xhttp = JSONObject().put("path", options["path"] ?: "/").put("host", options["host"].orEmpty()).put("mode", mode)
            if (extra.length() > 0) xhttp.put("extra", extra)
            stream.put("xhttpSettings", xhttp)
        }
        val user = JSONObject().put("id", uuid).put("encryption", "none").put("flow", options["flow"].orEmpty())
        val proxy = JSONObject().put("tag", "proxy").put("protocol", "vless").put("streamSettings", stream)
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(user)))))
        val tun = JSONObject().put("tag", "tun").put("protocol", "tun")
            .put("settings", JSONObject().put("name", "three-x").put("MTU", 1500))
        return JSONObject().put("log", JSONObject().put("loglevel", "none"))
            .put("stats", JSONObject()).put("policy", JSONObject().put("system", JSONObject().put("statsOutboundUplink", true).put("statsOutboundDownlink", true)))
            .put("inbounds", JSONArray().put(tun)).put("outbounds", JSONArray().put(proxy))
            .toString()
    }
}
