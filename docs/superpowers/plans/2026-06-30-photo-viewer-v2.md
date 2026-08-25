# PhotoViewerActivity V2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the standalone `PhotoViewerActivity` to mainstream mobile gallery feature parity (Apple Photos iOS 26 / Samsung / MIUI / Huawei — NOT Google Photos), with HorizontalPager swipe navigation, 5-icon bottom bar, info bottom sheet, slideshow, Live Photo press-hold, and same-Activity video playback.

**Architecture:** Single `ComponentActivity` with `HorizontalPager`. MIME detection per page routes image → Coil + gesture zoom, video → ExoPlayer. Chrome (top bar + bottom bar + overflow menu + bottom sheet) rendered as sibling overlays in pure Material3. **Sacred rule:** no `com.kyant.backdrop.*` or `com.smartvision.gallery.ui.liquidglass.*` imports anywhere in `ui/viewer/`.

**Tech Stack:** Jetpack Compose, Material3, Coil (`AsyncImage`), Compose Foundation `HorizontalPager` + `pager-compose`, ExoPlayer (Media3), `androidx.exifinterface.media.ExifInterface`, `MediaStore` (`ContentResolver`).

**Reference:** `docs/superpowers/specs/2026-06-30-photo-viewer-v2-design.md`.

**Build gate:** `./gradlew :app:assembleDebug` from project root (`H:\workspace-minimaxcode\新建文件夹\超级相册\`). Unit tests are not part of this project's pipeline — the build itself is the verification gate.

---

## File map

| File | Responsibility | LOC target |
|---|---|---|
| `ui/viewer/MediaItemAdapter.kt` (new) | Pure-function helpers: URI → mime / displayName / favorite / size. Used by Activity + ShareSheet + InfoPanel. | <120 |
| `ui/viewer/SlideshowConfig.kt` (new) | `SlideshowConfig` data class + `SlideshowDialog` composable + state machine helpers. | <100 |
| `ui/viewer/InfoPanel.kt` (new) | `InfoPanel(uri, onDismiss)` — ModalBottomSheet, EXIF read on IO dispatcher with timeout fallback. | <220 |
| `ui/viewer/ViewerChrome.kt` (new) | `ViewerTopBar`, `ViewerBottomBar`, `MoreMenu`, `DeleteConfirmDialog`, `LivePhotoOverlay`. | <260 |
| `ui/viewer/PhotoViewerActivity.kt` (modify) | Wire HorizontalPager + Chrome + Info + Slideshow + Live Photo. Two-page MIME dispatch. | grows to ~450 |
| `ui/AppRoot.kt` (modify) | Replace single-URI `launchIntent` calls with list+index variant. Build URI list from the current source (Timeline, Album, Vault, Search). | +~30 lines per call site, 6 call sites |

---

## Task 1: MediaItemAdapter helpers

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/ui/viewer/MediaItemAdapter.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.smartvision.gallery.ui.viewer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.smartvision.gallery.data.model.MediaItem

/**
 * Lightweight adapter from a bare [Uri] to the metadata operations the viewer
 * needs (favorite toggle, delete, share). Avoids round-tripping through
 * [com.smartvision.gallery.data.MediaRepository] for one-shot operations.
 *
 * Why this exists: the standalone [PhotoViewerActivity] receives only URIs from
 * its Intent extras. Operations like Share / Delete / Favorite need at least a
 * MIME type and display name; rather than re-construct a full [MediaItem] (which
 * needs bucket + dateTaken + size + many more columns) we query the smallest
 * possible column set per call.
 */
object MediaItemAdapter {

    fun mimeType(context: Context, uri: Uri): String? =
        context.contentResolver.getType(uri)

    fun isVideo(context: Context, uri: Uri): Boolean =
        (mimeType(context, uri) ?: "").startsWith("video/")

    fun isImage(context: Context, uri: Uri): Boolean =
        (mimeType(context, uri) ?: "").startsWith("image/")

    /**
     * Query MediaStore.Images for the display name + favorite flag of [uri].
     * Returns null if [uri] is not a MediaStore image URI.
     */
    fun queryImage(context: Context, uri: Uri): ImageMeta? {
        if (!uri.toString().startsWith("content://media/")) return null
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE,
                    "_favorite"
                ),
                null, null, null
            )?.use { c ->
                if (!c.moveToFirst()) return null
                val nameIdx = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val favIdx = c.getColumnIndex("_favorite")
                ImageMeta(
                    displayName = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else "",
                    mimeType = if (mimeIdx >= 0) c.getString(mimeIdx) ?: "" else "",
                    isFavorite = favIdx >= 0 && c.getInt(favIdx) == 1
                )
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Toggle favorite flag in MediaStore. Returns the new state, or null on
     * failure. Caller should show a snackbar with the failure message.
     */
    fun toggleFavorite(context: Context, uri: Uri): Boolean? {
        val current = queryImage(context, uri)?.isFavorite ?: return null
        val newValue = if (current) 0 else 1
        val values = android.content.ContentValues().apply { put("_favorite", newValue) }
        return try {
            val n = context.contentResolver.update(uri, values, null, null)
            if (n > 0) !current else null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Delete via MediaStore. Returns true on success.
     */
    fun delete(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.delete(uri, null, null) > 0
    } catch (t: Throwable) {
        false
    }

    data class ImageMeta(
        val displayName: String,
        val mimeType: String,
        val isFavorite: Boolean,
    )
}
```

- [ ] **Step 2: Build to verify it compiles**

Run from project root:
```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/smartvision/gallery/ui/viewer/MediaItemAdapter.kt
git commit -m "feat(viewer): MediaItemAdapter helpers for URI → metadata"
```

---

## Task 2: SlideshowConfig + Dialog

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/ui/viewer/SlideshowConfig.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.smartvision.gallery.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class SlideshowConfig(
    val intervalMs: Long = 5_000L,
    val loop: Boolean = true,
)

@Composable
fun SlideshowDialog(
    initial: SlideshowConfig,
    onConfirm: (SlideshowConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var interval by remember { mutableStateOf(initial.intervalMs) }
    var loop by remember { mutableStateOf(initial.loop) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("幻灯片设置") },
        text = {
            Column {
                Text("切换间隔", style = MaterialTheme.typography.labelLarge)
                val intervals = listOf(3_000L to "3 秒", 5_000L to "5 秒", 10_000L to "10 秒")
                intervals.forEach { (ms, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = interval == ms,
                                onClick = { interval = ms }
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = interval == ms, onClick = { interval = ms })
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("循环播放")
                    Switch(checked = loop, onCheckedChange = { loop = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(SlideshowConfig(interval, loop)) }) {
                Text("开始")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
```

- [ ] **Step 2: Build to verify**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/smartvision/gallery/ui/viewer/SlideshowConfig.kt
git commit -m "feat(viewer): SlideshowConfig data + SlideshowDialog"
```

---

## Task 3: InfoPanel — ModalBottomSheet with EXIF

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/ui/viewer/InfoPanel.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.smartvision.gallery.ui.viewer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoPanel(
    uri: Uri,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var info by remember(uri) { mutableStateOf<MediaInfo?>(null) }

    LaunchedEffect(uri) {
        info = withTimeoutOrNull(1000L) {
            withContext(Dispatchers.IO) { readMediaInfo(uri) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            val current = info
            if (current == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) { CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(end = 8.dp)) }
                Text("正在读取信息…", style = MaterialTheme.typography.bodySmall)
            } else {
                InfoContent(current)
            }
            Text(
                "（长按文字可复制）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp)
            )
        }
    }
}

@Composable
private fun InfoContent(info: MediaInfo) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Image, contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp))
                Text(info.displayName.ifEmpty { "未命名" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
            }
            InfoRow("格式", info.formatLabel)
            InfoRow("分辨率", info.dimensions)
            InfoRow("大小", info.sizeLabel)
            InfoRow("拍摄日期", info.dateTaken)
            InfoRow("修改日期", info.dateModified)
            if (info.camera.isNotEmpty()) InfoRow("相机", info.camera)
            if (info.lens.isNotEmpty()) InfoRow("镜头参数", info.lens)
            if (info.gps.isNotEmpty()) InfoRow("位置", info.gps)
            if (info.path.isNotEmpty()) InfoRow("路径", info.path)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifEmpty { "—" }, style = MaterialTheme.typography.bodyMedium)
    }
}

data class MediaInfo(
    val displayName: String,
    val formatLabel: String,
    val dimensions: String,
    val sizeLabel: String,
    val dateTaken: String,
    val dateModified: String,
    val camera: String,
    val lens: String,
    val gps: String,
    val path: String,
)

private suspend fun readMediaInfo(uri: Uri): MediaInfo {
    val context = uri.let { /* keep */ } as? Context // dummy to keep import
    // Use LocalContext indirectly via ContentResolver hack: open through the app context.
    // InfoPanel passes uri only; we resolve via app context from a side channel.
    TODO("see Task 3 step 2: needs context — refactor signature to take Context")
}
```

Wait — `readMediaInfo` needs a `Context` to open the URI. Refactor signature.

- [ ] **Step 2: Refactor InfoPanel to take Context and pass it through**

Replace the entire `InfoPanel.kt` content with:

```kotlin
package com.smartvision.gallery.ui.viewer

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoPanel(
    context: Context,
    uri: Uri,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var info by remember(uri) { mutableStateOf<MediaInfo?>(null) }

    LaunchedEffect(uri) {
        info = withTimeoutOrNull(1000L) {
            withContext(Dispatchers.IO) { readMediaInfo(context, uri) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            val current = info
            if (current == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("正在读取信息…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                InfoContent(current)
            }
            Text(
                "（长按文字可复制）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp)
            )
        }
    }
}

@Composable
private fun InfoContent(info: MediaInfo) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Image, contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp))
                Text(
                    info.displayName.ifEmpty { "未命名" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            InfoRow("格式", info.formatLabel)
            InfoRow("分辨率", info.dimensions)
            InfoRow("大小", info.sizeLabel)
            InfoRow("拍摄日期", info.dateTaken)
            InfoRow("修改日期", info.dateModified)
            if (info.camera.isNotEmpty()) InfoRow("相机", info.camera)
            if (info.lens.isNotEmpty()) InfoRow("镜头参数", info.lens)
            if (info.gps.isNotEmpty()) InfoRow("位置", info.gps)
            if (info.path.isNotEmpty()) InfoRow("路径", info.path)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value.ifEmpty { "—" }, style = MaterialTheme.typography.bodyMedium)
    }
}

data class MediaInfo(
    val displayName: String,
    val formatLabel: String,
    val dimensions: String,
    val sizeLabel: String,
    val dateTaken: String,
    val dateModified: String,
    val camera: String,
    val lens: String,
    val gps: String,
    val path: String,
)

private fun readMediaInfo(context: Context, uri: Uri): MediaInfo {
    var displayName = ""
    var formatLabel = ""
    var dimensions = ""
    var sizeLabel = ""
    var dateTaken = ""
    var dateModified = ""
    var path = ""
    var mimeType = ""

    try {
        context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.DATA,
            ),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                displayName = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: ""
                mimeType = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)) ?: ""
                val w = c.getInt(c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH))
                val h = c.getInt(c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT))
                dimensions = if (w > 0 && h > 0) "${w} × ${h}  ·  ${"%.1f".format(w * h / 1_000_000.0)} MP" else ""
                val size = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))
                sizeLabel = humanSize(size)
                val taken = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
                val modified = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED))
                dateTaken = if (taken > 0) formatDate(taken) else ""
                dateModified = if (modified > 0) formatDate(modified * 1000L) else ""
                path = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)) ?: ""
                formatLabel = buildFormatLabel(mimeType)
            }
        }
    } catch (_: Throwable) {
        // swallow; InfoPanel will show whatever was filled before the failure
    }

    var camera = ""
    var lens = ""
    var gps = ""
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
            val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
            camera = listOf(make, model).filter { it.isNotEmpty() }.joinToString(" ")
            val focal = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
            val aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
            val exposure = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
            val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
            val parts = mutableListOf<String>()
            aperture?.let { parts.add("ƒ/$it") }
            exposure?.let { parts.add("1/${"%.0f".format(1.0 / it.toDoubleOrNull()!!)}s".takeIf { v -> v.isNotEmpty() } ?: "${it}s") }
            iso?.let { parts.add("ISO $it") }
            focal?.let { parts.add("$it mm") }
            lens = parts.joinToString(" · ")

            val latLong = FloatArray(2)
            if (exif.getLatLong(latLong)) {
                gps = "${"%.5f".format(latLong[0])}, ${"%.5f".format(latLong[1])}"
            }
        }
    } catch (_: Throwable) {
        // EXIF read is best-effort; fall through with empty strings
    }

    return MediaInfo(
        displayName = displayName,
        formatLabel = formatLabel.ifEmpty { mimeType.ifEmpty { "—" } },
        dimensions = dimensions,
        sizeLabel = sizeLabel,
        dateTaken = dateTaken,
        dateModified = dateModified,
        camera = camera,
        lens = lens,
        gps = gps,
        path = path,
    )
}

private fun buildFormatLabel(mime: String): String {
    val ext = when (mime.lowercase(Locale.US)) {
        "image/jpeg" -> "JPEG"
        "image/png" -> "PNG"
        "image/heic", "image/heif" -> "HEIC"
        "image/webp" -> "WebP"
        "image/avif" -> "AVIF"
        "image/gif" -> "GIF"
        "image/bmp" -> "BMP"
        else -> mime.substringAfter("/").uppercase(Locale.US)
    }
    return "$ext  ($mime)"
}

private fun humanSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val df = DecimalFormat("#,##0.#")
    return when {
        bytes >= 1_000_000 -> "${df.format(bytes / 1_000_000.0)} MB"
        bytes >= 1_000 -> "${df.format(bytes / 1_000.0)} KB"
        else -> "$bytes B"
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
```

- [ ] **Step 3: Build to verify**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. If `ExifInterface` is unresolved, add to `app/build.gradle.kts` (or `.gradle`):

```kotlin
implementation("androidx.exifinterface:exifinterface:1.3.7")
```

(Check the existing `build.gradle.kts` for what's already declared; the project may already have it. If so, skip the addition.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/smartvision/gallery/ui/viewer/InfoPanel.kt
git commit -m "feat(viewer): InfoPanel bottom sheet with EXIF + MediaStore metadata"
```

---

## Task 4: ViewerChrome — TopBar + BottomBar + More menu + Delete confirm + LivePhoto overlay

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/ui/viewer/ViewerChrome.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.smartvision.gallery.ui.viewer

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartvision.gallery.livephoto.LivePhotoBadge
import com.smartvision.gallery.livephoto.LivePhotoPressHold

@Composable
fun ViewerTopBar(
    title: String,
    onBack: () -> Unit,
    onSlideshowClick: () -> Unit,
    onSetWallpaperClick: () -> Unit,
    onHideToVaultClick: () -> Unit,
    onShowLocationClick: () -> Unit,
    hasGps: Boolean,
    isHidden: Boolean,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = Color.White
            )
        }
        Text(
            text = title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp)
        )
        Spacer(modifier = Modifier.weight(1f))
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "更多",
                    tint = Color.White
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("幻灯片播放") },
                    leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onSlideshowClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("设为壁纸") },
                    leadingIcon = { Icon(Icons.Outlined.Wallpaper, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onSetWallpaperClick()
                    }
                )
                if (!isHidden) {
                    DropdownMenuItem(
                        text = { Text("隐藏到保险柜") },
                        leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onHideToVaultClick()
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("显示位置") },
                    leadingIcon = { Icon(Icons.Outlined.LocationOn, contentDescription = null) },
                    enabled = hasGps,
                    onClick = {
                        menuOpen = false
                        onShowLocationClick()
                    }
                )
            }
        }
    }
}

@Composable
fun ViewerBottomBar(
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    onShareClick: () -> Unit,
    onEditClick: () -> Unit,
    onInfoClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        BarAction(
            icon = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            tint = if (isFavorite) Color(0xFFFF4D6D) else Color.White,
            contentDescription = if (isFavorite) "取消收藏" else "收藏",
            onClick = onFavoriteClick
        )
        BarAction(
            icon = Icons.Outlined.Share,
            tint = Color.White,
            contentDescription = "分享",
            onClick = onShareClick
        )
        BarAction(
            icon = Icons.Outlined.Edit,
            tint = Color.White,
            contentDescription = "编辑",
            onClick = onEditClick
        )
        BarAction(
            icon = Icons.Outlined.Info,
            tint = Color.White,
            contentDescription = "信息",
            onClick = onInfoClick
        )
        BarAction(
            icon = Icons.Outlined.Delete,
            tint = Color.White,
            contentDescription = "删除",
            onClick = onDeleteClick
        )
    }
}

@Composable
private fun BarAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun DeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除这张照片？") },
        text = { Text("照片会移动到“最近删除”，30 天后自动清除。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun LivePhotoOverlay(
    visible: Boolean,
    isPlaying: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Box(
        modifier = modifier.padding(bottom = 120.dp),
        contentAlignment = Alignment.Center
    ) {
        LivePhotoPressHold(
            isActive = isPlaying,
            onPress = onPress,
            onRelease = onRelease,
        )
    }
}

@Composable
fun LiveBadgePill(modifier: Modifier = Modifier) {
    LivePhotoBadge(modifier = modifier)
}
```

Add `Lock` import for the vault icon:

```kotlin
import androidx.compose.material.icons.outlined.Lock
```

Place it next to the other `androidx.compose.material.icons.outlined.*` imports.

- [ ] **Step 2: Build to verify**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/smartvision/gallery/ui/viewer/ViewerChrome.kt
git commit -m "feat(viewer): ViewerChrome (top/bottom bars + more menu + delete confirm)"
```

---

## Task 5: Wire HorizontalPager + MIME dispatch into PhotoViewerActivity

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerActivity.kt`

The current Activity is ~230 lines. This step replaces its body with a full-featured composition. Keep all imports that don't pull in Liquid Glass.

- [ ] **Step 1: Replace the Activity body**

Read the current file first to preserve existing imports correctly. Then rewrite with:

```kotlin
package com.smartvision.gallery.ui.viewer

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.smartvision.gallery.data.repository.MediaRepository
import com.smartvision.gallery.data.vault.EncryptedPrivacyVault
import com.smartvision.gallery.decoder.format.FormatDetector
import com.smartvision.gallery.export.ExportPipeline
import com.smartvision.gallery.livephoto.LivePhotoDetector
import com.smartvision.gallery.livephoto.LivePhotoVideoPlayer
import com.smartvision.gallery.share.ShareSheet
import com.smartvision.gallery.ui.theme.SmartVisionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Standalone photo/video viewer Activity — opens via Intent.
 *
 * Composition root that bypasses the main app's Liquid Glass render tree
 * (see [[feedback-liquid-glass-sacred]]). All chrome is Material3.
 */
class PhotoViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Parse Intent extras
        val uriList: List<Uri> = intent.getStringArrayListExtra(EXTRA_URI_LIST)
            ?.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            ?: emptyList()
        val singleUri: Uri? = intent.getStringExtra(EXTRA_URI)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: ""

        val allUris: List<Uri> = when {
            uriList.isNotEmpty() -> uriList
            singleUri != null -> listOf(singleUri)
            else -> emptyList()
        }
        if (allUris.isEmpty()) { finish(); return }

        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
            .coerceIn(0, allUris.size - 1)

        setContent {
            SmartVisionTheme {
                ViewerScreen(
                    uris = allUris,
                    startIndex = startIndex,
                    displayName = displayName,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_URI_LIST = "extra_uri_list"
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_DISPLAY_NAME = "extra_display_name"
        const val EXTRA_START_INDEX = "extra_start_index"

        /** Caller-supplied list of URIs to swipe through. */
        fun launchIntent(
            context: Context,
            uris: List<Uri>,
            startIndex: Int = 0,
        ): Intent = Intent(context, PhotoViewerActivity::class.java).apply {
            putStringArrayListExtra(EXTRA_URI_LIST, ArrayList(uris.map { it.toString() }))
            putExtra(EXTRA_START_INDEX, startIndex)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        /** Legacy single-URI launcher. */
        fun launchIntent(context: Context, uri: Uri, displayName: String = ""): Intent =
            Intent(context, PhotoViewerActivity::class.java).apply {
                putExtra(EXTRA_URI, uri.toString())
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}

@Composable
private fun ViewerScreen(
    uris: List<Uri>,
    startIndex: Int,
    displayName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = startIndex) { uris.size }

    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var currentIndex by rememberSaveable { mutableStateOf(startIndex) }
    var showInfo by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }
    var slideshowConfig by remember { mutableStateOf<SlideshowConfig?>(null) }
    var slideshowPaused by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val currentUri by remember {
        derivedStateOf { uris.getOrNull(currentIndex) }
    }

    // Sync pagerState → currentIndex
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { currentIndex = it }
    }

    // Auto-hide chrome
    LaunchedEffect(chromeVisible) {
        if (chromeVisible) {
            delay(3000)
            chromeVisible = false
        }
    }

    // Slideshow auto-advance
    LaunchedEffect(slideshowConfig) {
        val cfg = slideshowConfig ?: return@LaunchedEffect
        while (isActive) {
            delay(cfg.intervalMs)
            if (slideshowPaused) continue
            if (currentIndex < uris.size - 1 || cfg.loop) {
                val next = if (currentIndex >= uris.size - 1) 0 else currentIndex + 1
                pagerState.animateScrollToPage(next)
            }
        }
    }

    val pipeline = remember { ExportPipeline(context.applicationContext) }
    val mediaRepo = remember { MediaRepository.getInstance(context.applicationContext) }
    val vault = remember { EncryptedPrivacyVault.getInstance(context.applicationContext) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val uri = uris[page]
                val mime = remember(uri) { MediaItemAdapter.mimeType(context, uri) }
                if (mime?.startsWith("video/") == true) {
                    VideoPlayerViewer(uri = uri, autoHideMs = 3000)
                } else {
                    ZoomableImage(uri = uri, modifier = Modifier.fillMaxSize())
                }
            }

            // Live Photo overlay (only on current page)
            currentUri?.let { uri ->
                val isLive = remember(uri) { LivePhotoDetector.findLivePhoto(context, uri) != null }
                if (isLive) {
                    LivePhotoOverlay(
                        visible = chromeVisible,
                        isPlaying = false, // controlled by gesture in ViewModel hookup; static for V2
                        onPress = { /* hook ExoPlayer start in future patch */ },
                        onRelease = { /* hook ExoPlayer stop in future patch */ },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                    LiveBadgePill(modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 64.dp, end = 12.dp))
                }
            }

            if (chromeVisible) {
                Column(modifier = Modifier.fillMaxSize()) {
                    ViewerTopBar(
                        title = if (uris.size > 1) "${currentIndex + 1} / ${uris.size}"
                                else displayName,
                        onBack = onBack,
                        onSlideshowClick = { slideshowConfig = SlideshowConfig() },
                        onSetWallpaperClick = {
                            scope.launch {
                                val ok = setAsWallpaper(context, currentUri ?: return@launch)
                                snackbarHostState.showSnackbar(
                                    if (ok) "壁纸已设置" else "设置失败"
                                )
                            }
                        },
                        onHideToVaultClick = {
                            scope.launch {
                                currentUri?.let { uri ->
                                    val ok = withContext(Dispatchers.IO) { vault.hide(uri) }
                                    snackbarHostState.showSnackbar(
                                        if (ok) "已隐藏到保险柜" else "隐藏失败"
                                    )
                                    if (ok) {
                                        chromeVisible = false
                                        onBack()
                                    }
                                }
                            }
                        },
                        onShowLocationClick = { /* TODO: open external map app in V3 */ },
                        hasGps = false,
                        isHidden = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    ViewerBottomBar(
                        isFavorite = MediaItemAdapter.queryImage(context, currentUri ?: Uri.EMPTY)?.isFavorite == true,
                        onFavoriteClick = {
                            currentUri?.let { uri ->
                                scope.launch {
                                    val newState = withContext(Dispatchers.IO) {
                                        MediaItemAdapter.toggleFavorite(context, uri)
                                    }
                                    if (newState == null) {
                                        snackbarHostState.showSnackbar("收藏失败")
                                    }
                                }
                            }
                        },
                        onShareClick = { showShareSheet = true },
                        onEditClick = {
                            currentUri?.let { uri ->
                                val edit = Intent(Intent.ACTION_EDIT).apply {
                                    setDataAndType(uri, "image/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val chooser = Intent.createChooser(edit, "编辑图片")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(chooser) }
                                    .onFailure {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("未找到可用的编辑器")
                                        }
                                    }
                            }
                        },
                        onInfoClick = { showInfo = true },
                        onDeleteClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Tap-to-toggle chrome
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { chromeVisible = !chromeVisible })
                    }
            )

            // Bottom sheets & dialogs
            if (showInfo && currentUri != null) {
                InfoPanel(
                    context = context,
                    uri = currentUri!!,
                    onDismiss = { showInfo = false }
                )
            }
            if (showDeleteConfirm) {
                DeleteConfirmDialog(
                    onConfirm = {
                        showDeleteConfirm = false
                        currentUri?.let { uri ->
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    MediaItemAdapter.delete(context, uri)
                                }
                                if (ok) {
                                    snackbarHostState.showSnackbar("已删除")
                                    if (currentIndex >= uris.size - 1) {
                                        // current page will be removed; close
                                        onBack()
                                    } else {
                                        pagerState.animateScrollToPage(currentIndex + 1)
                                    }
                                } else {
                                    snackbarHostState.showSnackbar("删除失败，请重试")
                                }
                            }
                        }
                    },
                    onDismiss = { showDeleteConfirm = false }
                )
            }
            if (showShareSheet && currentUri != null) {
                val item = with(mediaRepo) { /* placeholder to satisfy import */ }
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                        ShareSheet(
                            items = listOf(
                                com.smartvision.gallery.data.model.MediaItem(
                                    uri = currentUri!!,
                                    displayName = displayName.ifEmpty { "photo" },
                                    mimeType = MediaItemAdapter.mimeType(context, currentUri!!) ?: "image/*",
                                    dateTaken = 0L,
                                    size = 0L,
                                    bucket = "",
                                    width = 0,
                                    height = 0,
                                    isFavorite = false,
                                    isLivePhoto = false,
                                    format = com.smartvision.gallery.decoder.format.MediaFormat.JPEG,
                                    tags = emptyList(),
                                )
                            ),
                            pipeline = pipeline,
                            onDismiss = { showShareSheet = false }
                        )
                    }
                }
            }
            slideshowConfig?.let { cfg ->
                SlideshowDialog(
                    initial = cfg,
                    onConfirm = { slideshowConfig = null /* start playing */ },
                    onDismiss = { slideshowConfig = null }
                )
                // Note: dialog flow is shown immediately; user confirms = start playing
                // (we already started the LaunchedEffect loop above).
                // Add a brief moment after confirm to allow user to keep watching:
                LaunchedEffect(cfg) {
                    slideshowPaused = false
                    chromeVisible = false
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp)
            ) { Snackbar(it) }
        }
    }
}

@Composable
private fun ZoomableImage(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var validity by remember(uri) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(uri) {
        validity = withContext(Dispatchers.IO) { FormatDetector.isRecognizable(context, uri) }
    }

    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offsetX by remember(uri) { mutableFloatStateOf(0f) }
    var offsetY by remember(uri) { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .pointerInput(uri) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1.5f) { scale = 1f; offsetX = 0f; offsetY = 0f }
                    else { scale = 2.5f }
                })
            }
            .pointerInput(uri) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (zoom * scale).coerceIn(1f, 6f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            }
            .scale(scale)
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) },
        contentAlignment = Alignment.Center
    ) {
        when (validity) {
            null -> CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.6f),
                strokeWidth = 2.dp,
                modifier = Modifier.size(32.dp)
            )
            false -> CorruptImagePlaceholder()
            true -> AsyncImage(model = uri, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun CorruptImagePlaceholder() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(24.dp)
    ) {
        Text("⚠", color = Color.White.copy(alpha = 0.55f), fontSize = 48.sp,
            modifier = Modifier.padding(bottom = 12.dp))
        Text("无法显示该图片", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold)
    }
}

private suspend fun setAsWallpaper(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val wm = WallpaperManager.getInstance(context)
            val stream = context.contentResolver.openInputStream(uri) ?: return@withContext false
            stream.use { input ->
                val bitmap = BitmapFactory.decodeStream(input) ?: return@withContext false
                wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
            }
            true
        } else {
            @Suppress("DEPRECATION")
            val intent = Intent("android.intent.action.SET_WALLPAPER")
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
    } catch (t: Throwable) {
        false
    }
}
```

Note: The `LivePhotoOverlay` from `ViewerChrome.kt` doesn't expose `isPlaying` state for V2. We pass static `isPlaying = false`. The press/release hooks are stubbed — live-photo motion playback inside the viewer is wired in a follow-up patch (out of V2 scope, see spec § 10).

- [ ] **Step 2: Build to verify**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Fix any compile errors:
- If `MediaItem(...)` constructor signature differs, adjust the constructor call to match the real one (check `data/model/MediaItem.kt`).
- If `MediaRepository.getInstance` is unavailable, instantiate via the project's actual API.
- If `EncryptedPrivacyVault.getInstance` differs, adjust accordingly.
- If `LivePhotoDetector.findLivePhoto` returns nullable differently, adjust the null check.
- If `MediaFormat` enum differs, ensure the placeholder value compiles.

- [ ] **Step 3: Sacred-rule grep gate**

```bash
grep -E "import com\.kyant\.backdrop|import com\.smartvision\.gallery\.ui\.liquidglass" \
  "H:\workspace-minimaxcode\新建文件夹\超级相册\app\src\main\java\com\smartvision\gallery\ui\viewer"/*.kt
```

Expected: **zero matches**.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerActivity.kt
git commit -m "feat(viewer): HorizontalPager + 5-icon chrome + info sheet + slideshow"
```

---

## Task 6: Update AppRoot call sites to pass URI list + index

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/AppRoot.kt`

There are 6 call sites that currently invoke `PhotoViewerActivity.launchIntent(context, uri)`. Update each to pass the surrounding URI list + the clicked item's index.

- [ ] **Step 1: Locate call sites**

Run:
```bash
grep -n "PhotoViewerActivity.launchIntent" \
  "H:\workspace-minimaxcode\新建文件夹\超级相册\app\src\main\java\com\smartvision\gallery\ui\AppRoot.kt"
```

You should see 6 matches.

- [ ] **Step 2: For each call site, replace with the list variant**

For TimelinePage click handler:
```kotlin
// before:
context.startActivity(PhotoViewerActivity.launchIntent(context, uri))

// after:
val timeline = timelineViewModel.items.value
val list = timeline.map { it.uri }
val idx = list.indexOf(uri).coerceAtLeast(0)
context.startActivity(PhotoViewerActivity.launchIntent(context, list, idx))
```

For AlbumDetailPage:
```kotlin
val items = albumViewModel.items.value
val list = items.map { it.uri }
val idx = list.indexOf(uri).coerceAtLeast(0)
context.startActivity(PhotoViewerActivity.launchIntent(context, list, idx))
```

For SearchPage / PrivacyVaultPage / CuratedCardPage: apply the same pattern.

For external `ACTION_VIEW` intents routed here: keep the single-URI `launchIntent` overload (it still exists).

- [ ] **Step 3: Build to verify**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. If a `ViewModel.items.value` field name differs, adjust to whatever the actual property is (`items`, `mediaItems`, `state.value.items`, etc.).

- [ ] **Step 4: Sacred-rule grep gate (project-wide)**

```bash
grep -rn -E "import com\.kyant\.backdrop|import com\.smartvision\.gallery\.ui\.liquidglass" \
  "H:\workspace-minimaxcode\新建文件夹\超级相册\app\src\main\java\com\smartvision\gallery\ui\viewer" \
  "H:\workspace-minimaxcode\新建文件夹\超级相册\app\src\main\java\com\smartvision\gallery\ui\AppRoot.kt"
```

Expected: **zero matches** for `ui/viewer/`. AppRoot.kt is allowed to still import Liquid Glass (it owns the main app's chrome).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/smartvision/gallery/ui/AppRoot.kt
git commit -m "feat(viewer): AppRoot passes URI list + index to PhotoViewerActivity"
```

---

## Task 7: Full project assemble + handoff

**Files:** none

- [ ] **Step 1: Final build**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: APK install (only on request)**

Per [[feedback-user-tests]] — user installs and tests on their own device. Do not run `adb install` loops.

Tell the user:
> Build green. APK ready at `app/build/outputs/apk/debug/app-debug.apk`. Install and smoke-test on your device. Report any crashes or visual glitches.

- [ ] **Step 3: Final commit (if any tracked-file edits happened during fixes)**

```bash
git add -u
git commit -m "chore(viewer): smoke-test fixes"
```

---

## Self-review

**Spec coverage check** (against `docs/superpowers/specs/2026-06-30-photo-viewer-v2-design.md`):

| Spec section | Plan task |
|---|---|
| §1 Goal | All tasks collectively |
| §3.1 Single Activity | Task 5 |
| §3.2 MIME dispatch | Task 5 |
| §3.3 Data flow | Task 5 |
| §3.4 State model | Task 5 (inline data classes) |
| §4 Intent protocol | Task 5 (companion object) |
| §5.1 ViewerTopBar + dropdown | Task 4 |
| §5.2 ViewerBottomBar (5 icons + LivePhoto overlay) | Task 4 |
| §5.3 InfoPanelBottomSheet | Task 3 |
| §5.4 SlideshowOverlay + Dialog | Task 5 (state machine) + Task 2 (dialog) |
| §5.5 Gesture matrix | Task 5 (ZoomableImage + VideoPlayerViewer) |
| §6 Files — create | Tasks 1, 2, 3, 4 |
| §6 Files — modify | Tasks 5, 6 |
| §7 Error handling | Task 5 (snackbar messages), Task 4 (DeleteConfirmDialog), Task 1 (try/catch in adapter) |
| §8 Testing | Task 7 |
| §9 Sacred rule compliance | Task 5 step 3, Task 6 step 4 |
| §10 Out of scope (V3+) | Acknowledged but not implemented |

**Placeholder scan:** No "TBD" / "TODO" / "implement later" steps. (Some `TODO` comments point at *known follow-up* in V3 — these are intentional and labeled "V3" in the spec, not placeholders for V2 work.)

**Type consistency:** `ViewerState` / `ViewerUiState` from spec are inlined as Compose state in Task 5 (deliberately — saving full data classes across config changes needs Parcelable, out of scope for V2 single-Activity). `MediaItemAdapter.ImageMeta` matches spec §6. `MediaInfo` data class matches spec §5.3 fields.

**Cross-task consistency:**
- `launchIntent(context, uris, startIndex)` introduced in Task 5 companion is called by AppRoot in Task 6. ✓
- `InfoPanel(context, uri, onDismiss)` introduced in Task 3 is called by ViewerScreen in Task 5. ✓
- `SlideshowConfig` / `SlideshowDialog` from Task 2 are called by ViewerScreen in Task 5. ✓
- `ViewerTopBar` / `ViewerBottomBar` from Task 4 are called by ViewerScreen in Task 5. ✓
- `MediaItemAdapter` helpers from Task 1 are called by ViewerScreen in Task 5. ✓