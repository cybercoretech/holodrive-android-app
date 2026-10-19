package dev.cybercore.holodrive.photos

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

internal enum class SyncAction { NONE, UPLOAD, DOWNLOAD, CONFLICT }

/** Missing files are copied in the allowed direction; absence never deletes the other copy. */
internal fun chooseSyncAction(direction: String, localSize: Long?, localModified: Long?, remoteSize: Long?, remoteModified: Long?): SyncAction {
    if (localSize == null) return if (remoteSize != null && direction != "upload") SyncAction.DOWNLOAD else SyncAction.NONE
    if (remoteSize == null) return if (direction != "download") SyncAction.UPLOAD else SyncAction.NONE
    val sizeDiffers = localSize != remoteSize
    val localTime = (localModified ?: 0L) / 1000L
    val remoteTime = remoteModified ?: 0L
    val localNewer = localTime > remoteTime + 2L
    val remoteNewer = remoteTime > localTime + 2L
    return when (direction) {
        "upload" -> if (sizeDiffers || localNewer) SyncAction.UPLOAD else SyncAction.NONE
        "download" -> if (sizeDiffers || remoteNewer) SyncAction.DOWNLOAD else SyncAction.NONE
        "two-way" -> when {
            localNewer -> SyncAction.UPLOAD
            remoteNewer -> SyncAction.DOWNLOAD
            sizeDiffers -> SyncAction.CONFLICT
            else -> SyncAction.NONE
        }
        else -> SyncAction.NONE
    }
}

/** Periodic, non-deleting folder backup/mirror worker. Deletions never propagate between devices. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val server = inputData.getString(KEY_SERVER) ?: return@withContext Result.failure()
        val treeUri = inputData.getString(KEY_TREE)?.let(Uri::parse) ?: return@withContext Result.failure()
        val remoteRoot = inputData.getString(KEY_REMOTE)?.trim('/')?.takeIf(String::isNotBlank) ?: return@withContext Result.failure()
        val direction = inputData.getString(KEY_DIRECTION) ?: "upload"
        val writeToken = try { DeviceTokenVault(applicationContext).read() } catch (_: Exception) { null }
        val readOnlyToken = try { DeviceTokenVault(applicationContext, readOnly = true).read() } catch (_: Exception) { null }
        val token = if (direction == "download") readOnlyToken ?: writeToken else writeToken
            ?: return@withContext Result.failure(UploadWorker.workDataOfMessage("Enable background uploads to run folder sync."))
        val root = DocumentFile.fromTreeUri(applicationContext, treeUri)
            ?: return@withContext Result.failure(UploadWorker.workDataOfMessage("Android no longer grants access to this phone folder. Remove and add the sync pair again."))
        val api = try {
            HolodriveApi(ServerPolicy.parse(server), ServerPolicy.dns(ServerPolicy.parse(server), BuildConfig.DEBUG && BuildConfig.SCRATCH_SERVER)).also { it.setDeviceToken(token) }
        } catch (e: Exception) { return@withContext Result.failure(UploadWorker.workDataOfMessage(e.message ?: "Invalid server address.")) }
        try {
            val localFiles = linkedMapOf<String, DocumentFile>()
            walkLocal(root, "", localFiles)
            val remoteFiles = linkedMapOf<String, PhotoEntry>()
            walkRemote(api, remoteRoot, remoteRoot, remoteFiles)
            val toUpload = mutableListOf<Pair<File, String>>()
            val toDownload = mutableListOf<Pair<String, PhotoEntry>>()
            val conflicts = mutableListOf<String>()
            val names = (localFiles.keys + remoteFiles.keys).toSortedSet()
            for (name in names) {
                val local = localFiles[name]
                val remote = remoteFiles[name]
                when (chooseSyncAction(direction, local?.length(), local?.lastModified(), remote?.size, remote?.modified)) {
                    SyncAction.UPLOAD -> local?.let { stage(it, name)?.let(toUpload::add) }
                    SyncAction.DOWNLOAD -> remote?.let { toDownload += name to it }
                    SyncAction.CONFLICT -> conflicts += name
                    SyncAction.NONE -> Unit
                }
            }
            if (toUpload.isNotEmpty()) UploadWorker.enqueue(applicationContext, server, remoteRoot, toUpload, overwrite = true)
            for ((relative, entry) in toDownload) {
                val parent = ensureLocalDirectory(root, relative.substringBeforeLast('/', "")) ?: throw IOException("Could not create a local sync folder for $relative.")
                val name = relative.substringAfterLast('/')
                val existing = parent.findFile(name)
                val temp = File(applicationContext.cacheDir, "holodrive-sync-${id}-$name")
                temp.parentFile?.mkdirs()
                temp.outputStream().buffered().use { api.download(entry.path, it) }
                if (existing?.isDirectory == true) throw IOException("Could not replace the folder named $name with a file.")
                val stagedName = ".holodrive-pending-${id}-${UUID.randomUUID().toString().take(8)}"
                val staged = parent.createFile(entry.mime.ifBlank { "application/octet-stream" }, stagedName)
                    ?: throw IOException("Android could not stage the downloaded copy of $name.")
                try {
                    applicationContext.contentResolver.openOutputStream(staged.uri, "wt")?.buffered()?.use { out ->
                        temp.inputStream().buffered().use { it.copyTo(out) }
                    } ?: throw IOException("Android could not write the staged copy of $name.")
                    if (existing != null) {
                        val backupName = ".holodrive-backup-${id}-${UUID.randomUUID().toString().take(8)}"
                        if (!existing.renameTo(backupName)) throw IOException("Could not protect the existing local copy of $name.")
                        if (!staged.renameTo(name)) {
                            existing.renameTo(name)
                            throw IOException("Could not install the downloaded copy of $name; the old copy was kept.")
                        }
                        existing.delete()
                    } else if (!staged.renameTo(name)) {
                        throw IOException("Could not install the downloaded copy of $name.")
                    }
                } finally {
                    staged.delete()
                    temp.delete()
                }
            }
            val completedDownloads = toDownload.size
            val summary = buildList {
                if (toUpload.isNotEmpty()) add("${toUpload.size} upload(s) queued")
                if (completedDownloads > 0) add("$completedDownloads downloaded")
                if (conflicts.isNotEmpty()) add("${conflicts.size} conflict(s) need review")
            }.joinToString(" · ")
            if (summary.isNotEmpty()) {
                try { api.recordActivity("sync", remoteRoot, summary) } catch (_: Exception) { }
            }
            Result.success(Data.Builder()
                .putInt("uploaded", toUpload.size)
                .putInt("downloaded", toDownload.size)
                .putString(KEY_PAIR, inputData.getString(KEY_PAIR))
                .putStringArray(KEY_CONFLICTS, conflicts.take(MAX_REPORTED_CONFLICTS).toTypedArray())
                .putInt(KEY_CONFLICTS_TOTAL, conflicts.size)
                .build())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.retry()
        } finally { api.close() }
    }

    private fun stage(document: DocumentFile, relative: String): Pair<File, String>? {
        val name = document.name ?: return null
        val clean = relative.split('/').filter { it.isNotBlank() && it != "." && it != ".." && !it.startsWith('.') }.joinToString("/")
        if (clean.isBlank()) return null
        val staged = File(applicationContext.cacheDir, "holodrive-sync-stage/${id}/$clean")
        staged.parentFile?.mkdirs()
        applicationContext.contentResolver.openInputStream(document.uri)?.use { input -> staged.outputStream().buffered().use(input::copyTo) }
            ?: throw IOException("Android could not read $name from the selected folder.")
        return staged to clean
    }

    private suspend fun walkRemote(api: HolodriveApi, root: String, path: String, files: MutableMap<String, PhotoEntry>) {
        for (entry in api.list(path)) {
            if (entry.name.startsWith('.')) continue
            if (entry.folder) walkRemote(api, root, entry.path, files)
            else files[entry.path.removePrefix("$root/")] = entry
        }
    }

    private fun walkLocal(folder: DocumentFile, prefix: String, files: MutableMap<String, DocumentFile>) {
        for (entry in folder.listFiles()) {
            val name = entry.name?.takeIf { it.isNotBlank() && !it.startsWith('.') } ?: continue
            val relative = if (prefix.isEmpty()) name else "$prefix/$name"
            if (entry.isDirectory) walkLocal(entry, relative, files) else if (entry.isFile) files[relative] = entry
        }
    }

    private fun ensureLocalDirectory(root: DocumentFile, relative: String): DocumentFile? {
        var current = root
        if (relative.isBlank()) return current
        for (part in relative.split('/')) {
            if (part.isBlank() || part == "." || part == "..") return null
            current = current.findFile(part) ?: current.createDirectory(part) ?: return null
        }
        return current
    }

    companion object {
        const val TAG = "holodrive-folder-sync"
        const val TAG_PAIR = "$TAG-pair:"
        const val KEY_SERVER = "sync_server"
        const val KEY_TREE = "sync_tree"
        const val KEY_REMOTE = "sync_remote"
        const val KEY_DIRECTION = "sync_direction"
        const val KEY_PAIR = "sync_pair"
        const val KEY_CONFLICTS = "sync_conflicts"
        const val KEY_CONFLICTS_TOTAL = "sync_conflicts_total"
        private const val MAX_REPORTED_CONFLICTS = 100
        fun workName(id: String) = "$TAG-$id"
        private fun periodicName(id: String) = "${workName(id)}-periodic"
        private fun data(server: String, pair: SyncPair) = Data.Builder()
            .putString(KEY_SERVER, server).putString(KEY_TREE, pair.localTree).putString(KEY_REMOTE, pair.remotePath)
            .putString(KEY_DIRECTION, pair.direction).putString(KEY_PAIR, pair.id).build()
        private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()

        fun observe(context: Context) = WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG)

        fun schedule(context: Context, server: String, pairs: List<SyncPair>, interval: String, wifiOnly: Boolean) {
            val manager = WorkManager.getInstance(context)
            val minutes = when (interval) { "15m" -> 15L; "6h" -> 360L; "daily" -> 1440L; else -> 60L }
            pairs.forEach { pair ->
                val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
                    .setInputData(data(server, pair)).setConstraints(constraints(wifiOnly))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(TAG).addTag(TAG_PAIR + pair.id).build()
                manager.enqueueUniquePeriodicWork(periodicName(pair.id), ExistingPeriodicWorkPolicy.UPDATE, request)
            }
        }

        fun cancel(context: Context, id: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(id))
            WorkManager.getInstance(context).cancelUniqueWork(periodicName(id))
        }

        fun runNow(context: Context, server: String, pair: SyncPair, wifiOnly: Boolean) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().setInputData(data(server, pair))
                .setConstraints(constraints(wifiOnly)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG).addTag(TAG_PAIR + pair.id).build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(pair.id), ExistingWorkPolicy.REPLACE, request)
        }
    }
}
