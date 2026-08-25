package com.smartvision.gallery.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun GridSelectionBadge(
    isSelected: Boolean,
    visible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.5f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
    )
    Box(
        modifier = modifier
            .size(22.dp)
            .padding(4.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                if (isSelected) Color(0xFF007AFF)
                else Color.White.copy(alpha = 0.5f)
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "已选中",
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}