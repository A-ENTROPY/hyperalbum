package com.smartvision.gallery.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Transient overlay shown when a JXL's source long edge exceeds
 * [JxlProgressiveController.MAX_SUPPORTED_LONG_EDGE_PX] (32K). The image is
 * still shown as a small preview, but the banner explains why it isn't
 * native-resolution. Auto-fades after [durationMillis]; touch passes through.
 */
@Composable
fun TooLargeBanner(
    longEdgePx: Long,
    modifier: Modifier = Modifier,
    durationMillis: Int = 5000,
) {
    val visible = remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(durationMillis.toLong())
        visible.value = false
    }

    Box(modifier = modifier, contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = visible.value,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(500)),
        ) {
            Box(
                modifier = Modifier
                    .padding(12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "原图尺寸过大 (${longEdgePx}px 长边)\n已显示降采样版本",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}