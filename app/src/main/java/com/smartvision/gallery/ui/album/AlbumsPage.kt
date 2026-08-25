package com.smartvision.gallery.ui.album

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.data.model.Album
import com.smartvision.gallery.ui.components.AsyncThumbnail

/**
 * 图集 (Albums) tab — standard Android gallery layout.
 *
 *  * Large bold title "图集" at top.
 *  * 2-column grid of square album tiles below.
 *  * Each tile = cover photo (or folder icon) + album name + item count.
 *  * Sorted by recency so Camera Roll (DCIM/Camera) sits at the top.
 *
 * This is the user-requested replacement for the previous 图集 chip
 * placement in LibraryOverlay's action row. Selected icon for the
 * device-folder icon is bucket-name-aware: Camera → camera icon,
 * Screenshots → screenshot icon, Downloads → download icon, etc.
 *
 * This page is intentionally separate from [AlbumListPage] (精选).
 * That page is the iOS-26-style curated content surface; this page
 * is the standard Android "device folders" surface. They serve different
 * purposes and don't share state.
 */
@Composable
fun AlbumsPage(
    onOpenAlbum: (albumId: String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val app = LocalContext.current.applicationContext as SmartVisionApp
    val vm: AlbumsViewModel = viewModel(factory = AlbumsViewModel.factory(app.mediaRepository))
    val albums by vm.albums.collectAsState()

    // Top bar is set centrally in AppRoot based on currentRoute.
    // (See AppRoot.kt for the centralization rationale — fixes the stale
    // top-bar bug when navigating Timeline → 图集 → Timeline.)

    if (albums.isEmpty()) {
        EmptyState()
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 60.dp, bottom = 140.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items = albums, key = { it.id }) { album ->
                AlbumTile(
                    album = album,
                    onClick = { onOpenAlbum(album.id) }
                )
            }
        }
    }
}

/**
 * One album tile — 1:1 cover + name + count below.
 *
 * Style references: Google Photos (2-col grid, square covers, rounded
 * corners, name+count below) and iOS 26 Photos Albums tab (same shape,
 * same metadata pattern). Cover photo loads from MediaStore via Coil;
 * fallback is a folder icon tinted by [iconForBucket].
 */
@Composable
private fun AlbumTile(album: Album, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        // 1:1 cover
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        )
                    )
                )
        ) {
            if (album.coverUri != null) {
                AsyncThumbnail(
                    model = album.coverUri,
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = iconForBucket(album.name),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Album name (truncate to 1 line)
        Text(
            text = album.name,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.1).sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "${album.itemCount} 项",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "暂无相册",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "拍摄一些照片后会按设备文件夹自动整理",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Pick a meaningful icon for a bucket based on its name. Standard
 * gallery apps differentiate Camera / Screenshots / Downloads so users
 * can find the right folder fast — we mirror that here.
 */
private fun iconForBucket(name: String): ImageVector {
    val lower = name.lowercase()
    return when {
        "camera" in lower || "相机" in lower || "dcim" in lower -> Icons.Outlined.PhotoCamera
        "screenshot" in lower || "截屏" in lower || "screen" in lower -> Icons.Outlined.Screenshot
        "download" in lower || "下载" in lower -> Icons.Outlined.Download
        else -> Icons.Outlined.Image
    }
}