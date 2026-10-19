package dev.cybercore.holodrive.photos

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress

class HolodriveApiTest {
    @Test fun serverPolicyRejectsUnprotectedOrAmbiguousAddresses() {
        listOf("http://example.com:8768", "http://100.100.10.10:8768", "http://ts.net", "http://fake.ts.net.evil.com", "https://user:pass@example.com", "https://example.com/path", "https://example.com/?token=x").forEach {
            assertThrows(IllegalArgumentException::class.java) { ServerPolicy.parse(it) }
        }
        assertEquals(8768, ServerPolicy.parse(DEFAULT_SERVER).port)
        assertEquals("https", ServerPolicy.parse("https://photos.example.com").scheme)
    }

    @Test fun onlyTailnetAddressRangesAreAllowedForHttp() {
        listOf("100.64.0.1", "100.127.255.254", "fd7a:115c:a1e0::1").forEach { assertTrue(ServerPolicy.isTailnetAddress(InetAddress.getByName(it))) }
        listOf("127.0.0.1", "192.168.0.1", "100.63.255.255", "100.128.0.1", "8.8.8.8", "::1", "fd7a:115c:a1e1::1").forEach { assertFalse(ServerPolicy.isTailnetAddress(InetAddress.getByName(it))) }
    }

    @Test fun cookieAndCsrfAreUsedAndLogoutClearsThem() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Set-Cookie", "holodrive_session=test-session; HttpOnly; Path=/; Max-Age=3600").setBody("{\"csrf\":\"test-csrf\"}"))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody("[]"))
            val api = HolodriveApi(server.url("/"), Dns.SYSTEM)
            api.login("test-only-password")
            val login = server.takeRequest()
            assertEquals("test-only-password", JSONObject(login.body.readUtf8()).getString("password"))
            assertNull(login.getHeader("Origin"))
            api.roots()
            assertEquals("holodrive_session=test-session", server.takeRequest().getHeader("Cookie"))
            api.logout()
            assertEquals("test-csrf", server.takeRequest().getHeader("X-Holodrive-Csrf"))
            api.roots()
            assertNull(server.takeRequest().getHeader("Cookie"))
            api.close()
        }
    }

    @Test fun expiredSessionIsClearedAndReported() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Set-Cookie", "holodrive_session=test; Path=/").setBody("{\"csrf\":\"test\"}"))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("[]"))
            var expired = false
            val api = HolodriveApi(server.url("/"), Dns.SYSTEM) { expired = true }
            api.login("test-only-password")
            try { api.roots(); fail("Expected expiry") } catch (e: ApiException) { assertEquals(401, e.status) }
            assertTrue(expired)
            api.roots()
            server.takeRequest(); server.takeRequest()
            assertNull(server.takeRequest().getHeader("Cookie"))
            api.close()
        }
    }

    @Test fun loginRateLimitDoesNotRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429))
            val api = HolodriveApi(server.url("/"), Dns.SYSTEM)
            try { api.login("test-only-password"); fail("Expected brake") } catch (e: ApiException) { assertEquals(429, e.status) }
            assertEquals(1, server.requestCount)
            api.close()
        }
    }

    @Test fun redirectsAndOtherOriginsNeverReceiveCredentials() = runBlocking {
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/")))
                val api = HolodriveApi(server.url("/"), Dns.SYSTEM)
                try { api.login("test-only-password"); fail("Expected redirect blocked") } catch (e: ApiException) { assertEquals(302, e.status) }
                assertThrows(IOException::class.java) { api.client.newCall(Request.Builder().url(other.url("/")).build()).execute() }
                assertEquals(0, other.requestCount)
                api.close()
            }
        }
    }

    @Test fun unicodePhotoPathsRoundTripAndDownloadsPreserveBytes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("original-photo-bytes"))
            val api = HolodriveApi(server.url("/"), Dns.SYSTEM)
            val path = "pictures/été & family/猫 #1.jpg"
            val output = ByteArrayOutputStream()
            api.download(path, output)
            assertEquals(path, server.takeRequest().requestUrl!!.queryParameter("path"))
            assertEquals("original-photo-bytes", output.toString("UTF-8"))
            assertEquals("/api/cybergrid/themes", api.url("cybergrid/themes").encodedPath)
            api.close()
        }
    }

    @Test fun ghostPortStatusUsesTheAuthenticatedReadOnlyEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Set-Cookie", "holodrive_session=test-session; HttpOnly; Path=/; Max-Age=3600").setBody("{\"csrf\":\"test-csrf\"}"))
            server.enqueue(MockResponse().setBody("""{"api_version":"status/v1","available":true,"snapshot":{"role":"client","control_connected":true,"uptime_secs":3600,"control_peer_addr":"192.0.2.10:9000","links":[{"id":"holodrive","mode":"reverse","active_streams":2,"bytes_forward":100,"bytes_back":200}]}}"""))
            val api = HolodriveApi(server.url("/"), Dns.SYSTEM)
            api.login("test-only-password")
            server.takeRequest() // login exchange
            val status = api.ghostPortStatus()
            val request = server.takeRequest()
            assertEquals("/api/integrations/ghostport/status", request.requestUrl!!.encodedPath)
            assertEquals("holodrive_session=test-session", request.getHeader("Cookie"))
            assertTrue(status.available)
            assertTrue(status.connected)
            assertEquals("client", status.role)
            assertEquals(1, status.links.size)
            assertEquals(300, status.links.single().bytesRelayed)
            api.close()
        }
    }

    @Test fun cyberVaultStoreUsesPasswordSessionAndSendsTheShareBundle() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Set-Cookie", "holodrive_session=test-session; HttpOnly; Path=/; Max-Age=3600").setBody("{\"csrf\":\"test-csrf\"}"))
            server.enqueue(MockResponse().setBody("{\"api_version\":\"store/v1\",\"stored\":true,\"label\":\"HOLODRIVE · album\"}"))
            val api = HolodriveApi(server.url("/"), Dns.SYSTEM)
            api.login("test-only-password")
            server.takeRequest()
            assertEquals("HOLODRIVE · album", api.saveShareToCyberVault("HOLODRIVE · album", "https://example.test/s/abc", "amber moon river"))
            val request = server.takeRequest()
            assertEquals("/api/integrations/cybervault/store", request.requestUrl!!.encodedPath)
            assertEquals("test-csrf", request.getHeader("X-Holodrive-Csrf"))
            val payload = JSONObject(request.body.readUtf8())
            assertEquals("HOLODRIVE · album", payload.getString("label"))
            assertEquals("Link: https://example.test/s/abc\nPassphrase: amber moon river", payload.getString("secret"))
            api.close()
        }
    }

    @Test fun cyberVaultStatusUsesTheAuthenticatedStatusEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Set-Cookie", "holodrive_session=test-session; HttpOnly; Path=/; Max-Age=3600").setBody("{\"csrf\":\"test-csrf\"}"))
            server.enqueue(MockResponse().setBody("{\"api_version\":\"status/v1\",\"available\":true,\"unlocked\":true}"))
            val api = HolodriveApi(server.url("/"), Dns.SYSTEM)
            api.login("test-only-password")
            server.takeRequest()
            val status = api.cyberVaultStatus()
            assertEquals("/api/integrations/cybervault/status", server.takeRequest().requestUrl!!.encodedPath)
            assertTrue(status.available)
            assertTrue(status.unlocked)
            api.close()
        }
    }

    @Test fun listingRetainsFilesButMarksOnlySafeRasterImagesAsPhotos() {
        fun entry(name: String, mime: String = "image/jpeg", escapes: Boolean = false, kind: String = "file") =
            PhotoEntry.fromJson("pictures", JSONObject().put("name", name).put("mime", mime).put("kind", kind).put("escapes", escapes))
        assertTrue(entry("été.jpg")!!.image)
        assertNotNull(entry("vacation", "", kind = "dir"))
        assertNull(entry(".secret.jpg"))
        assertNull(entry("../escape.jpg"))
        assertNull(entry("outside.jpg", escapes = true))
        assertFalse(entry("drawing.svg", "image/svg+xml")!!.image)
        assertFalse(entry("notes.txt", "text/plain")!!.image)
    }

    @Test fun photoScanPreservesServerPathsAndExifCaptureDates() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"results":[{"name":"trip.jpg","path":"pictures/2024/trip.jpg","kind":"file","mime":"image/jpeg","size":12,"mtime":2,"capture_date":"2024:05:06 07:08:09"}],"truncated":false}"""))
            val api = HolodriveApi(server.url("/"), Dns.SYSTEM)
            val scan = api.photos("pictures")
            assertEquals("pictures", server.takeRequest().requestUrl!!.queryParameter("path"))
            assertEquals("pictures/2024/trip.jpg", scan.entries.single().path)
            assertEquals("2024:05:06 07:08:09", scan.entries.single().captureDate)
            assertFalse(scan.truncated)
            api.close()
        }
    }
}
