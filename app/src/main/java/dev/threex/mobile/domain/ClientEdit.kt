package dev.threex.mobile.domain

import dev.threex.mobile.data.objectField
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

class ClientEdit(val record: JSONObject, val initial: ClientDraft) {
    val email: String get() = record.getString("email")
    val expiry: Long get() = record.optLong("expiryTime")

    fun validate(draft: ClientDraft, inbounds: List<JSONObject>): JSONObject = draft.payload(inbounds,
        existingExpiry = expiry, preserveFlow = draft.flow == initial.flow && draft.inboundIds == initial.inboundIds)

    // Compare the form against its original values, then apply only the user's changes
    // to a freshly fetched record. Exact timestamps, byte counts and unknown fields survive.
    fun updateBody(draft: ClientDraft, inbounds: List<JSONObject>, fresh: ClientEdit): JSONObject {
        val before = validate(initial, inbounds).getJSONObject("client")
        val after = validate(draft, inbounds).getJSONObject("client")
        val result = JSONObject(fresh.record.toString())
        result.put("id", fresh.record.getString("uuid"))
        result.remove("uuid")
        result.remove("createdAt"); result.remove("updatedAt")
        if (fresh.record.has("createdAt")) result.put("created_at", fresh.record.getLong("createdAt"))
        // ClientRecord stores allowedIPs as JSON text, whereas model.Client expects an array.
        if (result.opt("allowedIPs") is String) {
            val text = result.getString("allowedIPs")
            result.put("allowedIPs", if (text.isBlank()) JSONArray() else JSONArray(text))
        }
        for (key in after.keys()) {
            if (before.opt(key)?.toString() != after.opt(key)?.toString()) {
                if (key == "reverse") {
                    val reverse = fresh.record.objectField("reverse")
                    reverse.put("tag", after.getJSONObject("reverse").getString("tag"))
                    result.put(key, reverse)
                } else result.put(key, after.get(key))
            }
        }
        if (initial.reverseTag.isNotBlank() && draft.reverseTag.isBlank()) result.put("reverse", JSONObject.NULL)
        return result
    }

    companion object {
        fun from(response: JSONObject): ClientEdit {
            val c = response.getJSONObject("client")
            require(c.optString("uuid").isNotBlank()) { "Редактор конфигурации поддерживает клиентов VLESS" }
            val ids = response.getJSONArray("inboundIds")
            fun value(key: String, fallback: String = "0") = c.optString(key, fallback).ifBlank { fallback }
            val initial = ClientDraft(email = c.getString("email"), uuid = c.getString("uuid"), subId = c.optString("subId"),
                inboundIds = (0 until ids.length()).map { ids.getInt(it) }.toSet(), flow = c.optString("flow"), enable = c.optBoolean("enable"),
                limitGiB = BigDecimal(c.optLong("totalGB")).divide(BigDecimal(1073741824L)).stripTrailingZeros().toPlainString(),
                limitIp = value("limitIp"), limitHwid = value("limitHwid"), expiryMode = "keep",
                reset = value("reset"), resetDay = value("resetDay"), resetMax = value("resetMax"),
                trafficReset = value("trafficReset", "never"), trafficResetDay = value("trafficResetDay", "1"),
                tgId = value("tgId"), group = c.optString("group"), comment = c.optString("comment"), reverseTag = c.objectField("reverse").optString("tag"))
            return ClientEdit(JSONObject(c.toString()), initial)
        }
    }
}
