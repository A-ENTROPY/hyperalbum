package com.smartvision.gallery.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing tokens — named distances so chrome geometry stops being a magic number
 * scattered across screens. Add new tokens here when the same value is needed in
 * 2+ places, not before (YAGNI).
 *
 * Vertical clearances below the top of the screen are tuned to:
 *   - 64dp large title bar
 *   - 12dp breathing room (top padding for floating chrome)
 *   - 8dp gap before the next layer
 */
object Spacing {
    /** Bottom clearance for floating panels (selection toolbar) so they clear
     *  the iOS bottom tab bar (80dp bar + 12dp breathing + 4dp safety). */
    val TabBarClearance = 96.dp

    /** Top inset for the segmented control row (library page chrome):
     *  64dp large title + 12dp top padding + 8dp gap. */
    val SegmentedControlTop = 84.dp

    /** Top inset for the action row (选择 / Tune chips) which sits below the
     *  segmented control (~32dp) + 16dp gap. */
    val ActionRowTop = 132.dp
}