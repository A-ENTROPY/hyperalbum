# SmartVision Gallery — Architecture Notes (V1.0)

> 内部架构参考文档。描述分层、模块边界、关键决策和扩展点。

## 1. 设计原则

1. **格式无感（Format-agnostic UI）** — UI 层只跟 `MediaItem` 打交道，不感知具体格式。
2. **可插拔解码器（Pluggable decoders）** — 每个解码器实现 `Decoder` 接口；`MediaLoader` 用策略模式路由。
3. **分层清晰** — UI → ViewModel → Repository → DAO/MediaLoader，不允许跨层调用。
4. **单一数据源** — Room 是唯一持久层；MediaStore 是扫描源。
5. **失败软降级** — 解码失败返回 null，loader 自动路由下一个解码器。

## 2. 关键决策

| 决策 | 原因 |
| --- | --- |
| 选用 Compose + Material 3 | 声明式 UI 迭代快；Material 3 适配暗色/Dynamic Color |
| 自写 MediaLoader，不直接用 Glide/Coil 解码 | Coil 仍负责 UI 渲染；但解码走自己的管线以便统一管理 AVIF/JXL/缓存 |
| 嗅探放解码前 | magic bytes 比扩展名/ MIME 更可靠；节省一次 decode 失败的成本 |
| JNI stub + Kotlin 解耦 | V1.0 暂未实现 libavif/libjxl；Kotlin 侧 API 稳定，native 替换不影响业务 |
| Room KSP 而非 KAPT | KSP 编译速度快 2-3 倍 |

## 3. 数据流（典型：用户打开大图）

```
用户点击 TimelineCell
        ↓ onOpenPhoto(uri)
NavController.navigate("viewer/{uri}")
        ↓
PhotoViewerPage 拿到 uri
        ↓
PhotoViewerViewModel.loadFromUri(uri)
        ↓
MediaRepository.observeTimeline()  返回 Flow<List<MediaItem>>
        ↓ Room observe
        ↓
定位 uri 对应的 MediaItem
        ↓
AsyncImage(model = item.uri)  →  Coil →  MediaFetcherFactory
        ↓ 命中 AVIF/JXL 时
MediaLoader.loadThumbnail(item, w, h)
        ↓ 缓存检查
        ↓ miss → AvifNativeDecoder / JxlNativeDecoder
        ↓ NativeBridge.decodeAvifThumbnail(uri, w, h)
        ↓
libsmartvision_decoder.so → Bitmap
        ↓ 回写缓存 + 返回
Coil 渲染到 ImageView
```

## 4. 扩展点（如何添加新格式）

假设要支持 **HEIF 序列图（animated HEIF）**：

1. 在 `MediaFormat` 枚举新增 `HEIF_ANIMATED(...)`
2. `FormatDetector.detectFromBytes` 扩展 'heifs' 品牌嗅探
3. 新建 `decoder/image/HeifAnimatedDecoder.kt`，实现 `Decoder` 接口
4. `MediaLoader.decoderFor(format)` 增加分支
5. `MediaFetcherFactory.NEEDS_OURS` 添加（如系统不支持）
6. 单元测试覆盖

## 5. 性能调优点

- **冷启动**：当前未启用 Baseline Profile；待 V1.x 加 startup-profiler 调优
- **滚动 60fps**：Timeline 用 LazyVerticalGrid + 缩略图 LRU；BitmapPool 复用
- **AVIF 解码 < 200ms**：依赖 native 实现的 libavif；V1.0 stub 返回占位图
- **进程优先级**：前台扫描用 `MediaScanService` + foregroundServiceType="dataSync"

## 6. 安全

- 所有 prefs 存在 DataStore（不进 SharedPreferences，避免被外部读取）
- 隐私空间 V1.0 是软标记；V1.x 接 Android Keystore + Biometric
- 网络层用 OkHttp + HTTPS（V1.x 启用 CertificatePinner）

## 7. 测试策略

| 层级 | 测试方式 |
| --- | --- |
| 纯函数 / 枚举 | JUnit4 单测（`FormatDetectorTest`、`MediaFormatTest`） |
| ViewModel | MockK + Turbine 测 Flow 行为 |
| Repository | Robolectric + Room in-memory |
| UI / Compose | Compose UI Test（V1.x 补） |
| Native | GTest 跑在 NDK（V1.x 补） |

## 8. 已知技术债

- `MediaRepository.replaceAll` 没有真正的 truncate — V1.0 简化实现
- `PhotoViewerViewModel.loadFromUri` 每次都全量加载 timeline；V1.x 改为 cursor-based
- 大图分页（HorizontalPager）暂未实现
- Compose Preview 在多 Surface 上偶发崩溃

— 2026-04-17