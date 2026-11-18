package dev.threex.mobile.domain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RealityCandidateTest {
    @Test fun derivesHostWithoutPanelPortOrSecretPath() {
        assertEquals("203.0.113.10", suggestedPublicHost("https://203.0.113.10:2053/secret/"))
        assertEquals("panel.example.com", suggestedPublicHost("https://panel.example.com:443/path/"))
        assertEquals("2001:db8::1", suggestedPublicHost("https://[2001:db8::1]:2053/path/"))
        assertEquals("", suggestedPublicHost(null))
    }
    @Test fun onlyOffersFeasiblePublicServerResultsAndKeepsPairsTogether() {
        fun row() = JSONObject().put("target", "target.example:443").put("host", "sni.example").put("feasible", true).put("latencyMs", 45)
        val rows = JSONArray().put(row()).put(row()).put(row().put("feasible", false))
            .put(row().put("privateTarget", true)).put(row().put("target", "target.example:99999"))
        assertEquals(listOf(RealityCandidate("sni.example", "target.example:443", 45)), realityCandidates(rows))
    }
}
