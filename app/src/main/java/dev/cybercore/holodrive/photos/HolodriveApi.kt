package dev.cybercore.holodrive.photos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

const val DEFAULT_SERVER = "http://blackbox.tail9311b5.ts.net:8768"

object ServerPolicy {
    fun parse(value: String): HttpUrl {
        val url = value.trim().toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty()) { "Enter a server address without credentials." }
        require(url.encodedPath == "/" && url.query == null && url.fragment == null) { "Enter only the server address and port." }
        require(url.scheme == "https" || (url.host.endsWith(".ts.net") && url.host.split('.').size >= 4)) {
            "Use HTTPS, or an http://device.tailnet.ts.net address over Tailscale."
        }
        return url
    }

    fun isTailnetAddress(address: InetAddress): Boolean {
        val b = address.address.map { it.toInt() and 255 }
        return (b.size == 4 && b[0] == 100 && b[1] in 64..127) ||
            (b.size == 16 && b.take(6) == listOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0))
    }

    fun dns(server: HttpUrl, scratch: Boolean = false) = object : Dns {
      override fun lookup(hostname: String): List<InetAddress> {
        if (hostname != server.host) throw UnknownHostException("Unexpected server")
        return if (scratch && hostname == "blackbox.tail9311b5.ts.net") {
            listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        } else {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (server.scheme == "http" && addresses.any { !isTailnetAddress(it) }) {
                throw UnknownHostException("Connect Tailscale to reach this server securely")
            }
            addresses
        }
      }
    }
}

class SessionCookies : CookieJar {
    private var cookies = listOf<Cookie>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        this.cookies = cookies.filter { it.name == "holodrive_session" && it.matches(url) }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
    @Synchronized fun clear() { cookies = emptyList() }
}

class ApiException(val status: Int, message: String) : IOException(message)
data class Drive(val id: String, val name: String, val icon: String, val online: Boolean)
data class PhotoEntry(val name: String, val path: String, val folder: Boolean, val image: Boolean, val mime: String, val size: Long, val modified: Long) {
    companion object {
        fun fromJson(folder: String, obj: JSONObject): PhotoEntry? {
            val name = obj.getString("name")
            if (name.startsWith('.') || name.contains('/') || name.contains('\\') || obj.optBoolean("escapes")) return null
            val isFolder = obj.optString("kind") == "dir"
            if (!isFolder && obj.optString("kind") != "file") return null
            val mime = obj.optString("mime")
            // Retain all ordinary files for Files; SVG remains excluded from Photos.
            val image = !isFolder && mime.startsWith("image/") && mime != "image/svg+xml"
            return PhotoEntry(name, "$folder/$name", isFolder, image, mime, obj.optLong("size"), obj.optLong("mtime"))
        }
    }
}
data class ServerTheme(val id: String, val name: String, val palette: Map<String, String>)
data class ThemeCatalog(val active: String, val themes: List<ServerTheme>)
data class GhostPortLinkStatus(val id: String, val mode: String, val activeStreams: Long, val bytesRelayed: Long)
data class GhostPortStatus(val available: Boolean, val connected: Boolean, val reason: String?, val role: String?, val uptimeSeconds: Long, val links: List<GhostPortLinkStatus>)
data class CyberVaultStatus(val available: Boolean, val unlocked: Boolean, val reason: String?)

class HolodriveApi(
    val server: HttpUrl,
    dns: Dns = ServerPolicy.dns(server),
    private val expired: () -> Unit = {},
) {
    private val cookies = SessionCookies()
    @Volatile private var csrf: String? = null
    @Volatile private var deviceToken: String? = null
    val client = OkHttpClient.Builder()
        .cookieJar(cookies).dns(dns).proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val request = deviceToken?.let { original.newBuilder().header("Authorization", "Bearer $it").build() } ?: original
            if (request.url.host != server.host || request.url.port != server.port || request.url.scheme != server.scheme) {
                throw IOException("Request outside the selected server was blocked")
            }
            val response = chain.proceed(request)
            if (response.code == 401 && request.url.encodedPath != "/api/login") {
                clearSession()
                expired()
            }
            response
        }.build()

    fun url(endpoint: String, path: String? = null): HttpUrl = server.newBuilder()
        .addPathSegment("api").addPathSegments(endpoint)
        .apply { if (path != null) addQueryParameter("path", path) }.build()

    private suspend fun request(endpoint: String, path: String? = null, body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url(endpoint, path))
        if (body != null) {
            builder.post(body.toString().toRequestBody("application/json".toMediaType()))
            if (endpoint != "login") builder.header("X-Holodrive-Csrf", csrf ?: throw ApiException(401, "Sign in again."))
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw failure(response.code)
            response.body?.string() ?: throw IOException("Empty response")
        }
    }

    suspend fun login(password: String) {
        clearSession()
        val result = JSONObject(request("login", body = JSONObject().put("password", password)))
        csrf = result.getString("csrf")
        if (cookies.loadForRequest(server).isEmpty()) {
            clearSession()
            throw IOException("The server did not provide a session cookie")
        }
    }

    suspend fun loginWithDeviceToken(token: String) {
        clearSession()
        require(token.startsWith("hd_") && token.length < 256) { "Enter a Holodrive device token." }
        deviceToken = token.trim()
        try { roots() } catch (e: Exception) { deviceToken = null; throw e }
    }

    suspend fun createDeviceToken(name: String, readOnly: Boolean = false): String {
        val result = JSONObject(request("tokens", body = JSONObject().put("name", name).put("read_only", readOnly)))
        return result.getString("token")
    }

    suspend fun createShareLink(path: String, password: String, days: Int = 7): String {
        require(password.length in 12..256) { "Use a passphrase between 12 and 256 characters." }
        require(days in 1..365) { "Share-link expiry must be between 1 and 365 days." }
        val result = JSONObject(request("links", body = JSONObject()
            .put("path", path)
            .put("mode", "view")
            .put("days", days)
            .put("password", password)
        ))
        val key = result.getString("key")
        return server.newBuilder().addPathSegment("s").addPathSegment(key).build().toString()
    }

    suspend fun logout() { try { request("logout", body = JSONObject()) } finally { clearSession() } }
    fun setDeviceToken(token: String?) { deviceToken = token?.trim()?.takeIf(String::isNotEmpty) }
    fun isUsingDeviceToken(): Boolean = deviceToken != null
    fun clearSession() { csrf = null; deviceToken = null; cookies.clear() }
    fun close() { clearSession(); client.dispatcher.cancelAll(); client.connectionPool.evictAll() }

    suspend fun roots(): List<Drive> {
        val array = JSONArray(request("roots"))
        return (0 until array.length()).map { array.getJSONObject(it) }.map {
            Drive(it.getString("id"), it.getString("name"), it.optString("icon", "▣"), it.optBoolean("online", true))
        }
    }
    suspend fun list(path: String): List<PhotoEntry> {
        val array = JSONObject(request("list", path)).getJSONArray("entries")
        return (0 until array.length()).mapNotNull { PhotoEntry.fromJson(path, array.getJSONObject(it)) }
    }
    suspend fun themes(): ThemeCatalog {
        val obj = JSONObject(request("cybergrid/themes"))
        val array = obj.getJSONArray("themes")
        return ThemeCatalog(obj.optString("active"), (0 until array.length()).map {
            val theme = array.getJSONObject(it)
            val palette = theme.getJSONObject("palette")
            ServerTheme(theme.getString("id"), theme.getString("name"), palette.keys().asSequence().associateWith { key -> palette.getString(key) })
        })
    }
    suspend fun ghostPortStatus(): GhostPortStatus {
        val result = JSONObject(request("integrations/ghostport/status"))
        val snapshot = result.optJSONObject("snapshot")
        val links = snapshot?.optJSONArray("links") ?: JSONArray()
        return GhostPortStatus(
            available = result.optBoolean("available"),
            connected = snapshot?.optBoolean("control_connected") ?: false,
            reason = result.optString("reason").takeIf(String::isNotBlank),
            role = snapshot?.optString("role")?.takeIf(String::isNotBlank),
            uptimeSeconds = snapshot?.optLong("uptime_secs") ?: 0,
            links = (0 until links.length()).map { index ->
                val link = links.getJSONObject(index)
                GhostPortLinkStatus(
                    id = link.optString("id", "link"),
                    mode = link.optString("mode"),
                    activeStreams = link.optLong("active_streams"),
                    bytesRelayed = link.optLong("bytes_forward") + link.optLong("bytes_back"),
                )
            },
        )
    }
    suspend fun cyberVaultStatus(): CyberVaultStatus {
        val result = JSONObject(request("integrations/cybervault/status"))
        return CyberVaultStatus(result.optBoolean("available"), result.optBoolean("unlocked"), result.optString("reason").takeIf(String::isNotBlank))
    }
    suspend fun saveShareToCyberVault(label: String, link: String, passphrase: String): String {
        check(!isUsingDeviceToken()) { "Sign in with your Holodrive password to save secrets to CyberVault." }
        val secret = "Link: $link\nPassphrase: $passphrase"
        val result = JSONObject(request("integrations/cybervault/store", body = JSONObject()
            .put("label", label)
            .put("secret", secret)))
        if (!result.optBoolean("stored")) {
            throw IOException("CyberVault did not store this share (${result.optString("reason", "bridge unavailable")}).")
        }
        return result.optString("label", label)
    }
    suspend fun download(path: String, stream: OutputStream) = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url("raw", path)).build()).execute().use { response ->
            if (!response.isSuccessful) throw failure(response.code)
            (response.body ?: throw IOException("Empty photo")).byteStream().use { it.copyTo(stream) }
        }
    }
    private fun failure(code: Int) = ApiException(code, when (code) {
        401 -> "Your session expired. Sign in again."
        403 -> "This server denied access to the folder."
        404 -> "This folder or photo is no longer available."
        421 -> "The server does not allow this hostname. Check its Host allowlist."
        429 -> "Too many sign-in attempts. Wait 10 minutes before trying again."
        in 300..399 -> "The server redirected this request. Enter its final address in Settings."
        else -> "Holodrive returned an error ($code). Try again."
    })
}
