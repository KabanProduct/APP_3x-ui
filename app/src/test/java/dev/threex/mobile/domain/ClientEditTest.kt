package dev.threex.mobile.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClientEditTest {
    private fun response() = JSONObject("""{"client":{"id":42,"uuid":"f47ac10b-58cc-4372-a567-0e02b2c3d479","email":"phone","subId":"subscription","enable":true,"totalGB":1234567891,"expiryTime":1600000000123,"flow":"xtls-rprx-vision","security":"custom","password":"keep-password","allowedIPs":"[]","reverse":{"tag":"old","extra":1},"futureField":{"keep":true}},"inboundIds":[7]}""")
    private val inbounds = listOf(JSONObject("""{"id":7,"protocol":"vless","streamSettings":{"network":"tcp","security":"reality"}}"""))
    @Test fun unchangedEditPreservesExactLimitsExpiredTimestampAndUnknownFields() {
        val edit = ClientEdit.from(response())
        val body = edit.updateBody(edit.initial, inbounds, edit)
        assertEquals(1234567891L, body.getLong("totalGB"))
        assertEquals(1600000000123L, body.getLong("expiryTime"))
        assertEquals("f47ac10b-58cc-4372-a567-0e02b2c3d479", body.getString("id"))
        assertEquals("custom", body.getString("security"))
        assertEquals("keep-password", body.getString("password"))
        assertTrue(body.getJSONObject("futureField").getBoolean("keep"))
        assertEquals(0, body.getJSONArray("allowedIPs").length())
        assertEquals(1, body.getJSONObject("reverse").getInt("extra"))
    }
    @Test fun changingCommentDoesNotOverwriteConcurrentServerValues() {
        val edit = ClientEdit.from(response())
        val freshResponse = response().apply { getJSONObject("client").put("totalGB", 99L).put("expiryTime", -123456789L) }
        val body = edit.updateBody(edit.initial.copy(comment = "changed"), inbounds, ClientEdit.from(freshResponse))
        assertEquals("changed", body.getString("comment"))
        assertEquals(99L, body.getLong("totalGB")); assertEquals(-123456789L, body.getLong("expiryTime"))
    }
    @Test fun explicitlyChangesLimitAndClearsExpiryAndReverse() {
        val edit = ClientEdit.from(response())
        val body = edit.updateBody(edit.initial.copy(limitGiB = "0", expiryMode = "never", reverseTag = ""), inbounds, edit)
        assertEquals(0L, body.getLong("totalGB")); assertEquals(0L, body.getLong("expiryTime"))
        assertTrue(body.isNull("reverse"))
    }
    @Test fun changingReverseTagPreservesOtherReverseFields() {
        val edit = ClientEdit.from(response())
        val reverse = edit.updateBody(edit.initial.copy(reverseTag = "new"), inbounds, edit).getJSONObject("reverse")
        assertEquals("new", reverse.getString("tag")); assertEquals(1, reverse.getInt("extra"))
    }
    @Test fun existingOrphanCanBeEditedWithoutInventingAnAttachment() {
        val edit = ClientEdit.from(response().put("inboundIds", org.json.JSONArray()))
        assertEquals("phone", edit.updateBody(edit.initial, emptyList(), edit).getString("email"))
    }
    @Test fun preservesUnchangedFlowOnMixedTransportsButValidatesChangedFlow() {
        val edit = ClientEdit.from(response())
        val xhttp = listOf(JSONObject("""{"id":7,"protocol":"vless","streamSettings":{"network":"xhttp","security":"reality"}}"""))
        assertEquals("xtls-rprx-vision", edit.updateBody(edit.initial.copy(comment = "ok"), xhttp, edit).getString("flow"))
        assertThrows(IllegalArgumentException::class.java) { edit.validate(edit.initial.copy(flow = "xtls-rprx-vision-udp443"), xhttp) }
    }
}
