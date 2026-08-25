package com.smartvision.gallery.ui.viewer

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.exifinterface.media.ExifInterface
import com.smartvision.gallery.decoder.bridge.NativeBridge
import com.smartvision.gallery.ui.liquidglass.LiquidGlassBottomSheet
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun InfoPanel(
    context: Context,
    uri: Uri,
    onDismiss: () -> Unit,
) {
    var info by remember(uri) { mutableStateOf<MediaInfo?>(null) }
    // 区分「加载中」与「超时/失败」: 两者 info 都是 null, 但超时后
    // 不能永久转 spinner. loading=false 且 info==null → 显示失败提示.
    var loading by remember(uri) { mutableStateOf(true) }

    LaunchedEffect(uri) {
        loading = true
        info = withTimeoutOrNull(1000L) {
            withContext(Dispatchers.IO) { readMediaInfo(context, uri) }
        }
        loading = false
    }

    LiquidGlassBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val current = info
            if (current == null) {
                if (loading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("正在读取信息…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(
                        "读取信息超时或文件不可访问",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(24.dp)
                    )
                }
            } else {
                InfoContent(current, onDismiss)
            }
            Text(
                "（长按文字可复制）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun InfoContent(info: MediaInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Image, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 8.dp))
                Text(
                    info.displayName.ifEmpty { "未命名" },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.clickable(onClick = onDismiss)
                )
            }
            InfoRow("格式", info.formatLabel)
            InfoRow("分辨率", info.dimensions)
            InfoRow("大小", info.sizeLabel)
            InfoRow("拍摄日期", info.dateTaken)
            InfoRow("修改日期", info.dateModified)
            if (info.camera.isNotEmpty()) InfoRow("相机", info.camera)
            if (info.lens.isNotEmpty()) InfoRow("镜头参数", info.lens)
            if (info.gps.isNotEmpty()) InfoRow("坐标", info.gps)
            if (info.address.isNotEmpty()) InfoRow("地址", info.address)
            if (info.latLng != null) {
                val (lat, lng) = info.latLng
                InteractiveMap(
                    lat = lat, lng = lng,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?z=15&q=$lat,$lng"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    },
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("在外部地图中查看", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
            }
            if (info.path.isNotEmpty()) InfoRow("路径", info.path)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
        Text(value.ifEmpty { "—" }, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InteractiveMap(lat: Double, lng: Double, modifier: Modifier = Modifier) {
    val mapRef = remember { mutableStateOf<MapView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            mapRef.value?.apply { onPause(); onDetach() }
        }
    }

    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(AMAP_TILE_SOURCE)
                setMultiTouchControls(true)
                isTilesScaledToDpi = true
                isClickable = true
                // AMap tiles are GCJ-02; EXIF GPS is WGS-84 → convert to avoid ~500m offset.
                val (gLat, gLng) = wgs84ToGcj02(lat, lng)
                val point = GeoPoint(gLat, gLng)
                controller.setZoom(15.0)
                controller.setCenter(point)

                val marker = Marker(this)
                marker.position = point
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                overlays.add(marker)

                // Parent Column has verticalScroll → stop it stealing drag gestures.
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN,
                        android.view.MotionEvent.ACTION_MOVE ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }

                mapRef.value = this
                onResume() // ↼ starts tile downloader
            }
        },
        modifier = modifier
    )
}

/** 高德在线瓦片（无需 key、国内可达、HTTPS）。style=7 为路网底图。 */
private val AMAP_TILE_SOURCE = object : OnlineTileSourceBase(
    "AMap", 3, 19, 256, ".png",
    arrayOf(
        "https://wprd01.is.autonavi.com/appmaptile?",
        "https://wprd02.is.autonavi.com/appmaptile?",
        "https://wprd03.is.autonavi.com/appmaptile?",
        "https://wprd04.is.autonavi.com/appmaptile?",
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + "lang=zh_cn&size=1&scl=1&style=7&x=" +
            MapTileIndex.getX(pMapTileIndex) + "&y=" +
            MapTileIndex.getY(pMapTileIndex) + "&z=" +
            MapTileIndex.getZoom(pMapTileIndex)
}

// ── WGS-84 → GCJ-02 (火星坐标) ───────────────────────────────────────────────
private const val GCJ_A = 6378245.0
private const val GCJ_EE = 0.00669342162296594323

private fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
    if (lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271) return lat to lng
    var dLat = transformLat(lng - 105.0, lat - 35.0)
    var dLng = transformLng(lng - 105.0, lat - 35.0)
    val radLat = lat / 180.0 * Math.PI
    var magic = sin(radLat)
    magic = 1 - GCJ_EE * magic * magic
    val sqrtMagic = sqrt(magic)
    dLat = dLat * 180.0 / (GCJ_A * (1 - GCJ_EE) / (magic * sqrtMagic) * Math.PI)
    dLng = dLng * 180.0 / (GCJ_A / sqrtMagic * cos(radLat) * Math.PI)
    return (lat + dLat) to (lng + dLng)
}

private fun transformLat(x: Double, y: Double): Double {
    var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
    ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
    ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
    ret += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
    return ret
}

private fun transformLng(x: Double, y: Double): Double {
    var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
    ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
    ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
    ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
    return ret
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
    val address: String,
    val latLng: Pair<Double, Double>?,
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
                dimensions = if (w > 0 && h > 0) "${w} × ${h}  ·  ${"%.1f".format(w.toLong() * h.toLong() / 1_000_000.0)} MP" else ""
                // MediaProvider leaves WIDTH/HEIGHT 0 for JXL (does not parse it) —
                // probe the real dims from the header instead. The MIME_TYPE column
                // is also often null/empty for JXL, so fall back to the display-name
                // extension — otherwise the probe never fires and the info panel
                // shows no resolution at all for JXL (AVIF always works because
                // MediaProvider recognises it).
                val isJxl = mimeType.equals("image/jxl", ignoreCase = true) ||
                    displayName.endsWith(".jxl", ignoreCase = true)
                if (dimensions.isEmpty() && isJxl) {
                    val dims = runBlocking { NativeBridge.jxlProbeDims(uri) }
                    AppLog.i("InfoPanel", "JXL probe dims for $uri → ${dims?.toList()}")
                    if (dims != null && dims.size == 2 && dims[0] > 0 && dims[1] > 0) {
                        dimensions = "${dims[0]} × ${dims[1]}  ·  ${"%.1f".format(dims[0].toLong() * dims[1].toLong() / 1_000_000.0)} MP"
                    }
                }
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
    var latLng: Pair<Double, Double>? = null
    // Two-pass EXIF read: ContentResolver first (canonical), then file-path fallback
    // so we don't depend on ACCESS_MEDIA_LOCATION being honored by the OEM ROM.
    // GPS lives in the photo file itself — direct file I/O returns the raw EXIF
    // including the GPS tags that ContentResolver would otherwise redact.
    val resolverExif: ExifInterface? = try {
        val originalUri = com.smartvision.gallery.util.IoUtils.requireOriginalUri(context, uri)
        context.contentResolver.openInputStream(originalUri)?.use { ExifInterface(it) }
    } catch (_: Throwable) { null }
    val fallbackExif: ExifInterface? = if (resolverExif == null) try {
        val meta = com.smartvision.gallery.util.IoUtils.queryForFile(context, uri)
        val path = meta?.absolutePath
        if (path != null && java.io.File(path).canRead()) ExifInterface(path) else null
    } catch (_: Throwable) { null } else null
    val exif = resolverExif ?: fallbackExif
    if (exif != null) {
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
        exposure?.let { ev ->
            val seconds = ev.toDoubleOrNull()
            val formatted = if (seconds != null && seconds > 0) {
                val denom = (1.0 / seconds).toInt().coerceAtLeast(1)
                "1/${denom}s"
            } else {
                "${ev}s"
            }
            parts.add(formatted)
        }
        iso?.let { parts.add("ISO $it") }
        focal?.let { parts.add("$it mm") }
        lens = parts.joinToString(" · ")

        val latLong = FloatArray(2)
        if (exif.getLatLong(latLong)) {
            latLng = latLong[0].toDouble() to latLong[1].toDouble()
            gps = "${"%.5f".format(latLong[0])}, ${"%.5f".format(latLong[1])}"
        }
    }

    // Reverse geocode lat/lng → address (no API key needed, Android built-in)
    // readMediaInfo always runs on IO dispatcher, so Geocoder can block directly
    var address = ""
    if (latLng != null) {
        address = try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addrs = geocoder.getFromLocation(latLng.first, latLng.second, 1)
            if (addrs.isNullOrEmpty()) ""
            else addrs[0].getAddressLine(0) ?: ""
        } catch (e: Exception) {
            ""
        }
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
        address = address,
        latLng = latLng,
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
