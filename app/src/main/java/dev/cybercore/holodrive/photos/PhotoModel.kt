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
import org.json.JSONArray
import org.json.JSONObject
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
    val photoFolders: List<String> = emptyList(),
    val fileFolders: List<String> = emptyList(),
    val choosing: Boolean = false,
    val folderPickTarget: String = "photos",
    val drives: List<Drive> = emptyList(),
    val entries: List<PhotoEntry> = emptyList(),
    val filePath: String? = null,
    val fileEntries: List<PhotoEntry> = emptyList(),
    val fileBusy: Boolean = false,
    val fileError: String? = null,
    val catalog: ThemeCatalog = ThemeCatalog("", emptyList()),
    val themeChoice: String? = null,
    val gridSize: String = "medium",
    val sortOrder: String = "newest",
    val fileGridSize: String = "medium",
    val fileSortOrder: String = "name",
    val fileLayout: String = "list",
    val defaultTab: String = "photos",
    val syncPairs: List<SyncPair> = emptyList(),
    val syncInterval: String = "hourly",
    val syncWifiOnly: Boolean = true,
    val blockedUntil: Long = 0,
    val hasDeviceToken: Boolean = false,
    val hasReadOnlySyncToken: Boolean = false,
    val creatingDeviceToken: Boolean = false,
    val sharedFiles: List<ShareFile> = emptyList(),
    val sharing: Boolean = false,
    val shareProgress: String? = null,
    val creatingShareLink: Boolean = false,
    val shareLink: String? = null,
    val uploadDirectory: String = "shared/from-phone",
    val uploads: List<UploadItem> = emptyList(),
)

data class ShareFile(val file: File, val name: String, val size: Long)
data class SyncPair(val id: String, val localTree: String, val localName: String, val remotePath: String, val direction: String = "upload")

class PhotoModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("preferences", 0)
    private val initialPhotoFolders = loadPhotoFolders()
    private val mutable = MutableStateFlow(PhotoState(
        server = prefs.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER,
        photoFolders = initialPhotoFolders,
        fileFolders = loadFolderSet("file_folders"),
        themeChoice = prefs.getString("theme", null),
        gridSize = prefs.getString("grid_size", "medium") ?: "medium",
        sortOrder = prefs.getString("sort_order", "newest") ?: "newest",
        fileGridSize = prefs.getString("file_grid_size", "medium") ?: "medium",
        fileSortOrder = prefs.getString("file_sort_order", "name") ?: "name",
        fileLayout = prefs.getString("file_layout", "list") ?: "list",
        defaultTab = prefs.getString("default_tab", "photos") ?: "photos",
        syncPairs = loadSyncPairs(),
        syncInterval = prefs.getString("sync_interval", "hourly") ?: "hourly",
        syncWifiOnly = prefs.getBoolean("sync_wifi_only", true),
        hasDeviceToken = DeviceTokenVault(application).hasToken(),
        hasReadOnlySyncToken = DeviceTokenVault(application, readOnly = true).hasToken(),
        uploadDirectory = prefs.getString("upload_directory", "shared/from-phone") ?: "shared/from-phone",
    ))
    val state = mutable.asStateFlow()
    private var generation = 0
    private var browseJob: Job? = null
    private var filesJob: Job? = null
    private var activeDeviceToken: String? = null
    private var activeDeviceTokenReadOnly = false

    private fun loadPhotoFolders(): List<String> {
        prefs.getStringSet("photo_folders", null)?.let { saved ->
            return saved.filter(String::isNotBlank).distinct().sorted()
        }
        val legacy = prefs.getString("library", null)?.takeIf(String::isNotBlank)
        if (legacy != null) prefs.edit { putStringSet("photo_folders", setOf(legacy)) }
        return listOfNotNull(legacy)
    }

    private fun loadFolderSet(key: String): List<String> =
        prefs.getStringSet(key, null)?.filter(String::isNotBlank)?.distinct()?.sorted().orEmpty()

    private fun loadSyncPairs(): List<SyncPair> = try {
        val saved = JSONArray(prefs.getString("sync_pairs", "[]"))
        (0 until saved.length()).map { saved.getJSONObject(it) }.map { row ->
            SyncPair(row.getString("id"), row.getString("localTree"), row.getString("localName"), row.getString("remotePath"), row.optString("direction", "upload"))
        }
    } catch (_: Exception) { emptyList() }

    private fun saveSyncPairs(pairs: List<SyncPair>) {
        val rows = JSONArray()
        pairs.forEach { pair -> rows.put(JSONObject().put("id", pair.id).put("localTree", pair.localTree).put("localName", pair.localName).put("remotePath", pair.remotePath).put("direction", pair.direction)) }
        prefs.edit { putString("sync_pairs", rows.toString()) }
        mutable.update { it.copy(syncPairs = pairs) }
        refreshSyncSchedule()
    }

    private fun refreshSyncSchedule() {
        val context = getApplication<Application>()
        SyncWorker.schedule(context, mutable.value.server, mutable.value.syncPairs, mutable.value.syncInterval, mutable.value.syncWifiOnly)
    }

    private fun savePhotoFolders(folders: List<String>) {
        val clean = folders.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
        prefs.edit {
            putStringSet("photo_folders", clean.toSet())
            if (clean.isEmpty()) remove("library") else putString("library", clean.first())
        }
        mutable.update { it.copy(photoFolders = clean) }
    }

    private fun saveFileFolders(folders: List<String>) {
        val clean = folders.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
        prefs.edit { putStringSet("file_folders", clean.toSet()) }
        mutable.update { it.copy(fileFolders = clean) }
    }
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
                        if (activeDeviceTokenReadOnly) readOnlyTokenVault.clear() else tokenVault.clear()
                        activeDeviceToken = null
                        mutable.update { it.copy(hasDeviceToken = tokenVault.hasToken(), hasReadOnlySyncToken = readOnlyTokenVault.hasToken()) }
                    }
                    lock("Your session expired. Sign in again.")
                }
            }
        }
    }
    private fun createImageLoader() = ImageLoader.Builder(getApplication<Application>())
        .okHttpClient(api.client).diskCache(null).crossfade(false).build()

    private val tokenVault = DeviceTokenVault(application)
    private val readOnlyTokenVault = DeviceTokenVault(application, readOnly = true)
    private val uploadsDirectory = File(application.filesDir, "shared_uploads")

    init {
        refreshThemes()
        refreshSyncSchedule()
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
        val writeToken = tokenVault.read()
        val savedToken = writeToken ?: readOnlyTokenVault.read()
        savedToken?.let { signInWithDeviceToken(it, remember = false, readOnly = writeToken == null) }
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
        readOnlyTokenVault.clear()
        activeDeviceToken = null
        activeDeviceTokenReadOnly = false
        androidx.work.WorkManager.getInstance(getApplication<Application>()).cancelAllWorkByTag(UploadWorker.TAG)
        androidx.work.WorkManager.getInstance(getApplication<Application>()).cancelAllWorkByTag(SyncWorker.TAG)
        prefs.edit { putString("server", normalized); remove("library"); remove("photo_folders"); remove("file_folders"); remove("sync_pairs"); remove("theme"); remove("upload_directory") }
        mutable.value = PhotoState(
            server = normalized,
            gridSize = prefs.getString("grid_size", "medium") ?: "medium",
            sortOrder = prefs.getString("sort_order", "newest") ?: "newest",
            fileGridSize = prefs.getString("file_grid_size", "medium") ?: "medium",
            fileSortOrder = prefs.getString("file_sort_order", "name") ?: "name",
            fileLayout = prefs.getString("file_layout", "list") ?: "list",
            defaultTab = prefs.getString("default_tab", "photos") ?: "photos",
            syncInterval = prefs.getString("sync_interval", "hourly") ?: "hourly",
            syncWifiOnly = prefs.getBoolean("sync_wifi_only", true),
        )
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
                activeDeviceTokenReadOnly = false
                onSuccess?.invoke()
                mutable.update { it.copy(signedIn = true, busy = false, choosing = it.photoFolders.isEmpty(), error = null) }
                refreshSyncSchedule()
                browse(null)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (api !== current) return@launch
                mutable.update { it.copy(busy = false,
                    blockedUntil = if (e is ApiException && e.status == 429) System.currentTimeMillis() + 600_000 else it.blockedUntil,
                    error = if (e is ApiException && e.status == 401) "Incorrect password. Please try again." else message(e)) }
            }
        }
    }

    fun signInWithDeviceToken(token: String, remember: Boolean = true, readOnly: Boolean = false) {
        if (mutable.value.busy || token.isBlank()) return
        val current = api
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                current.loginWithDeviceToken(token.trim())
                if (api !== current) return@launch
                activeDeviceToken = token.trim()
                activeDeviceTokenReadOnly = readOnly
                if (remember) (if (readOnly) readOnlyTokenVault else tokenVault).store(token.trim())
                mutable.update { it.copy(signedIn = true, busy = false, choosing = it.photoFolders.isEmpty(), hasDeviceToken = tokenVault.hasToken(), hasReadOnlySyncToken = readOnlyTokenVault.hasToken(), error = null) }
                refreshSyncSchedule()
                browse(null)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (api !== current) return@launch
                val vault = if (readOnly) readOnlyTokenVault else tokenVault
                if (e is ApiException && e.status == 401 && vault.read() == token.trim()) {
                    vault.clear()
                    mutable.update { it.copy(hasDeviceToken = tokenVault.hasToken(), hasReadOnlySyncToken = readOnlyTokenVault.hasToken()) }
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
            if (activeDeviceTokenReadOnly) {
                mutable.update { it.copy(error = "This session uses a read-only token. Sign in with your Holodrive password to create a separate writable background token.") }
                return
            }
            try {
                tokenVault.store(active)
                mutable.update { it.copy(hasDeviceToken = true, notice = "Device token saved securely for background uploads.") }
                refreshSyncSchedule()
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
                activeDeviceToken = secret
                activeDeviceTokenReadOnly = false
                mutable.update { it.copy(creatingDeviceToken = false, hasDeviceToken = true, notice = "Background uploads are enabled. You can revoke this device token from the Holodrive Devices page.") }
                refreshSyncSchedule()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(creatingDeviceToken = false, error = message(e)) }
            }
        }
    }

    fun enableReadOnlySync() {
        val current = api
        if (!mutable.value.signedIn || mutable.value.creatingDeviceToken) return
        if (current.isUsingDeviceToken()) {
            if (activeDeviceTokenReadOnly) {
                val active = activeDeviceToken ?: return
                try {
                    readOnlyTokenVault.store(active)
                    mutable.update { it.copy(hasReadOnlySyncToken = true, notice = "Read-only sync token saved securely on this device.") }
                } catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "Could not store the read-only token securely.") } }
            } else {
                mutable.update { it.copy(error = "This session uses a writable token. Sign in with your Holodrive password to create a separate read-only sync token.") }
            }
            return
        }
        mutable.update { it.copy(creatingDeviceToken = true, error = null) }
        viewModelScope.launch {
            try {
                val model = android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device"
                val secret = current.createDeviceToken("Holodrive read-only sync · ${model.take(30)}", readOnly = true)
                readOnlyTokenVault.store(secret)
                mutable.update { it.copy(creatingDeviceToken = false, hasReadOnlySyncToken = true, notice = "Read-only sync token saved securely. It cannot upload, change, or delete server files.") }
                refreshSyncSchedule()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(creatingDeviceToken = false, error = message(e)) }
            }
        }
    }

    fun signOut() {
        CredentialVault(getApplication()).clear()
        tokenVault.clear()
        readOnlyTokenVault.clear()
        activeDeviceToken = null
        activeDeviceTokenReadOnly = false
        androidx.work.WorkManager.getInstance(getApplication<Application>()).cancelAllWorkByTag(UploadWorker.TAG)
        androidx.work.WorkManager.getInstance(getApplication<Application>()).cancelAllWorkByTag(SyncWorker.TAG)
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
                mutable.update { it.copy(sharedFiles = staged, sharing = false, shareProgress = null) }
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

    fun setUploadDirectory(value: String) {
        prefs.edit { putString("upload_directory", value) }
        mutable.update { it.copy(uploadDirectory = value) }
    }
    fun setGridSize(value: String) {
        if (value !in setOf("compact", "medium", "large")) return
        prefs.edit { putString("grid_size", value) }
        mutable.update { it.copy(gridSize = value) }
    }
    fun setSortOrder(value: String) {
        if (value !in setOf("newest", "oldest", "name")) return
        prefs.edit { putString("sort_order", value) }
        mutable.update { it.copy(sortOrder = value) }
    }
    fun setFileGridSize(value: String) {
        if (value !in setOf("compact", "medium", "large")) return
        prefs.edit { putString("file_grid_size", value) }
        mutable.update { it.copy(fileGridSize = value) }
    }
    fun setFileSortOrder(value: String) {
        if (value !in setOf("name", "newest", "oldest", "type")) return
        prefs.edit { putString("file_sort_order", value) }
        mutable.update { it.copy(fileSortOrder = value) }
    }
    fun setFileLayout(value: String) {
        if (value !in setOf("list", "grid")) return
        prefs.edit { putString("file_layout", value) }
        mutable.update { it.copy(fileLayout = value) }
    }
    fun addSyncPair(localTree: Uri, localName: String) {
        val state = mutable.value
        if (!state.signedIn || (!state.hasDeviceToken && !state.hasReadOnlySyncToken)) {
            mutable.update { it.copy(error = "Enable background sync access in Settings before adding a sync pair.") }
            return
        }
        prefs.edit { putString("pending_sync_tree", localTree.toString()); putString("pending_sync_name", localName) }
        chooseFolder("sync")
    }
    fun reportError(message: String) { mutable.update { it.copy(error = message) } }

    fun createShareLink(path: String, passphrase: String) {
        if (api.isUsingDeviceToken()) {
            mutable.update { it.copy(error = "Creating share links requires a password sign-in. Device tokens cannot manage links.") }
            return
        }
        mutable.update { it.copy(creatingShareLink = true, shareLink = null, error = null) }
        viewModelScope.launch {
            try {
                val link = api.createShareLink(path, passphrase)
                mutable.update { it.copy(creatingShareLink = false, shareLink = link) }
            } catch (e: Exception) {
                mutable.update { it.copy(creatingShareLink = false, error = e.message ?: "Could not create the share link.") }
            }
        }
    }

    fun clearShareLink() { mutable.update { it.copy(creatingShareLink = false, shareLink = null) } }
    fun removeSyncPair(id: String) {
        SyncWorker.cancel(getApplication(), id)
        saveSyncPairs(mutable.value.syncPairs.filterNot { it.id == id })
    }
    fun setSyncDirection(id: String, direction: String) {
        if (direction !in setOf("upload", "two-way", "download")) return
        saveSyncPairs(mutable.value.syncPairs.map { if (it.id == id) it.copy(direction = direction) else it })
    }
    fun setSyncInterval(value: String) {
        if (value !in setOf("15m", "hourly", "6h", "daily")) return
        prefs.edit { putString("sync_interval", value) }
        mutable.update { it.copy(syncInterval = value) }
        refreshSyncSchedule()
    }
    fun setSyncWifiOnly(value: Boolean) {
        prefs.edit { putBoolean("sync_wifi_only", value) }
        mutable.update { it.copy(syncWifiOnly = value) }
        refreshSyncSchedule()
    }
    fun syncNow(id: String) {
        mutable.value.syncPairs.firstOrNull { it.id == id }?.let { pair ->
            if (pair.direction != "download" && !tokenVault.hasToken()) {
                mutable.update { it.copy(error = "This sync direction needs a writable device token. Sign in with your Holodrive password and enable background uploads.") }
                return
            }
            if (pair.direction == "download" && !tokenVault.hasToken() && !readOnlyTokenVault.hasToken()) {
                mutable.update { it.copy(error = "Enable a read-only sync token in Settings first.") }
                return
            }
            SyncWorker.runNow(getApplication(), mutable.value.server, pair, mutable.value.syncWifiOnly)
            mutable.update { it.copy(notice = "${pair.localName} sync queued.") }
        }
    }
    fun setDefaultTab(value: String) {
        if (value !in setOf("photos", "files")) return
        prefs.edit { putString("default_tab", value) }
        mutable.update { it.copy(defaultTab = value) }
    }
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
        mutable.update { it.copy(signedIn = false, busy = false, saving = false, entries = emptyList(), fileEntries = emptyList(), drives = emptyList(), path = null, filePath = null, fileBusy = false, fileError = null, shareLink = null, creatingShareLink = false, error = error, notice = null) }
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
        val aggregateLibraries = path == null && !mutable.value.choosing && mutable.value.photoFolders.isNotEmpty()
        mutable.update { it.copy(path = path, entries = emptyList(), busy = true, error = null) }
        browseJob = viewModelScope.launch {
            try {
                val drives = if (path == null || mutable.value.drives.isEmpty()) current.roots() else mutable.value.drives
                var unavailable: List<String> = emptyList()
                val entries = when {
                    path != null -> current.list(path)
                    aggregateLibraries -> {
                        val failed = mutableListOf<String>()
                        val folderContents = mutableListOf<List<PhotoEntry>>()
                        mutable.value.photoFolders.forEach { folder ->
                            try { folderContents += current.list(folder) }
                            catch (e: Exception) {
                                if (e is CancellationException) throw e
                                failed += folder
                            }
                        }
                        unavailable = failed
                        combinePhotoFolders(folderContents)
                    }
                    else -> emptyList()
                }
                val folderError = unavailable.takeIf { it.isNotEmpty() }?.let {
                    "Could not load ${it.size} photo folder(s): ${it.joinToString { path -> path.substringAfterLast('/') }}. Check the folder or remove it in Settings."
                }
                if (api === current && mutable.value.signedIn) mutable.update {
                    it.copy(drives = drives, entries = entries, busy = false, error = folderError)
                }
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
        val state = mutable.value
        val path = state.path ?: return
        val parent = if ('/' in path) path.substringBeforeLast('/') else null
        if (state.choosing) browse(parent)
        else if (path in state.photoFolders || (parent != null && parent in state.photoFolders)) home()
        else browse(parent)
    }
    fun chooseFolder(target: String = "photos") {
        require(target in setOf("photos", "files", "sync"))
        mutable.update { it.copy(choosing = true, folderPickTarget = target, path = null, error = null) }
        browse(null)
    }
    fun selectFolder() {
        val path = mutable.value.path ?: return
        if (mutable.value.busy || mutable.value.error != null) return
        val state = mutable.value
        val target = state.folderPickTarget
        if (target == "sync") {
            val localTree = prefs.getString("pending_sync_tree", null)
            val localName = prefs.getString("pending_sync_name", "Phone folder") ?: "Phone folder"
            if (localTree == null) {
                mutable.update { it.copy(choosing = false, notice = "Choose a phone folder first, then choose its Holodrive destination.") }
                browse(null)
                return
            }
            val direction = if (!tokenVault.hasToken() && readOnlyTokenVault.hasToken()) "download" else "upload"
            val pair = SyncPair(UUID.randomUUID().toString(), localTree, localName, path, direction)
            prefs.edit { remove("pending_sync_tree"); remove("pending_sync_name") }
            saveSyncPairs(state.syncPairs + pair)
            mutable.update { it.copy(choosing = false, notice = "Sync pair added. Review its direction and conflict behavior in Folder sync settings.") }
            browse(null)
            return
        }
        val alreadyAdded = if (target == "files") path in state.fileFolders else path in state.photoFolders
        if (!alreadyAdded) {
            if (target == "files") saveFileFolders(state.fileFolders + path)
            else savePhotoFolders(state.photoFolders + path)
        }
        val destination = if (target == "files") "Files" else "Photos"
        mutable.update { it.copy(choosing = false, notice = if (alreadyAdded) "Folder is already saved in $destination." else "Folder added to $destination.") }
        browse(null)
    }
    fun cancelFolder() {
        if (mutable.value.folderPickTarget == "sync") prefs.edit { remove("pending_sync_tree"); remove("pending_sync_name") }
        mutable.update { it.copy(choosing = false) }
        home()
    }
    fun removePhotoFolder(path: String) {
        savePhotoFolders(mutable.value.photoFolders - path)
        mutable.update { it.copy(choosing = false) }
        home()
    }
    fun removeFileFolder(path: String) {
        saveFileFolders(mutable.value.fileFolders - path)
    }
    fun home() { browse(null) }
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
