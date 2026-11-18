package dev.threex.mobile.domain

import dev.threex.mobile.data.objectField
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID

data class ClientDraft(
    val email: String = "", val uuid: String = UUID.randomUUID().toString(),
    val subId: String = UUID.randomUUID().toString().replace("-", ""),
    val inboundIds: Set<Int> = emptySet(), val flow: String = "", val enable: Boolean = true,
    val limitGiB: String = "0", val limitIp: String = "0", val limitHwid: String = "0",
    val expiryMode: String = "never", val days: String = "30", val expiryDate: String = "",
    val reset: String = "0", val resetDay: String = "0", val resetMax: String = "0",
    val trafficReset: String = "never", val trafficResetDay: String = "1",
    val tgId: String = "0", val group: String = "", val comment: String = "", val reverseTag: String = "",
) {
    fun payload(inbounds: List<JSONObject>, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): JSONObject {
        fun identifier(value: String, label: String): String {
            val clean = value.trim()
            require(clean.isNotEmpty() && clean.none { it.isWhitespace() || it.isISOControl() || it in "/\\" }) { "$label: недопустимое имя" }
            return clean
        }
        fun number(value: String, label: String, max: Int = Int.MAX_VALUE): Int =
            value.toIntOrNull()?.takeIf { it in 0..max } ?: throw IllegalArgumentException("$label: введите число от 0 до $max")
        val name = identifier(email, "Имя клиента")
        require(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(uuid)) { "Введите корректный UUID" }
        require(inboundIds.isNotEmpty()) { "Выберите хотя бы одно входящее подключение" }
        val selected = inboundIds.map { id -> inbounds.firstOrNull { it.optInt("id") == id } ?: error("Входящее $id больше недоступно") }
        require(selected.all { it.optString("protocol") == "vless" }) { "Эта форма создаёт клиентов VLESS" }
        require(flow in listOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")) { "Неизвестный Flow" }
        require(flow.isEmpty() || selected.all(::supportsClientFlow)) { "Выбранный Flow несовместим с частью входящих. Выберите «Без Flow» или измените привязки." }
        val gib = limitGiB.replace(',', '.').toBigDecimalOrNull()
        require(gib != null && gib >= BigDecimal.ZERO && gib <= BigDecimal("1000000")) { "Лимит трафика: от 0 до 1000000 ГиБ" }
        val bytes = try { gib.multiply(BigDecimal(1073741824L)).longValueExact() } catch (_: ArithmeticException) { throw IllegalArgumentException("Лимит трафика содержит слишком много дробных знаков") }
        val expiry = when (expiryMode) {
            "never" -> 0L
            "days", "delayed" -> {
                val count = number(days, "Срок в днях", 36500)
                require(count > 0) { "Срок должен быть больше нуля" }
                if (expiryMode == "delayed") -count * 86400000L else now + count * 86400000L
            }
            "date" -> {
                val timestamp = try { LocalDateTime.parse(expiryDate.trim(), DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)).atZone(zone).toInstant().toEpochMilli() }
                    catch (_: Exception) { throw IllegalArgumentException("Дата: ГГГГ-ММ-ДД ЧЧ:ММ по времени телефона") }
                require(timestamp > now) { "Дата окончания должна быть в будущем" }; timestamp
            }
            else -> error("Неизвестный режим срока")
        }
        require(trafficReset in listOf("never", "hourly", "daily", "weekly", "monthly")) { "Неизвестный цикл сброса трафика" }
        val trafficDay = number(trafficResetDay, "День сброса трафика", 31)
        require(trafficDay >= 1) { "День сброса трафика: от 1 до 31" }
        val client = JSONObject().put("email", name).put("id", uuid).put("subId", identifier(subId, "ID подписки"))
            .put("flow", flow).put("security", "auto").put("enable", enable).put("totalGB", bytes).put("expiryTime", expiry)
            .put("limitIp", number(limitIp, "Лимит IP")).put("limitHwid", number(limitHwid, "Лимит HWID"))
            .put("reset", number(reset, "Период продления", 36500)).put("resetDay", number(resetDay, "День продления", 31))
            .put("resetMax", number(resetMax, "Количество продлений"))
            .put("trafficReset", trafficReset).put("trafficResetDay", trafficDay)
            .put("tgId", tgId.toLongOrNull() ?: throw IllegalArgumentException("Telegram ID должен быть целым числом"))
            .put("group", group.trim()).put("comment", comment)
        if (reverseTag.isNotBlank()) client.put("reverse", JSONObject().put("tag", reverseTag.trim()))
        return JSONObject().put("client", client).put("inboundIds", JSONArray(inboundIds.sorted()))
    }
}

fun supportsClientFlow(inbound: JSONObject): Boolean {
    if (inbound.optString("protocol") != "vless" || inbound.optBoolean("disableFlow")) return false
    val stream = inbound.objectField("streamSettings")
    return when (stream.optString("network")) {
        "tcp", "raw" -> stream.optString("security") in listOf("tls", "reality")
        "xhttp" -> inbound.objectField("settings").let { settings ->
            listOf("encryption", "decryption").any { key -> settings.optString(key).let { it.isNotBlank() && it != "none" } }
        }
        else -> false
    }
}
