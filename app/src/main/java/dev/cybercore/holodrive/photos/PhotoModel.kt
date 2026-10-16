package dev.cybercore.holodrive.photos

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import coil.ImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.File
import java.util.UUID

data class PhotoState(
    val server: String = DEFAULT_SERVER,
    val signedIn: Boolean = false,
    val busy: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val path: String? = null,
    val library: String? = null,
    val choosing: Boolean = false,
    val drives: List<Drive> = emptyList(),
    val entries: List<PhotoEntry> = emptyList(),
    val filePath: String? = null,
    val fileEntries: List<PhotoEntry> = emptyList(),
    val fileBusy: Boolean = false,
    val fileError: String? = null,
    val catalog: ThemeCatalog = ThemeCatalog("", emptyList()),
    val themeChoice: String? = null,
    val blockedUntil: Long = 0,
    val hasDeviceToken: Boolean = false,
    val creatingDeviceToken: Boolean = false,
    val sharedFiles: List<ShareFile> = emptyList(),
    val sharing: Boolean = false,
    val shareProgress: String? = null,
    val uploadDirectory: String = "shared/from-phone",
    val uploads: List<UploadItem> = emptyList(),
)

data class ShareFile(val file: File, val name: String, val size: Long)

class PhotoModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("preferences", 0)
    private val mutable = MutableStateFlow(PhotoState(
        server = prefs.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER,
        library = prefs.getString("library", null),
        themeChoice = prefs.getString("theme", null),
        hasDeviceToken = DeviceTokenVault(application).hasToken(),
        uploadDirectory = prefs.getString("upload_directory", "shared/from-phone") ?: "shared/from-phone",
    ))
    val state = mutable.asStateFlow()
    private var generation = 0
    private var browseJob: Job? = null
    private var filesJob: Job? = null
    private var activeDeviceToken: String? = null
    var api = createApi()
        private set
    var imageLoader = createImageLoader()
        private set

    private fun createApi(): HolodriveApi {
        val server = ServerPolicy.parse(mutable.value.server)
        val epoch = generation
        return HolodriveApi(server, ServerPolicy.dns(server, BuildConfig.DEBUG && BuildConfig.SCRATCH_SERVER)) {
            viewModelScope.launch {
                if (epoch == generation && mutable.value.signedIn) {
                    if (api.isUsingDeviceToken()) {
                        tokenVault.clear()
                        activeDeviceToken = null
                        mutable.update { it.copy(hasDeviceToken = false) }
                    }
                    lock("Your session expired. Sign in again.")
                }
            }
        }
    }
    private fun createImageLoader() = ImageLoader.Builder(getApplication<Application>())
        .okHttpClient(api.client).diskCache(null).crossfade(false).build()

    private val tokenVault = DeviceTokenVault(application)
    private val uploadsDirectory = File(application.filesDir, "shared_uploads")

    init {
        refreshThemes()
        viewModelScope.launch {
            UploadWorker.observe(getApplication()).collect { infos ->
                val uploads = infos.map { info ->
                    UploadItem(
                        id = info.id.toString(),
                        name = info.progress.getString(UploadWorker.KEY_NAME)
                            ?: info.tags.firstOrNull { it.startsWith(UploadWorker.TAG_NAME) }?.removePrefix(UploadWorker.TAG_NAME).orEmpty().ifBlank { "Upload" },
                        bytes = info.progress.getLong(UploadWorker.KEY_BYTES, 0),
                        total = info.progress.getLong(UploadWorker.KEY_TOTAL, info.tags.firstOrNull { it.startsWith(UploadWorker.TAG_SIZE) }?.removePrefix(UploadWorker.TAG_SIZE)?.toLongOrNull() ?: 0L),
                        state = info.state.name,
                        error = info.outputData.getString("error"),
                    )
                }
                mutable.update { it.copy(uploads = uploads) }
            }
        }
        tokenVault.read()?.let { signInWithDeviceToken(it, remember = false) }
    }

    fun configureServer(value: String): String? {
        val parsed = try { ServerPolicy.parse(value) } catch (e: IllegalArgumentException) {
            return e.message ?: "Enter a valid server URL."
        }
        val normalized = parsed.toString().trimEnd('/')
        if (normalized == mutable.value.server) return null
        generation++
        browseJob?.cancel()
        filesJob?.cancel()
        api.close()
        imageLoader.shutdown()
        CredentialVault(getApplication()).clear()
        tokenVault.clear()
        activeDeviceToken = null
        androidx.work.WorkManager.getInstance(getApplication<Application>()).cancelAllWorkByTag(UploadWorker.TAG)
        prefs.edit { putString("server", normalized); remove("library"); remove("theme") }
        mutable.value = PhotoState(server = normalized)
        api = createApi()
        imageLoader = createImageLoader()
        refreshThemes()
        return null
    }

    fun signIn(password: String, onSuccess: (() -> Unit)? = null) {
        if (mutable.value.busy || password.isEmpty() || System.currentTimeMillis() < mutable.value.blockedUntil) return
        val current = api
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                current.login(password)
                if (api !== current) return@launch
                activeDeviceToken = null
                onSuccess?.invoke()
                mutable.update { it.copy(signedIn = true, busy = false, choosing = it.library == null, error = null) }
                browse(mutable.value.library)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (api !== current) return@launch
                mutable.update { it.copy(busy = false,
                    blockedUntil = if (e is ApiException && e.status == 429) System.currentTimeMillis() + 600_000 else it.blockedUntil,
                    error = if (e is ApiException && e.status == 401) "Incorrect password. Please try again." else message(e)) }
            }
        }
    }

    fun signInWithDeviceToken(token: String, remember: Boolean = true) {
        if (mutable.value.busy || token.isBlank()) return
        val current = api
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                current.loginWithDeviceToken(token.trim())
                if (api !== current) return@launch
                activeDeviceToken = token.trim()
                if (remember) tokenVault.store(token.trim())
                mutable.update { it.copy(signedIn = true, busy = false, choosing = it.library == null, hasDeviceToken = tokenVault.hasToken(), error = null) }
                browse(mutable.value.library)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (api !== current) return@launch
                if (e is ApiException && e.status == 401 && tokenVault.read() == token.trim()) {
                    tokenVault.clear()
                    mutable.update { it.copy(hasDeviceToken = false) }
                }
                mutable.update { it.copy(busy = false, error = if (e is ApiException && e.status == 401) "This device token was rejected or revoked." else message(e)) }
            }
        }
    }

    fun enableBackgroundUploads() {
        val current = api
        if (!mutable.value.signedIn || mutable.value.creatingDeviceToken) return
        if (current.isUsingDeviceToken()) {
            val active = activeDeviceToken ?: return
            try {
                tokenVault.store(active)
                mutable.update { it.copy(hasDeviceToken = true, notice = "Device token saved securely for background uploads.") }
            } catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "Could not store the device token securely.") } }
            return
        }
        mutable.update { it.copy(creatingDeviceToken = true, error = null) }
        viewModelScope.launch {
            try {
                val model = android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device"
                val secret = current.createDeviceToken("Holodrive Photos · ${model.take(38)}")
                tokenVault.store(secret)
                current.setDeviceToken(secret)
                mutable.update { it.copy(creatingDeviceToken = false, hasDeviceToken = true, notice = "Background uploads are enabled. You can revoke this device token from the Holodrive Devices page.") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(creatingDeviceToken = false, error = message(e)) }
            }
        }
    }

    fun signOut() {
        CredentialVault(getApplication()).clear()
        tokenVault.clear()
        activeDeviceToken = null
        androidx.work.WorkManager.getInstance(getApplication<Application>()).cancelAllWorkByTag(UploadWorker.TAG)
        val current = api
        lock(null)
        viewModelScope.launch {
            try { current.logout() } catch (_: IOException) { /* Local lock already applied. */ }
            finally { current.close() }
        }
        // A fresh client prevents in-flight images from repopulating the locked session.
        generation++
        api = createApi()
        imageLoader = createImageLoader()
    }

    fun stageSharedFiles(uris: List<Uri>) {
        if (mutable.value.sharing || uris.isEmpty()) return
        val resolver = getApplication<Application>().contentResolver
        mutable.update { it.copy(sharing = true, shareProgress = "Preparing shared files…", error = null) }
        viewModelScope.launch {
            val staged = mutableListOf<ShareFile>()
            try {
                withContext(Dispatchers.IO) {
                    uploadsDirectory.mkdirs()
                    uris.forEachIndexed { index, uri ->
                        mutable.update { it.copy(shareProgress = "Copying ${index + 1} of ${uris.size}…") }
                        val original = resolver.displayName(uri) ?: "shared-file-${index + 1}"
                        val name = safeUploadName(original)
                        val file = File(uploadsDirectory, "${UUID.randomUUID()}.upload")
                        try {
                            val input = resolver.openInputStream(uri) ?: throw IOException("Cannot read $original")
                            input.use { source -> file.outputStream().buffered().use { source.copyTo(it, 256 * 1024) } }
                            staged += ShareFile(file, name, file.length())
                        } catch (e: Exception) { file.delete(); throw e }
                    }
                }
                mutable.update { it.copy(sharedFiles = staged, sharing = false, shareProgress = null,
                    uploadDirectory = mutable.value.path ?: mutable.value.uploadDirectory) }
            } catch (e: Exception) {
                staged.forEach { it.file.delete() }
                mutable.update { it.copy(sharing = false, shareProgress = null, error = e.message ?: "Could not prepare the shared file.") }
            }
        }
    }

    @Suppress("DEPRECATION")
    fun stageShareIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = buildList {
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::add)
            }
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach(::add)
            } else {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
                intent.data?.let(::add)
            }
        }.distinct()
        stageSharedFiles(uris)
    }

    fun setUploadDirectory(value: String) { mutable.update { it.copy(uploadDirectory = value) } }
    fun dismissShare() {
        mutable.value.sharedFiles.forEach { it.file.delete() }
        mutable.update { it.copy(sharedFiles = emptyList()) }
    }
    fun enqueueSharedFiles() {
        val state = mutable.value
        if (state.sharedFiles.isEmpty() || !state.signedIn) return
        if (!tokenVault.hasToken()) {
            mutable.update { it.copy(error = "Enable background uploads in Settings first so the queue can continue if Holodrive Photos closes.") }
            return
        }
        val dir = state.uploadDirectory.trim().trim('/')
        if (dir.isEmpty() || dir.split('/').any { it.isEmpty() || it == "." || it == ".." || it.startsWith('.') }) {
            mutable.update { it.copy(error = "Enter a valid Holodrive destination folder.") }
            return
        }
        prefs.edit { putString("upload_directory", dir) }
        UploadWorker.enqueue(getApplication(), state.server, dir, state.sharedFiles.map { it.file to it.name })
        mutable.update { it.copy(sharedFiles = emptyList(), error = null, notice = "${state.sharedFiles.size} file(s) added to the upload queue.") }
    }

    fun cancelUpload(id: String) = UploadWorker.cancel(getApplication(), id)
    fun retryUpload(id: String) {
        viewModelScope.launch { UploadWorker.retry(getApplication(), id) }
    }

    private fun lock(error: String?) {
        browseJob?.cancel()
        imageLoader.shutdown()
        filesJob?.cancel()
        mutable.update { it.copy(signedIn = false, busy = false, saving = false, entries = emptyList(), fileEntries = emptyList(), drives = emptyList(), path = null, filePath = null, fileBusy = false, fileError = null, error = error, notice = null) }
        // Expiry already clears cookies in the interceptor. Explicit logout keeps
        // its cookie long enough to invalidate the server-side session.
        if (error != null) {
            api.close()
            generation++
            api = createApi()
            imageLoader = createImageLoader()
        }
    }

    fun browse(path: String?) {
        if (!mutable.value.signedIn) return
        browseJob?.cancel()
        val current = api
        mutable.update { it.copy(path = path, entries = emptyList(), busy = true, error = null) }
        browseJob = viewModelScope.launch {
            try {
                val drives = if (path == null || mutable.value.drives.isEmpty()) current.roots() else mutable.value.drives
                val entries = if (path != null) current.list(path) else emptyList()
                if (api === current && mutable.value.signedIn) mutable.update { it.copy(drives = drives, entries = entries, busy = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (api === current) mutable.update { it.copy(busy = false, error = message(e)) }
            }
        }
    }
    fun browseFiles(path: String?) {
        if (!mutable.value.signedIn) return
        filesJob?.cancel()
        val current = api
        mutable.update { it.copy(filePath = path, fileEntries = emptyList(), fileBusy = true, fileError = null) }
        filesJob = viewModelScope.launch {
            try {
                val drives = if (path == null || mutable.value.drives.isEmpty()) current.roots() else mutable.value.drives
                val entries = if (path != null) current.list(path) else emptyList()
                if (api === current && mutable.value.signedIn) mutable.update {
                    it.copy(drives = drives, fileEntries = entries, fileBusy = false, fileError = null)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (api === current) mutable.update { it.copy(fileBusy = false, fileError = message(e)) }
            }
        }
    }
    fun up() {
        val path = mutable.value.path ?: return
        browse(if ('/' in path) path.substringBeforeLast('/') else null)
    }
    fun chooseFolder() { mutable.update { it.copy(choosing = true) }; browse(null) }
    fun selectFolder() {
        val path = mutable.value.path ?: return
        if (mutable.value.busy || mutable.value.error != null) return
        prefs.edit { putString("library", path) }
        mutable.update { it.copy(library = path, choosing = false) }
    }
    fun cancelFolder() { mutable.update { it.copy(choosing = false) }; browse(mutable.value.library) }
    fun home() { browse(mutable.value.library) }
    fun chooseTheme(id: String?) {
        prefs.edit { putString("theme", id) }
        mutable.update { it.copy(themeChoice = id) }
    }
    fun refreshThemes() {
        val current = api
        viewModelScope.launch {
            try {
                val themes = current.themes()
                if (api === current) mutable.update { it.copy(catalog = themes) }
            } catch (_: IOException) { /* Offline use retains the fallback palette. */ }
            catch (_: org.json.JSONException) { /* Older servers may not expose themes. */ }
        }
    }
    fun savePhoto(entry: PhotoEntry, uri: Uri) {
        if (mutable.value.saving || !mutable.value.signedIn) return
        val current = api
        mutable.update { it.copy(saving = true, notice = "Saving original…") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = getApplication<Application>().contentResolver.openOutputStream(uri, "w") ?: throw IOException("Cannot open destination")
                    output.use { current.download(entry.path, it) }
                }
                if (api === current) mutable.update { it.copy(saving = false, notice = "Photo saved.") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Remove an incomplete document created by the save picker.
                withContext(Dispatchers.IO) {
                    runCatching { android.provider.DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) }
                }
                if (api === current) mutable.update { it.copy(saving = false, notice = "Save failed. ${message(e)}") }
            }
        }
    }
    fun clearNotice() { mutable.update { it.copy(notice = null) } }
    private fun message(e: Exception): String = when (e) {
        is ApiException -> e.message ?: "Unable to connect."
        is org.json.JSONException -> "This address did not return a compatible Holodrive response."
        else -> "Cannot reach Holodrive. Check the server address and your Tailscale connection."
    }
    private fun ContentResolver.displayName(uri: Uri): String? = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 } ?: return@use null) else null
    }
    private fun safeUploadName(value: String): String {
        val name = value.substringAfterLast('/').substringAfterLast('\\').trim().replace(Regex("[\\u0000-\\u001f]"), "_")
        val safe = name.ifBlank { "shared-file" }
        return if (safe.startsWith('.')) "upload-$safe" else safe
    }
    override fun onCleared() { imageLoader.shutdown(); api.close() }
}
