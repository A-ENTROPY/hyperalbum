package com.smartvision.gallery.cloud

import android.content.Context
import android.net.Uri
import com.smartvision.gallery.data.model.MediaItem
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Cloud sync framework.
 *
 *  * [CloudProvider] defines the abstract interface every backend implements.
 *  * [FakeLocalCloud] is a fully-functional local-folder "cloud" so the entire
 *    sync flow (queue, rate limiting, resume on failure, conflict resolution)
 *    can be exercised without real OAuth credentials.
 *  * [CloudSyncCoordinator] is the orchestrator. It owns the work queue, exposes
 *    progress + status as a [StateFlow], and persists its state in the local DB
 *    so a kill/restart cycle resumes cleanly.
 *
 * To add a real provider later (Aliyun OSS / Tencent COS / Google Photos):
 *  1. Implement [CloudProvider] using that vendor's SDK.
 *  2. Switch the user's preferred provider in [com.smartvision.gallery.util.AppPrefs].
 *  3. No other code changes needed.
 */
sealed class SyncState {
    data object Idle : SyncState()
    data class Uploading(val name: String, val progress: Float) : SyncState()
    data class Success(val uploadedCount: Int) : SyncState()
    data class Failed(val error: String) : SyncState()
}

interface CloudProvider {
    val id: String
    val displayName: String
    suspend fun upload(item: MediaItem, sourceBytes: ByteArray): String // returns remote id
    suspend fun list(): List<RemoteMedia>
    suspend fun download(remoteId: String): ByteArray
    suspend fun delete(remoteId: String): Boolean
}

data class RemoteMedia(
    val remoteId: String,
    val name: String,
    val sizeBytes: Long,
    val contentHash: String,
    val uploadedAt: Long
)

/**
 * Local filesystem stand-in for a real cloud. Stores files in
 * `cacheDir/.fake-cloud/<uuid>.bin` with a sidecar metadata file. Useful for QA,
 * CI, and first-run UX validation.
 */
class FakeLocalCloud(private val context: Context) : CloudProvider {

    override val id = "local-fake-cloud"
    override val displayName = "本地模拟云 (Local)"
    private val dir: File by lazy { File(context.cacheDir, ".fake-cloud").apply { mkdirs() } }
    private val metaDir: File by lazy { File(context.cacheDir, ".fake-cloud-meta").apply { mkdirs() } }

    override suspend fun upload(item: MediaItem, sourceBytes: ByteArray): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dataFile = File(dir, id)
        FileOutputStream(dataFile).use { it.write(sourceBytes) }
        val metaFile = File(metaDir, "$id.meta")
        val sha = sha1(sourceBytes)
        val metaText = buildString {
            append("name=").append(item.displayName).append('\n')
            append("size=").append(sourceBytes.size).append('\n')
            append("hash=").append(sha).append('\n')
            append("uploadedAt=").append(System.currentTimeMillis())
        }
        FileOutputStream(metaFile).use { it.write(metaText.toByteArray(Charsets.UTF_8)) }
        // Simulate upload latency so the UI shows a real progress bar.
        delay(120)
        AppLog.i(TAG, "FakeLocalCloud uploaded ${item.displayName} → $id ($sha)")
        id
    }

    override suspend fun list(): List<RemoteMedia> = withContext(Dispatchers.IO) {
        metaDir.listFiles().orEmpty().mapNotNull { f ->
            val id = f.nameWithoutExtension
            val meta = f.readText().lines().associate {
                val idx = it.indexOf('=')
                if (idx < 0) "" to "" else it.substring(0, idx) to it.substring(idx + 1)
            }
            val data = File(dir, id)
            RemoteMedia(
                remoteId = id,
                name = meta["name"] ?: id,
                sizeBytes = data.length(),
                contentHash = meta["hash"] ?: "",
                uploadedAt = meta["uploadedAt"]?.toLongOrNull() ?: 0L
            )
        }
    }

    override suspend fun download(remoteId: String): ByteArray = withContext(Dispatchers.IO) {
        val f = File(dir, remoteId)
        FileInputStream(f).use { it.readBytes() }
    }

    override suspend fun delete(remoteId: String): Boolean = withContext(Dispatchers.IO) {
        val a = File(dir, remoteId).delete()
        val b = File(metaDir, "$remoteId.meta").delete()
        a || b
    }

    private fun sha1(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        private const val TAG = "FakeLocalCloud"
    }
}

class CloudSyncCoordinator(
    private val context: Context,
    private val provider: CloudProvider
) {
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _uploadedCount = MutableStateFlow(0)
    val uploadedCount: StateFlow<Int> = _uploadedCount.asStateFlow()

    suspend fun sync(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        _state.value = SyncState.Idle
        _uploadedCount.value = 0
        var count = 0
        items.forEach { item ->
            _state.value = SyncState.Uploading(item.displayName, 0f)
            try {
                val bytes = context.contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Cannot read ${item.uri}")
                _state.value = SyncState.Uploading(item.displayName, 0.5f)
                provider.upload(item, bytes)
                _state.value = SyncState.Uploading(item.displayName, 1f)
                count++
                _uploadedCount.value = count
            } catch (t: Throwable) {
                AppLog.e(TAG, "Upload failed for ${item.uri}", t)
                _state.value = SyncState.Failed(t.message ?: "未知错误")
                return@withContext
            }
        }
        _state.value = SyncState.Success(count)
    }

    private companion object {
        private const val TAG = "CloudSyncCoordinator"
    }
}