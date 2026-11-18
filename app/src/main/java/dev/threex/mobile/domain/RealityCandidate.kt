package dev.threex.mobile.domain

import dev.threex.mobile.data.objects
import dev.threex.mobile.data.panelBaseUrl
import org.json.JSONArray

data class RealityCandidate(val sni: String, val target: String, val latencyMs: Int)

fun suggestedPublicHost(baseUrl: String?): String = baseUrl?.let { panelBaseUrl(it).host.removeSurrounding("[", "]") }.orEmpty()

fun realityCandidates(results: JSONArray): List<RealityCandidate> = results.objects().mapNotNull {
    val sni = it.optString("host")
    val target = it.optString("target")
    if (!it.optBoolean("feasible") || it.optBoolean("privateTarget") ||
        !sni.matches(Regex("[A-Za-z0-9.-]+\\.[A-Za-z0-9-]+")) ||
        !target.matches(Regex("[A-Za-z0-9.-]+:[0-9]{1,5}")) || target.substringAfterLast(':').toIntOrNull() !in 1..65535) null
    else RealityCandidate(sni, target, it.optInt("latencyMs"))
}.distinctBy { it.sni to it.target }
