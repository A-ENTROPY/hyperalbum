package com.smartvision.gallery.ui.glass

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smartvision.gallery.data.glass.BackdropGlassConfig
import com.smartvision.gallery.data.glass.BackgroundGlassConfig
import com.smartvision.gallery.data.glass.ChipFilterGlassConfig
import com.smartvision.gallery.data.glass.ControlGlassConfig
import com.smartvision.gallery.data.glass.GlassConfig
import com.smartvision.gallery.data.glass.GlassConfigRepository
import com.smartvision.gallery.data.glass.HeroFrostGlassConfig
import com.smartvision.gallery.data.glass.LensGlassConfig
import com.smartvision.gallery.data.glass.SearchBarGlassConfig
import com.smartvision.gallery.data.glass.StaticGlassConfig
import com.smartvision.gallery.data.glass.TabBarGlassConfig
import com.smartvision.gallery.data.glass.ToggleGlassConfig
import com.smartvision.gallery.data.glass.TopBarGlassConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Single source of truth for the live [GlassConfig] that every glass
 * surface in the app reads from.
 *
 *  * `config` — hot [StateFlow] backed by the DataStore. The first
 *    emission happens immediately; subsequent writes (from the tuning
 *    panel) propagate to every glass surface in the same frame.
 *  * `setXxx` — write the corresponding sub-spec back to DataStore.
 *  * `reset` — clear all eight keys; the flow re-emits the defaults.
 *
 * One instance is created at the root of [com.smartvision.gallery.ui.AppRoot]
 * and provided via [LocalGlassConfig] so every screen sees the same values.
 */
class GlassConfigViewModel(
    private val repository: GlassConfigRepository,
) : ViewModel() {

    val config: StateFlow<GlassConfig> = repository.observe()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = GlassConfig(),
        )

    fun setTabBar(value: TabBarGlassConfig) {
        viewModelScope.launch { repository.saveTabBar(value) }
    }

    fun setStatic(value: StaticGlassConfig) {
        viewModelScope.launch { repository.saveStatic(value) }
    }

    fun setTopBar(value: TopBarGlassConfig) {
        viewModelScope.launch { repository.saveTopBar(value) }
    }

    fun setControl(value: ControlGlassConfig) {
        viewModelScope.launch { repository.saveControl(value) }
    }

    fun setToggle(value: ToggleGlassConfig) {
        viewModelScope.launch { repository.saveToggle(value) }
    }

    fun setLens(value: LensGlassConfig) {
        viewModelScope.launch { repository.saveLens(value) }
    }

    fun setBackdrop(value: BackdropGlassConfig) {
        viewModelScope.launch { repository.saveBackdrop(value) }
    }

    fun setBackground(value: BackgroundGlassConfig) {
        viewModelScope.launch { repository.saveBackground(value) }
    }

    fun setSearchBar(value: SearchBarGlassConfig) {
        viewModelScope.launch { repository.saveSearchBar(value) }
    }

    fun setChipFilter(value: ChipFilterGlassConfig) {
        viewModelScope.launch { repository.saveChipFilter(value) }
    }

    fun setHeroFrost(value: HeroFrostGlassConfig) {
        viewModelScope.launch { repository.saveHeroFrost(value) }
    }

    fun reset() {
        viewModelScope.launch { repository.resetToDefaults() }
    }

    companion object {
        fun factory(repository: GlassConfigRepository) = viewModelFactory {
            initializer { GlassConfigViewModel(repository) }
        }
    }
}
