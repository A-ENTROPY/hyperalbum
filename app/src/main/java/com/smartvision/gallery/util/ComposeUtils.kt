package com.smartvision.gallery.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow

/**
 * Helpers for collecting a Flow in Compose safely against the host lifecycle.
 */
@Composable
fun <T> Flow<T>.CollectAsEffect(
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    onEach: (T) -> Unit
) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(this, owner, minActiveState) {
        owner.repeatOnLifecycle(minActiveState) {
            collect { onEach(it) }
        }
    }
}

@Composable
fun <T> rememberDerived(initial: T, block: () -> T) =
    remember { derivedStateOf(block) }

/** Wrap a mutable state in remember + a default factory. */
@Composable
fun <T> rememberMutableStateOf(initial: () -> T): MutableState<T> =
    remember { mutableStateOf(initial()) }