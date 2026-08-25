package com.smartvision.gallery.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.smartvision.gallery.SmartVisionApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 位置权限提示工具 — 当用户进入位置相关功能但未授予 ACCESS_MEDIA_LOCATION 时，
 * 通过 Toast 一次性告知原因及如何开启，不主动弹运行时授权框。
 *
 * 为什么不做运行时权限弹窗：
 * - 用户体验：iOS Photos、Android 相册原生应用都不在打开功能时立即弹授权，
 *   而是在设置面板里让用户主动开启。
 * - ACCESS_MEDIA_LOCATION 是相对生僻的权限，用户看到弹窗也未必理解意义。
 *   让用户在主动遇到"位置不可用"时一次性提示，比冷启动时被授权弹窗打断更友好。
 *
 * 用法：
 * ```
 * if (!LocationPermissionHint.ensureOrHint(context)) return@launch
 * // ... 继续执行位置相关逻辑
 * ```
 */
object LocationPermissionHint {

    // ACCESS_MEDIA_LOCATION 是"特殊权限"，不出现在普通运行时授权流程里。在 Android 14+ /
    // 部分厂商 ROM（如 Oplus）上，它藏在「权限 → 照片和视频 → 位置信息」子项，用户几乎找不到。
    // 因此提示语必须精确到子项路径，并配合 jumpToAppSettings 一键跳转。
    private const val MESSAGE =
        "无法读取照片位置：请在弹出的设置页进入「权限」→「照片和视频」，开启「位置信息 / 允许访问媒体位置」"

    // 在普通线程池里 fire-and-forget：grant-detection 用的是 suspend fun，
    // 直接 await 会让 ensureOrHint 变成 suspend，污染所有调用站点（UI lambda /
    // composable）。supervisor scope 独立于 UI lifecycle，I/O 操作安全。
    private val refillScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 检查 ACCESS_MEDIA_LOCATION 权限。如果没有则 Toast 提示并返回 false。
     *
     * 副作用：一旦发现权限刚刚变为已授权（之前未授权，本次为授权），会自动
     * 触发一次 geo refill —— DB 里历史扫描时写下的 latitude=null 行会被
     * 重新读取 EXIF GPS 并回填。这是修复"先前已扫描、现在才授权"边缘场景的
     * 关键路径，所以集成在这里而不是单独扫一个权限监听服务。
     *
     * @return true 表示已授权可继续；false 表示未授权，Toast 已显示。
     */
    fun ensureOrHint(context: Context): Boolean {
        if (checkGrantedTrackingRefill(context)) return true
        Toast.makeText(context.applicationContext, MESSAGE, Toast.LENGTH_LONG).show()
        return false
    }

    /**
     * 静默检查权限，不弹任何 UI。已授权时顺带触发首次 refill 副作用。
     *
     * 给需要自定义提示 UI 的调用方用（如 PhotoViewer 用 snackbar + 「去设置」动作），
     * 这样"照片无 GPS" vs "无权限"两种情况可以由调用方给出不同文案，而不是无脑跳转。
     *
     * @return true 已授权；false 未授权（调用方自行决定如何提示）。
     */
    fun checkGrantedTrackingRefill(context: Context): Boolean {
        val app = context.applicationContext
        if (!PermissionHelper.hasMediaLocationPermission(context)) return false
        // 已授权：尝试触发 geo refill（仅首次检测到权限时）。
        // AppPrefs 持久化一个 bit：上次检测时是否有权限。下次检测到"上次无 → 本次有"则触发 refill。
        triggerRefillIfFirstTimeGranted(app)
        return true
    }

    /**
     * 跳转到本应用的系统设置详情页（权限总入口）。
     *
     * 为什么用 ACTION_APPLICATION_DETAILS_SETTINGS 而不是直接请求权限：
     * ACCESS_MEDIA_LOCATION 在 Android 14+ 与 Oplus/ColorOS 等 ROM 上是"特殊权限"，
     * requestPermissions 不会弹标准授权框（系统直接静默 deny），用户唯一能开启的地方
     * 就是这个详情页里的「权限 → 照片和视频 → 位置信息」。直达详情页把用户的操作
     * 成本从"自己在系统设置里翻找"降到"点两下"。
     *
     * 从非 Activity Context 启动需要 FLAG_ACTIVITY_NEW_TASK。
     */
    fun jumpToAppSettings(context: Context) {
        val app = context.applicationContext
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", app.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        } catch (t: Throwable) {
            AppLog.w("LocationPermissionHint", "jumpToAppSettings failed", t)
        }
    }

    private fun triggerRefillIfFirstTimeGranted(app: Context) {
        val smartApp = app as? SmartVisionApp ?: return
        refillScope.launch {
            val hadPermission = smartApp.prefs.getMediaLocationGrantedLastCheck()
            if (!hadPermission) {
                smartApp.prefs.setMediaLocationGrantedLastCheck(true)
                smartApp.scanCoordinator.requestGeoRefill()
            }
        }
    }
}