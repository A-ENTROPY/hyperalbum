package com.smartvision.gallery.util

/**
 * 前台标志位 — 由 [com.smartvision.gallery.SmartVisionApp] 里已实测可靠触发的
 * ProcessLifecycleOwner observer (onStart/onStop 回调) 维护。
 *
 * 为什么不直接查 `ProcessLifecycleOwner.get().lifecycle.currentState`:
 * AiTaggingWorker 在线程池里读 currentState 与 activity 派发存在竞态,
 * 冷启动开图时返回值不可靠 (v47 实测: observer 的 onStart 已触发, worker
 * 读 currentState 却未判前台)。用同一个回调写标志位, worker 读标志,
 * 语义确定性, 无派发竞态。
 */
object AppForegroundTracker {

    @Volatile
    var isForeground: Boolean = false
        private set

    fun markForeground() { isForeground = true }
    fun markBackground() { isForeground = false }
}
