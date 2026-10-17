package dev.cybercore.holodrive.photos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.BackoffPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.HttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import kotlinx.coroutines.flow.first

data class UploadItem(val id: String, val name: String, val bytes: Long, val total: Long, val state: String, val error: String? = null)

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val file = inputData.getString(KEY_FILE)?.let(::File) ?: return@withContext Result.failure()
        val name = inputData.getString(KEY_NAME) ?: file.name
        val dir = inputData.getString(KEY_DIR) ?: return@withContext Result.failure()
        val overwrite = inputData.getBoolean(KEY_OVERWRITE, false)
        val address = inputData.getString(KEY_SERVER) ?: return@withContext Result.failure()
        if (!file.isFile) return@withContext Result.failure()
        val token = try { DeviceTokenVault(applicationContext).read() } catch (_: Exception) { null }
            ?: return@withContext Result.failure(workDataOfMessage("Save a device token to enable background uploads."))
        val server = try { ServerPolicy.parse(address) } catch (_: Exception) { return@withContext Result.failure() }
        val client = OkHttpClient.Builder()
            .dns(ServerPolicy.dns(server, BuildConfig.DEBUG && BuildConfig.SCRATCH_SERVER))
            .proxy(java.net.Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(2, TimeUnit.MINUTES)
            .build()
        val size = file.length()
        val checkpoints = applicationContext.getSharedPreferences("upload_checkpoints", Context.MODE_PRIVATE)
        var id = checkpoints.getString(idKey(), null)
        val resumeId = id
        try {
            ensureChannel()
            setForeground(createForeground(name, 0, size))
            setProgress(Data.Builder().putString(KEY_NAME, name).putLong(KEY_BYTES, 0).putLong(KEY_TOTAL, size).build())
            if (resumeId != null) {
                val status = request(client, server, token, "GET", "upload/status", id = resumeId)
                if (status.code == 404) { status.close(); id = null }
                else if (!status.isSuccessful) { val code = status.code; status.close(); throw UploadHttpException(code, "Could not resume upload ($code).") }
                else {
                    val received = status.body?.string()?.let(::JSONObject)?.optLong("offset") ?: 0L
                    checkpoints.edit(commit = true) { putString(idKey(), resumeId) }
                    transfer(client, server, token, file, resumeId, received, size, name)
                    finish(client, server, token, resumeId)
                    checkpoints.edit { remove(idKey()) }
                    queueMetadata(applicationContext).edit { remove(this@UploadWorker.id.toString()) }
                    file.delete()
                    return@withContext Result.success()
                }
            }
            val start = JSONObject().put("dir", dir).put("name", name).put("size", size).put("overwrite", overwrite).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val response = request(client, server, token, "POST", "upload/start", body = start)
            if (!response.isSuccessful) { val code = response.code; response.close(); throw UploadHttpException(code, "Could not start upload ($code).") }
            id = response.body?.string()?.let(::JSONObject)?.getString("id") ?: throw IOException("The server did not return an upload id.")
            checkpoints.edit(commit = true) { putString(idKey(), id) }
            transfer(client, server, token, file, id, 0, size, name)
            finish(client, server, token, id)
            checkpoints.edit { remove(idKey()) }
            queueMetadata(applicationContext).edit { remove(this@UploadWorker.id.toString()) }
            file.delete()
            Result.success()
        } catch (e: UploadHttpException) {
            if (e.status >= 500 || e.status == 408 || e.status == 429) Result.retry()
            else Result.failure(workDataOfMessage(e.message ?: "Upload failed (${e.status})."))
        } catch (e: IOException) {
            Result.retry()
        } catch (e: Exception) {
            Result.failure(workDataOfMessage(e.message ?: "Upload failed."))
        }
    }

    private suspend fun transfer(client: OkHttpClient, server: HttpUrl, token: String, file: File, id: String, start: Long, size: Long, name: String) {
        var offset = start.coerceIn(0, size)
        val chunkSize = 8L * 1024 * 1024
        while (offset < size) {
            val length = minOf(chunkSize, size - offset)
            val body = FileRangeBody(file, offset, length) { sent ->
                val current = offset + sent
                setProgressAsync(Data.Builder().putString(KEY_NAME, name).putLong(KEY_BYTES, current).putLong(KEY_TOTAL, size).build()).get()
                setForegroundAsync(createForeground(name, current, size)).get()
            }
            val response = request(client, server, token, "PUT", "upload/chunk", id = id, offset = offset, body = body)
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                if (code !in setOf(408, 409, 429) && code !in 500..599) {
                    throw UploadHttpException(code, "Upload chunk was rejected ($code).")
                }
                val status = request(client, server, token, "GET", "upload/status", id = id)
                if (status.code == 404) { status.close(); throw UploadHttpException(404, "Upload expired on the server. Retry to start again.") }
                if (!status.isSuccessful) { val statusCode = status.code; status.close(); throw UploadHttpException(statusCode, "Could not check upload progress ($statusCode).") }
                val actual = status.body?.string()?.let(::JSONObject)?.optLong("offset") ?: offset
                if (actual == offset) throw IOException("Upload chunk was interrupted.")
                offset = actual.coerceIn(0, size)
                continue
            }
            val actual = response.body?.string()?.let(::JSONObject)?.optLong("offset") ?: (offset + length)
            if (actual <= offset) { response.close(); throw IOException("The server did not advance the upload.") }
            offset = actual.coerceAtMost(size)
        }
    }

    private fun finish(client: OkHttpClient, server: HttpUrl, token: String, id: String) {
        val response = request(client, server, token, "POST", "upload/finish", body = JSONObject().put("id", id).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (!response.isSuccessful) { val code = response.code; response.close(); throw UploadHttpException(code, "Could not finish upload ($code).") }
        response.close()
    }

    private fun request(client: OkHttpClient, server: HttpUrl, token: String, method: String, endpoint: String, id: String? = null, offset: Long? = null, body: RequestBody? = null): Response {
        val url = server.newBuilder().addPathSegment("api").addPathSegments(endpoint).apply {
            id?.let { addQueryParameter("id", it) }
            offset?.let { addQueryParameter("offset", it.toString()) }
        }.build()
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
        when (method) {
            "GET" -> builder.get()
            "PUT" -> builder.put(requireNotNull(body))
            "POST" -> builder.post(requireNotNull(body))
        }
        return client.newCall(builder.build()).execute()
    }

    private fun createForeground(name: String, current: Long, total: Long): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Uploading to Holodrive")
            .setContentText(name)
            .setProgress(100, if (total == 0L) 0 else ((current * 100 / total).toInt()).coerceIn(0, 100), total == 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID + id.hashCode(), notification)
    }

    private fun ensureChannel() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Holodrive uploads", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun idKey(): String {
        val filePath = inputData.getString(KEY_FILE).orEmpty()
        val digest = MessageDigest.getInstance("SHA-256").digest(filePath.toByteArray())
        return "upload-" + digest.joinToString("") { "%02x".format(it) }
    }

    private class FileRangeBody(private val file: File, private val offset: Long, private val length: Long, private val progress: (Long) -> Unit) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = length
        override fun writeTo(sink: okio.BufferedSink) {
            FileInputStream(file).use { input ->
                input.channel.position(offset)
                val buffer = ByteArray(128 * 1024)
                var left = length
                var sent = 0L
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("The shared file ended during upload.")
                    sink.write(buffer, 0, n)
                    left -= n
                    sent += n
                    if (sent % (512 * 1024) < n || left == 0L) progress(sent)
                }
            }
        }
    }

    private class UploadHttpException(val status: Int, message: String) : IOException(message)

    companion object {
        const val TAG = "holodrive-upload"
        const val TAG_NAME = "$TAG-name:"
        const val TAG_SIZE = "$TAG-size:"
        const val CHAIN = "holodrive-upload-queue"
        const val KEY_FILE = "file"
        const val KEY_NAME = "name"
        const val KEY_DIR = "dir"
        const val KEY_SERVER = "server"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_SIZE = "size"
        const val KEY_OVERWRITE = "overwrite"
        private const val CHANNEL = "holodrive_uploads"
        private const val NOTIFICATION_ID = 7300

        private fun queueMetadata(context: Context) = context.getSharedPreferences("upload_queue_metadata", Context.MODE_PRIVATE)

        fun workDataOfMessage(message: String): Data = Data.Builder().putString("error", message).build()

        fun enqueue(context: Context, server: String, dir: String, staged: List<Pair<File, String>>, overwrite: Boolean = false) {
            val requests = staged.map { (file, name) ->
                val requestData = Data.Builder().putString(KEY_FILE, file.absolutePath).putString(KEY_NAME, name)
                    .putLong(KEY_SIZE, file.length()).putString(KEY_DIR, dir).putString(KEY_SERVER, server)
                    .putBoolean(KEY_OVERWRITE, overwrite).build()
                val request = androidx.work.OneTimeWorkRequestBuilder<UploadWorker>()
                    .setInputData(requestData)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag(TAG).addTag(TAG_NAME + name.take(128)).addTag(TAG_SIZE + file.length()).build()
                queueMetadata(context).edit { putString(request.id.toString(), JSONObject()
                    .put(KEY_FILE, file.absolutePath).put(KEY_NAME, name).put(KEY_SIZE, file.length())
                    .put(KEY_DIR, dir).put(KEY_SERVER, server).put(KEY_OVERWRITE, overwrite).toString()) }
                request
            }
            if (requests.isNotEmpty()) WorkManager.getInstance(context)
                .beginUniqueWork(CHAIN, androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE, requests)
                .enqueue()
        }

        fun observe(context: Context) = WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG)
        fun cancel(context: Context, id: String) = WorkManager.getInstance(context).cancelWorkById(java.util.UUID.fromString(id))
        suspend fun retry(context: Context, id: String) {
            val manager = WorkManager.getInstance(context)
            val info = manager.getWorkInfoByIdFlow(java.util.UUID.fromString(id)).first() ?: return
            if (info.state != WorkInfo.State.FAILED && info.state != WorkInfo.State.CANCELLED) return
            val saved = queueMetadata(context).getString(id, null)?.let(::JSONObject) ?: return
            val file = saved.getString(KEY_FILE)
            val name = saved.getString(KEY_NAME)
            val size = saved.getLong(KEY_SIZE)
            val data = Data.Builder().putString(KEY_FILE, file).putString(KEY_NAME, name).putLong(KEY_SIZE, size)
                .putString(KEY_DIR, saved.getString(KEY_DIR)).putString(KEY_SERVER, saved.getString(KEY_SERVER))
                .putBoolean(KEY_OVERWRITE, saved.optBoolean(KEY_OVERWRITE, false)).build()
            val request = androidx.work.OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG).addTag(TAG_NAME + name.take(128)).addTag(TAG_SIZE + size).build()
            queueMetadata(context).edit { putString(request.id.toString(), saved.toString()) }
            manager.beginUniqueWork(CHAIN, androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE, listOf(request)).enqueue()
        }
    }
}
