package com.smartvision.gallery.ui.privacy

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.fragment.app.FragmentActivity
import coil.compose.AsyncImage
import com.smartvision.gallery.R
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.privacy.EncryptedPrivacyVault
import com.smartvision.gallery.privacy.VaultUiEvent
import com.smartvision.gallery.ui.components.AsyncThumbnail
import com.smartvision.gallery.ui.components.GridSelectionBadge
import com.smartvision.gallery.ui.components.GridSelectionBar
import com.smartvision.gallery.ui.components.rememberGridCellSizePx
import com.smartvision.gallery.ui.components.rememberGridThumbSizePx
import com.smartvision.gallery.ui.gestures.DragSelectGesture
import com.smartvision.gallery.ui.gestures.PinchToZoomGesture
import com.smartvision.gallery.ui.liquidglass.LiquidGlassSurface
import com.smartvision.gallery.ui.liquidglass.LocalTopBarState
import com.smartvision.gallery.ui.liquidglass.TopBarConfig
import com.smartvision.gallery.ui.liquidglass.TopBarVariant
import com.smartvision.gallery.util.AppLog
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Privacy vault landing surface. Tries to use BiometricPrompt + Android Keystore
 * encryption. If the host Activity is not a [FragmentActivity] (it currently is,
 * but we degrade gracefully for safety), we fall back to the soft-marking impl.
 */
@Composable
fun PrivacyVaultPage(
    onBack: () -> Unit,
    onOpenPhoto: (List<Uri>, Int) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as SmartVisionApp
    val items by app.mediaRepository.observeHidden().collectAsState(initial = emptyList())
    var unlocked by remember { mutableStateOf(false) }

    val vault = remember { EncryptedPrivacyVault(app, app.mediaRepository) }
    val activity = context as? FragmentActivity
    val availability = remember(activity) {
        if (activity != null) vault.canAuthenticate(activity) else EncryptedPrivacyVault.BiometricAvailability.Unavailable
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val authCancelledMsg = stringResource(R.string.vault_auth_cancelled)
    val authFailedFmt = stringResource(R.string.vault_auth_error)

    fun triggerUnlock() {
        val a = activity ?: return
        vault.showPrompt(a) { event ->
            when (event) {
                VaultUiEvent.AuthSucceeded -> {
                    unlocked = true
                }
                VaultUiEvent.AuthCancelled -> {
                    AppLog.d("PrivacyVaultPage", "biometric cancelled")
                }
                is VaultUiEvent.AuthFailed -> {
                    AppLog.d("PrivacyVaultPage", "biometric failed: ${event.message}")
                    scope.launch { snackbarHostState.showSnackbar(event.message) }
                }
                is VaultUiEvent.AuthError -> {
                    AppLog.w(
                        "PrivacyVaultPage",
                        "biometric error code=${event.code} ${event.message}"
                    )
                    scope.launch {
                        snackbarHostState.showSnackbar(authFailedFmt.format(event.message))
                    }
                }
                else -> Unit
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        val topBar = LocalTopBarState.current
        LaunchedEffect(Unit) {
            topBar.value = TopBarConfig(
                title = "隐私空间",
                variant = TopBarVariant.LARGE_TITLE,
                onBack = onBack,
            )
        }
        Spacer(Modifier.height(96.dp))

        if (!unlocked) {
            LockPrompt(
                availability = availability,
                onUnlock = { triggerUnlock() },
                onBack = onBack
            )
        } else {
            var selectModeEnabled by rememberSaveable { mutableStateOf(false) }
            var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
            val selectScope = rememberCoroutineScope()

            BackHandler(enabled = selectModeEnabled) {
                selectModeEnabled = false
                selectedIds = emptySet()
            }
            val density = LocalDensity.current
            val gridState = rememberLazyGridState()
            val autoScrollSpeed = remember { mutableStateOf(0f) }
            // 自动滚动：速度 0 时不轮询 (10ms 空转白耗 CPU/电量)。
            LaunchedEffect(autoScrollSpeed.value) {
                val speed = autoScrollSpeed.value
                if (speed == 0f) return@LaunchedEffect
                while (isActive) {
                    gridState.scrollBy(speed * 0.01f)
                    delay(10)
                }
            }
            var gridColumns by rememberSaveable { mutableIntStateOf(3) }
            val cellPx = rememberGridCellSizePx(columnCount = gridColumns)
            val thumbSizePx = rememberGridThumbSizePx(columnCount = gridColumns)

            // 多选模式：GridSelectionBar；非多选：锁定按钮 + 选择入口
            if (selectModeEnabled) {
                GridSelectionBar(
                    selectedCount = selectedIds.size,
                    onCancel = {
                        selectModeEnabled = false
                        selectedIds = emptySet()
                    },
                    onSelectAll = {
                        selectedIds = items.map { it.id }.toSet()
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                selectScope.launch {
                                    val ids = selectedIds
                                    ids.forEach { id ->
                                        items.firstOrNull { it.id == id }?.let { item ->
                                            app.mediaRepository.setHidden(item.uri, false)
                                        }
                                    }
                                    selectedIds = emptySet()
                                    selectModeEnabled = false
                                    scope.launch {
                                        snackbarHostState.showSnackbar("已从隐私空间移出 ${ids.size} 项")
                                    }
                                }
                            }
                        ) {
                            Text("移出隐私空间", color = MaterialTheme.colorScheme.primary)
                        }
                    },
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LiquidGlassSurface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { unlocked = false },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                            Text(
                                stringResource(R.string.vault_relock),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    if (items.isNotEmpty()) {
                        TextButton(onClick = { selectModeEnabled = true }) {
                            Text("选择", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.vault_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Box(modifier = Modifier.weight(1f)) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(gridColumns),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        // 多选模式禁用 scrollable 指针抢占：拖拽快选需独占 move；自动滚动不受影响。
                        userScrollEnabled = !selectModeEnabled,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (selectModeEnabled) Modifier
                                else Modifier.pointerInput(Unit) {
                                    with(PinchToZoomGesture(
                                        defaultColumns = { gridColumns },
                                        onColumnChange = { newCols ->
                                            if (newCols != gridColumns) gridColumns = newCols
                                        },
                                        onGestureEnd = {},
                                    )) { attach() }
                                }
                            )
                            .then(
                                Modifier.pointerInput(items, gridColumns) {
                                    with(DragSelectGesture(
                                        gridState = gridState,
                                        isItemSelected = { idx ->
                                            items.getOrNull(idx)?.id in selectedIds
                                        },
                                        onToggleItem = { idx ->
                                            val item = items.getOrNull(idx)
                                            if (item != null) {
                                                if (item.id in selectedIds) selectedIds = selectedIds - item.id
                                                else selectedIds = selectedIds + item.id
                                            }
                                        },
                                        onRangeSelect = { prevRange, currRange, selectPass ->
                                            selectedIds = DragSelectGesture.selectRange(
                                                currentSelected = selectedIds,
                                                prevRange = prevRange,
                                                currRange = currRange,
                                                selectPass = selectPass,
                                            )
                                        },
                                        autoScrollSpeed = autoScrollSpeed,
                                        isSelectMode = { selectModeEnabled },
                                        onEnterSelectMode = { selectModeEnabled = true },
                                        onTapItem = { idx ->
                                            val item = items.getOrNull(idx)
                                            if (item != null) {
                                                if (selectModeEnabled) {
                                                    selectedIds = if (item.id in selectedIds) selectedIds - item.id
                                                        else selectedIds + item.id
                                                } else {
                                                    val list = items.map { it.uri }
                                                    val openIdx = list.indexOf(item.uri).coerceAtLeast(0)
                                                    onOpenPhoto(list, openIdx)
                                                }
                                            }
                                        },
                                    )) { attach() }
                                }
                            ),
                    ) {
                        items(items, key = { it.id }) { item ->
                            val isSelected = item.id in selectedIds
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(with(density) { cellPx.toDp() })
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        if (isSelected) Color(0x33007AFF)
                                        else MaterialTheme.colorScheme.surface
                                    )
                            ) {
                                AsyncThumbnail(
                                    model = item,
                                    sizePx = thumbSizePx,
                                    contentDescription = item.displayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(if (isSelected) Modifier.alpha(0.6f) else Modifier),
                                )
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(0x33007AFF))
                                    )
                                }
                                GridSelectionBadge(
                                    isSelected = isSelected,
                                    visible = selectModeEnabled,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
    }
}

@Composable
private fun LockPrompt(
    availability: EncryptedPrivacyVault.BiometricAvailability,
    onUnlock: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val isAvailable = availability == EncryptedPrivacyVault.BiometricAvailability.Available
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(horizontal = 28.dp)
        ) {
            // Hero 图标 — 渐变光环 + 大号品牌色指纹图标
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .shadow(
                        elevation = 24.dp,
                        shape = CircleShape,
                        ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    )
                    .clip(CircleShape)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                Color.Transparent,
                            ),
                            radius = 180f,
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp),
                )
            }

            Spacer(Modifier.height(28.dp))

            // 主标题 — 大字号粗体
            Text(
                text = stringResource(R.string.vault_encrypted_title),
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(10.dp))

            // 副标题 — 上下两行说明
            val hintRes = when (availability) {
                EncryptedPrivacyVault.BiometricAvailability.Available -> R.string.vault_unlock_hint_available
                EncryptedPrivacyVault.BiometricAvailability.NotEnrolled -> R.string.vault_unlock_hint_not_enrolled
                EncryptedPrivacyVault.BiometricAvailability.NoHardware -> R.string.vault_unlock_hint_no_hardware
                EncryptedPrivacyVault.BiometricAvailability.Unavailable -> R.string.vault_unlock_hint_unavailable
            }
            Text(
                text = stringResource(hintRes),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            Spacer(Modifier.height(32.dp))

            // 主 CTA — pill 形状品牌色按钮
            if (availability == EncryptedPrivacyVault.BiometricAvailability.NotEnrolled) {
                PillActionButton(
                    label = stringResource(R.string.vault_setup_now),
                    onClick = {
                        val enrollIntent = Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                            putExtra(
                                Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                            )
                        }
                        runCatching { context.startActivity(enrollIntent) }
                            .onFailure { AppLog.w("PrivacyVaultPage", "biometric enroll intent failed", it) }
                    },
                    leadingIcon = Icons.Outlined.Shield,
                )
            } else {
                PillActionButton(
                    label = stringResource(R.string.vault_authenticate),
                    onClick = onUnlock,
                    enabled = isAvailable,
                    leadingIcon = Icons.Filled.Fingerprint,
                )
            }

            Spacer(Modifier.height(20.dp))

            // 安全徽标行 — 极小 + 低饱和度
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "AES-256 · AndroidKeyStore",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/**
 * iOS-style pill-shaped action button. Filled brand color when enabled;
 * muted surface tint when disabled. Used for the unlock CTA on the lock screen.
 */
@Composable
private fun PillActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val container = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val content = if (enabled) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .shadow(
                elevation = if (enabled) 12.dp else 0.dp,
                shape = CircleShape,
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.30f else 0f),
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.40f else 0f),
            )
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
        }
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = content,
        )
    }
}