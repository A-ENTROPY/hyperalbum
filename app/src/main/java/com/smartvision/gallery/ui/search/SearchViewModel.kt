package com.smartvision.gallery.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smartvision.gallery.data.model.MediaItem
import com.smartvision.gallery.data.repo.MediaRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class SearchMode { TEXT, FAVORITES, RECENT, LIVE_PHOTOS, LOCATION }

data class LocationGroup(
    val label: String,
    val count: Int,
    val coverUri: android.net.Uri?,
    val items: List<MediaItem>,
)

class SearchViewModel(repository: MediaRepository) : ViewModel() {

    private val repo = repository
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchMode = MutableStateFlow(SearchMode.TEXT)
    val searchMode: StateFlow<SearchMode> = _searchMode.asStateFlow()

    private val _searchModeLabel = MutableStateFlow("")
    val searchModeLabel: StateFlow<String> = _searchModeLabel.asStateFlow()

    /** Location mode 用了不同的卡片布局，单独暴露一个 StateFlow 给 UI 直接订阅。 */
    val locationGroups: StateFlow<List<LocationGroup>> = repo.observeTimeline()
        .map { items -> buildLocationGroups(items) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<MediaItem>> = combine(_searchMode, _query) { mode, q -> mode to q }
        // 防抖：只在用户停手 200ms 后才真正查询；TEXT 模式独立处理因为它对延迟最敏感。
        .debounce { modeAndQuery ->
            val (mode, q) = modeAndQuery
            if (mode == SearchMode.TEXT && q.isNotBlank()) 200L else 0L
        }
        .flatMapLatest { (mode, q) ->
            when (mode) {
                SearchMode.TEXT -> {
                    if (q.isBlank()) flowOf(emptyList())
                    else repo.observeSearch(q)
                }
                SearchMode.FAVORITES -> repo.observeFavorites()
                SearchMode.RECENT -> {
                    val cutoffMs = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                    repo.observeTimeline().map { list -> list.filter { it.dateTakenMs >= cutoffMs } }
                }
                SearchMode.LIVE_PHOTOS -> {
                    // 实况照片是 is_live_photo flag，不是视频格式 — 之前误用 isVideo 会把
                    // 普通 mp4 也当成实况照片。
                    repo.observeTimeline().map { list -> list.filter { it.isLivePhoto } }
                }
                SearchMode.LOCATION -> {
                    // LOCATION 模式结果由 locationGroups 驱动 — 这里直接吐所有有定位的项，
                    // UI 层会再按 group 切片展示。
                    repo.observeTimeline().map { list ->
                        list.filter { it.latitude != null && it.longitude != null }
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(q: String) {
        _searchMode.value = SearchMode.TEXT
        _searchModeLabel.value = ""
        _query.value = q
    }

    fun setMode(mode: SearchMode) {
        _searchMode.value = mode
        _query.value = ""
        _searchModeLabel.value = when (mode) {
            SearchMode.FAVORITES -> "收藏"
            SearchMode.RECENT -> "最近"
            SearchMode.LIVE_PHOTOS -> "实况"
            SearchMode.LOCATION -> "地点"
            else -> ""
        }
    }

    /** 把 searchMode 回退到 TEXT 并清空 query — 用于拦截系统返回手势。 */
    fun resetToIdle() {
        _searchMode.value = SearchMode.TEXT
        _searchModeLabel.value = ""
        _query.value = ""
    }

    fun clearQuery() {
        _query.value = ""
    }

    /**
     * 按 bucketPath 把有定位的项聚合。第三方 app 没有反向地理编码的稳定能力，
     * 这里用 bucketPath（MediaStore 提供的拍摄目录）作为地点代理标签 —
     * 同一目录的相片大多数情况下确实是同一地点 / 同一事件，这是 iOS Photos 也采
     * 用的策略。
     */
    private fun buildLocationGroups(items: List<MediaItem>): List<LocationGroup> {
        val located = items.filter { it.latitude != null && it.longitude != null }
        if (located.isEmpty()) return emptyList()
        return located
            .groupBy { it.bucketPath?.takeIf { p -> p.isNotBlank() } ?: it.bucketName.orEmpty() }
            .filter { (k, _) -> k.isNotBlank() }
            .map { (key, group) ->
                val sorted = group.sortedByDescending { it.dateTakenMs }
                LocationGroup(
                    label = sorted.first().bucketName?.takeIf { it.isNotBlank() } ?: key,
                    count = sorted.size,
                    coverUri = sorted.first().uri,
                    items = sorted,
                )
            }
            .sortedByDescending { it.count }
    }

    companion object {
        fun factory(repository: MediaRepository) = viewModelFactory {
            initializer { SearchViewModel(repository) }
        }
    }
}