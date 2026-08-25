package com.smartvision.gallery.ui.liquidglass

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow

data class TopBarConfig(
    val title: String = "",
    val variant: TopBarVariant = TopBarVariant.LARGE_TITLE,
    val collapsedRatio: Float = 0f,
    val onBack: (() -> Unit)? = null,
    val subtitle: String? = null,
)

/** Controls the visibility and layout variant of the floating top bar pill. */
enum class TopBarVariant {
    /** Pill is not rendered. */
    HIDDEN,
    /** Large title only — no collapse animation. */
    LARGE_TITLE,
    /** Large title with collapse animation driven by scroll position. */
    COLLAPSIBLE_TITLE,
    /** Compact navigation bar (back + title + optional actions). */
    COMPACT,
}

/**
 * CompositionLocal carrying the current page's [TopBarConfig] as a
 * [MutableStateFlow]. AppRoot owns one instance and provides it to every
 * page; pages read `LocalTopBarState.current` and assign `topBar.value = ...`
 * to publish their config.
 */
val LocalTopBarState = compositionLocalOf<MutableStateFlow<TopBarConfig>> {
    error("TopBarState not provided")
}
