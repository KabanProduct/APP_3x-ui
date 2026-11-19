package dev.threex.mobile.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class PanelApiTest {
    private fun editableClient() = JSONObject("""{"client":{"id":5,"uuid":"f47ac10b-58cc-4372-a567-0e02b2c3d479","email":"phone","subId":"sub","enable":true,"totalGB":0,"expiryTime":0,"allowedIPs":""},"inboundIds":[1]}""")
    private fun editableInbounds() = listOf(1, 2).map { JSONObject().put("id", it).put("protocol", "vless") }
    @Test fun editRenamesThenAttachesBeforeDetachingUsingNewEmail() = runBlocking {
        server().use { server ->
            server.enqueue(response(editableClient().toString()))
            repeat(3) { server.enqueue(response("null")) }
            val api = PanelApi(ServerProfile(name="test", baseUrl=server.localUrl("/secret/"), token="test"), true)
            val edit = dev.threex.mobile.domain.ClientEdit.from(editableClient())
            api.updateClient(edit, edit.initial.copy(email="renamed", inboundIds=setOf(2)), editableInbounds())
            assertEquals("/secret/panel/api/clients/get/phone", server.takeRequest().path)
            val update = server.takeRequest()
            assertEquals("/secret/panel/api/clients/update/phone", update.path)
            assertEquals("renamed", JSONObject(update.body.readUtf8()).getString("email"))
            assertEquals("/secret/panel/api/clients/renamed/attach", server.takeRequest().path)
            assertEquals("/secret/panel/api/clients/renamed/detach", server.takeRequest().path)
        }
    }
    @Test fun attachmentFailureReportsPartialSaveAndDoesNotDetach() = runBlocking {
        server().use { server ->
            server.enqueue(response(editableClient().toString())); server.enqueue(response("null"))
            server.enqueue(MockResponse().setBody("""{"success":false,"msg":"attach failed"}"""))
            val api = PanelApi(ServerProfile(name="test", baseUrl=server.localUrl("/"), token="test"), true)
            val edit = dev.threex.mobile.domain.ClientEdit.from(editableClient())
            try { api.updateClient(edit, edit.initial.copy(inboundIds=setOf(2)), editableInbounds()); fail("Expected partial save") }
            catch (_: ClientPartiallySavedException) { }
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun loadsInboundDetailWithNestedSettingsAndDeletesClient() = runBlocking {
        server().use { server ->
            server.enqueue(response("""{"id":7,"settings":{"clients":[]},"streamSettings":{"network":"tcp"},"futureField":123}"""))
            server.enqueue(response("null"))
            val api = PanelApi(ServerProfile(name="test", baseUrl=server.localUrl("/secret/"), token="test"), true)
            val inbound = api.inboundForEdit(7)
            assertEquals(123, inbound.getInt("futureField")); assertEquals("tcp", inbound.getJSONObject("streamSettings").getString("network"))
            api.deleteClient("phone")
            assertEquals("/secret/panel/api/inbounds/get/7", server.takeRequest().path)
            val deletion = server.takeRequest()
            assertEquals("POST", deletion.method); assertEquals("/secret/panel/api/clients/del/phone", deletion.path)
        }
    }
    @Test fun rejectsMismatchedInboundDetail() = runBlocking {
        server().use { server ->
            server.enqueue(response("""{"id":9}"""))
            val api = PanelApi(ServerProfile(name="test", baseUrl=server.localUrl("/"), token="test"), true)
            try { api.inboundForEdit(7); fail("Expected id validation") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun getsDefaultCandidatesFromServerAndPostsClientAttachments() = runBlocking {
        server().use { server ->
            server.enqueue(response("""[{"target":"www.example.com:443","host":"www.example.com","feasible":true}]"""))
            server.enqueue(response("null"))
            val api = PanelApi(ServerProfile(name = "test", baseUrl = server.localUrl("/secret/"), token = "test-token"), true)
            assertEquals("www.example.com", api.realitySuggestions().single().sni)
            val scan = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/secret/panel/api/server/scanRealityTargets", scan.path)
            assertEquals("POST", scan.method); assertEquals("targets=", scan.body.readUtf8())
            val body = JSONObject("""{"client":{"email":"phone","id":"f47ac10b-58cc-4372-a567-0e02b2c3d479","flow":"xtls-rprx-vision"},"inboundIds":[1,2]}""")
            api.createClient(body)
            val create = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/secret/panel/api/clients/add", create.path)
            val sent = JSONObject(create.body.readUtf8())
            assertEquals(2, sent.getJSONArray("inboundIds").length())
            assertEquals("xtls-rprx-vision", sent.getJSONObject("client").getString("flow"))
        }
    }
    private fun server() = MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
    private fun MockWebServer.localUrl(path: String) = url(path).newBuilder().host("127.0.0.1").build().toString()
    private fun response(obj: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("{\"success\":true,\"obj\":$obj}")

    @Test fun loginKeepsBasePathCookiesAndRefreshesCsrf() = runBlocking {
        server().use { server ->
            server.enqueue(response("\"before\"").setHeader("Set-Cookie", "session=one; Path=/secret/; HttpOnly"))
            server.enqueue(response("null").setHeader("Set-Cookie", "session=two; Path=/secret/; HttpOnly"))
            server.enqueue(response("\"after\""))
            server.enqueue(response("null"))
            val api = PanelApi(ServerProfile(name="test", baseUrl=server.localUrl("/secret/"), username="admin", password="p&=secret"), true)
            api.login("123456")
            api.setInboundEnabled(7, false)
            val csrf = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/secret/csrf-token", csrf.path)
            val login = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/secret/login", login.path)
            assertEquals("before", login.getHeader("X-CSRF-Token"))
            assertEquals("session=one", login.getHeader("Cookie"))
            assertTrue(login.body.readUtf8().contains("twoFactorCode=123456"))
            assertEquals("session=two", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Cookie"))
            val mutation = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/secret/panel/api/inbounds/setEnable/7", mutation.path)
            assertEquals("after", mutation.getHeader("X-CSRF-Token"))
            assertFalse(JSONObject(mutation.body.readUtf8()).getBoolean("enable"))
        }
    }
    @Test fun bearerAuthSkipsLoginAndEscapesClientIdentifier() = runBlocking {
        server().use { server ->
            server.enqueue(response("[]"))
            val api = PanelApi(ServerProfile(name="test", baseUrl=server.localUrl("/base/"), token="private-token"), true)
            api.login(); api.links("alice+phone@example.com")
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer private-token", request.getHeader("Authorization"))
            assertEquals("/base/panel/api/clients/links/alice+phone@example.com", request.path)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun redirectCannotLeakAuthenticationToAnotherServer() = runBlocking {
        server().use { source -> server().use { target ->
            source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/collect")))
            val api = PanelApi(ServerProfile(name="test", baseUrl=source.localUrl("/"), token="private-token"), true)
            try { api.get("server", "status"); fail("Redirect must fail") } catch (_: PanelException) { }
            assertEquals(0, target.requestCount)
        } }
    }
    @Test fun apiFailureOnHttp200IsNotSuccess() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setBody("{\"success\":false,\"msg\":\"denied\"}"))
            val api = PanelApi(ServerProfile(name="test", baseUrl=server.localUrl("/"), token="t"), true)
            try { api.setClientEnabled("alice", true); fail("Must reject failure envelope") } catch (e: PanelException) { assertTrue(e.message!!.contains("denied")) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun unsafePanelAddressesAreRejected() {
        listOf("http://example.com/", "https://u:p@example.com/", "https://example.com/?secret=a", "https://example.com/#a").forEach {
            try { panelBaseUrl(it); fail("Must reject $it") } catch (_: IllegalArgumentException) { }
        }
        assertEquals("https://example.com/secret/", panelBaseUrl("https://example.com/secret/panel/").toString())
    }
    private fun certificateHash(c: HeldCertificate) = java.security.MessageDigest.getInstance("SHA-256").digest(c.certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
    @Test fun sshPinnedCertificateIsAcceptedWithMatchingHostname() = runBlocking {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        server().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
            server.enqueue(response("{\"panelVersion\":\"3.7.0\"}"))
            val api = PanelApi(ServerProfile(name="pinned", baseUrl=server.localUrl("/"), token="t", certificatePin=certificateHash(cert)))
            assertEquals("3.7.0", (api.get("server", "status") as JSONObject).getString("panelVersion"))
        }
    }
    @Test fun wrongPinAndWrongHostnameAreRejectedBeforeSendingToken() = runBlocking {
        for (wrongPin in listOf(true, false)) {
            val cert = HeldCertificate.Builder().addSubjectAlternativeName(if (wrongPin) "127.0.0.1" else "different.example").build()
            server().use { server ->
                server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
                val api = PanelApi(ServerProfile(name="pinned", baseUrl=server.localUrl("/"), token="secret", certificatePin=if (wrongPin) "0".repeat(64) else certificateHash(cert)))
                try { api.get("server", "status"); fail("Must reject TLS") } catch (_: javax.net.ssl.SSLException) { }
                assertEquals(0, server.requestCount)
            }
        }
    }
}
