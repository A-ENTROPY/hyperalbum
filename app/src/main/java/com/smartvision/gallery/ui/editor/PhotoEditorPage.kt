package com.smartvision.gallery.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartvision.gallery.ui.apple.iOSButton
import com.smartvision.gallery.ui.apple.iOSButtonStyle
import com.smartvision.gallery.ui.liquidglass.AdaptiveLiquidGlass
import com.smartvision.gallery.ui.liquidglass.LiquidGlassBar
import com.smartvision.gallery.ui.liquidglass.LiquidGlassChip
import com.smartvision.gallery.ui.liquidglass.LiquidGlassSpec
import com.smartvision.gallery.ui.liquidglass.LiquidGlassSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iOS Photos edit screen — full-bleed preview with Liquid Glass controls at the
 * bottom. Filters as horizontal pills; adjust sliders inside a single Liquid Glass
 * panel; cancel / save in the top bar.
 */
@Composable
fun PhotoEditorPage(
    uri: Uri,
    onBack: () -> Unit,
    onSave: (Uri) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(0f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var filter by remember { mutableStateOf(FilterPreset.None) }

    LaunchedEffect(uri) {
        val bmp = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
        sourceBitmap = bmp
        currentBitmap = bmp
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        currentBitmap?.let { bmp ->
            val imgBitmap = remember(bmp) { bmp.asImageBitmap() }
            androidx.compose.foundation.Image(
                bitmap = imgBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentScale = ContentScale.Fit
            )
        }

        // Top chrome
        LiquidGlassBar(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "取消", tint = Color.White)
                }
                Text(
                    "编辑",
                    color = Color.White,
                    fontSize = 17.sp,
                    modifier = Modifier.weight(1f),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                IconButton(onClick = {
                    scope.launch {
                        val out = currentBitmap ?: return@launch
                        val savedUri = saveBitmap(context, out)
                        if (savedUri != null) onSave(savedUri)
                    }
                }) {
                    Icon(Icons.Outlined.Check, contentDescription = "保存", tint = Color.White)
                }
            }
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Filter chip row
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(FilterPreset.entries) { preset ->
                    LiquidGlassChip(
                        spec = if (filter == preset) {
                            LiquidGlassSpec.VibrantPlus.copy(
                                tintAlpha = 0.65f,
                                highlightAlpha = 1.0f
                            )
                        } else {
                            LiquidGlassSpec.VibrantPlus.copy(tintAlpha = 0.20f)
                        },
                        onClick = {
                            filter = preset
                            currentBitmap = sourceBitmap?.let { applyFilter(it, preset) }
                        }
                    ) {
                        Text(
                            preset.displayName,
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Sliders in liquid glass panel
            AdaptiveLiquidGlass(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SliderRow("亮度", brightness, -100f..100f) {
                        brightness = it
                        currentBitmap = sourceBitmap?.let { src -> applyAdjust(src, brightness, contrast, saturation) }
                    }
                    SliderRow("对比度", contrast, -100f..100f) {
                        contrast = it
                        currentBitmap = sourceBitmap?.let { src -> applyAdjust(src, brightness, contrast, saturation) }
                    }
                    SliderRow("饱和度", saturation, 0f..2f) {
                        saturation = it
                        currentBitmap = sourceBitmap?.let { src -> applyAdjust(src, brightness, contrast, saturation) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(onClick = {
                            rotation -= 90f
                            currentBitmap = sourceBitmap?.let { rotateBitmap(it, rotation) }
                        }) { Icon(Icons.Outlined.Rotate90DegreesCcw, "左旋", tint = Color.White) }
                        IconButton(onClick = {
                            rotation += 90f
                            currentBitmap = sourceBitmap?.let { rotateBitmap(it, rotation) }
                        }) { Icon(Icons.Outlined.Rotate90DegreesCw, "右旋", tint = Color.White) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.size(width = 64.dp, height = 24.dp)
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            )
        )
    }
}

enum class FilterPreset(val displayName: String) {
    None("原图"),
    Mono("黑白"),
    Sepia("复古"),
    Cool("冷色"),
    Warm("暖色")
}

private fun applyFilter(src: Bitmap, preset: FilterPreset): Bitmap {
    val w = src.width
    val h = src.height
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)
    for (i in pixels.indices) {
        val c = pixels[i]
        val a = (c shr 24) and 0xFF
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val (nr, ng, nb) = when (preset) {
            FilterPreset.None -> Triple(r, g, b)
            FilterPreset.Mono -> {
                val avg = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
                Triple(avg, avg, avg)
            }
            FilterPreset.Sepia -> {
                val nr = (0.393 * r + 0.769 * g + 0.189 * b).toInt().coerceIn(0, 255)
                val ng = (0.349 * r + 0.686 * g + 0.168 * b).toInt().coerceIn(0, 255)
                val nb = (0.272 * r + 0.534 * g + 0.131 * b).toInt().coerceIn(0, 255)
                Triple(nr, ng, nb)
            }
            FilterPreset.Cool -> Triple(
                (r * 0.9f).toInt().coerceIn(0, 255),
                g,
                (b * 1.15f).toInt().coerceIn(0, 255)
            )
            FilterPreset.Warm -> Triple(
                (r * 1.15f).toInt().coerceIn(0, 255),
                (g * 1.05f).toInt().coerceIn(0, 255),
                (b * 0.85f).toInt().coerceIn(0, 255)
            )
        }
        pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}

private fun applyAdjust(src: Bitmap, brightness: Float, contrast: Float, saturation: Float): Bitmap {
    val w = src.width
    val h = src.height
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)
    val c = (contrast + 100f) / 100f
    val b = brightness
    val s = saturation
    for (i in pixels.indices) {
        val argb = pixels[i]
        val a = (argb shr 24) and 0xFF
        var r = ((argb shr 16) and 0xFF).toFloat()
        var g = ((argb shr 8) and 0xFF).toFloat()
        var bl = (argb and 0xFF).toFloat()
        r = (r + b).coerceIn(0f, 255f)
        g = (g + b).coerceIn(0f, 255f)
        bl = (bl + b).coerceIn(0f, 255f)
        r = ((r - 128f) * c + 128f).coerceIn(0f, 255f)
        g = ((g - 128f) * c + 128f).coerceIn(0f, 255f)
        bl = ((bl - 128f) * c + 128f).coerceIn(0f, 255f)
        if (s != 1f) {
            val avg = (0.299f * r + 0.587f * g + 0.114f * bl)
            r = (avg + (r - avg) * s).coerceIn(0f, 255f)
            g = (avg + (g - avg) * s).coerceIn(0f, 255f)
            bl = (avg + (bl - avg) * s).coerceIn(0f, 255f)
        }
        pixels[i] = (a shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or bl.toInt()
    }
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}

private fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
    if (degrees % 360f == 0f) return src
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}

private suspend fun saveBitmap(context: android.content.Context, bitmap: Bitmap): Uri? =
    withContext(Dispatchers.IO) {
        val file = java.io.File(context.cacheDir, "edited_${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }