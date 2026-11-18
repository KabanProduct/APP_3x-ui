package dev.threex.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// No generated toString: credentials must never appear in diagnostics.
class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val certificatePin: String = "",
) {
    fun json() = JSONObject().put("id", id).put("name", name).put("baseUrl", baseUrl)
        .put("username", username).put("password", password).put("token", token).put("certificatePin", certificatePin)
    companion object {
        fun from(j: JSONObject) = ServerProfile(j.getString("id"), j.getString("name"), j.getString("baseUrl"), j.optString("username"), j.optString("password"), j.optString("token"), j.optString("certificatePin"))
    }
}

class VpnProfile(val id: String = UUID.randomUUID().toString(), val name: String, val uri: String) {
    fun json() = JSONObject().put("id", id).put("name", name).put("uri", uri)
    companion object { fun from(j: JSONObject) = VpnProfile(j.getString("id"), j.getString("name"), j.getString("uri")) }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONObject.objectField(name: String): JSONObject = when (val value = opt(name)) {
    is JSONObject -> value
    is String -> JSONObject(value)
    else -> JSONObject()
}

data class PanelSnapshot(val status: JSONObject, val inbounds: List<JSONObject>, val clients: List<JSONObject>) {
    val version: String get() = status.optString("panelVersion").removePrefix("v")
    val canWrite: Boolean get() = version == "3.7.0"
}
