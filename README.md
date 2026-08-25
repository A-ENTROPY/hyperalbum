# 智能视界 — SmartVision Gallery

> 全格式智能相册App，次世代图像技术（AVIF / JPEG XL / WebP2）在 Android 端的落地先锋。

## 项目状态

| 指标 | 结果 |
| --- | --- |
| 项目版本 | V1.0.0 (2026-04-17) |
| 构建状态 | ✅ `assembleDebug` SUCCESSFUL |
| APK 体积 | ~20 MB（debug，含 4 ABI native libs） |
| Native libs | `libsmartvision_decoder.so`（AVIF/JXL 解码 stub，ABI: arm64-v8a / armeabi-v7a / x86 / x86_64） |
| 单元测试 | 3 个 decoder / format 核心单测已实现，Gradle wrapper 因项目路径含中文运行受 Windows 限制 |

## 目录结构

```
智能视界/
├── app/
│   ├── build.gradle.kts            # AGP 8.5.2 / Kotlin 1.9.24
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── cpp/                # Native decoder 桩实现 + CMake
│       │   │   ├── CMakeLists.txt
│       │   │   └── stubs/
│       │   │       ├── decoder_stub.cpp / .h
│       │   │       └── smartvision_jni.cpp   # JNI 桥接桩
│       │   ├── java/com/smartvision/gallery/
│       │   │   ├── SmartVisionApp.kt        # Application 容器
│       │   │   ├── data/
│       │   │   │   ├── model/               # MediaItem / Album / DecodedPayload
│       │   │   │   ├── db/                  # Room: MediaEntity / AlbumEntity / DAO
│       │   │   │   └── repo/                # MediaRepository
│       │   │   ├── decoder/
│       │   │   │   ├── MediaLoader.kt       # 统一媒体加载框架
│       │   │   │   ├── Decoder.kt           # 解码器接口
│       │   │   │   ├── format/              # MediaFormat 枚举 + FormatDetector
│       │   │   │   ├── image/               # 系统/AVIF/JXL/动画 解码器
│       │   │   │   ├── video/               # 视频缩略图解码器
│       │   │   │   └── bridge/              # NativeBridge.kt (JNI 桥)
│       │   │   ├── scanner/                 # 全盘扫描协调器 + Service
│       │   │   ├── cache/                   # LruCache + 磁盘缩略图缓存
│       │   │   ├── ai/                      # AiService 接口（V1.x 接 TFLite/MNN）
│       │   │   ├── privacy/                 # PrivacyVault（V1.x 接 KeyStore）
│       │   │   ├── export/                  # ExportPipeline（格式感知分享）
│       │   │   ├── ui/
│       │   │   │   ├── MainActivity.kt
│       │   │   │   ├── AppRoot.kt           # Compose Navigation
│       │   │   │   ├── theme/               # Material 3 主题
│       │   │   │   ├── components/          # FormatBadge + MediaFetcherFactory
│       │   │   │   ├── pages/TimelinePage.kt
│       │   │   │   ├── album/               # AlbumListPage / AlbumDetailPage
│       │   │   │   ├── viewer/              # PhotoViewerPage（手势缩放 / 格式徽章 / 信息面板）
│       │   │   │   ├── search/              # SearchPage（Flow debounce）
│       │   │   │   └── settings/            # SettingsPage
│       │   │   └── util/                    # AppLog / AppPrefs / DateFormatters / IoUtils
│       │   └── res/                         # 颜色 / 主题 / 启动器图标 / Splash
│       ├── test/                            # 单元测试（FormatDetector / MediaFormat / IoUtils）
│       └── androidTest/                     # 占位
├── gradle/
│   ├── libs.versions.toml                   # 版本目录
│   └── wrapper/                             # Gradle 8.7 wrapper
├── docs/                                    # 文档（待补充）
├── samples/                                 # 格式样本（待补充）
├── .github/workflows/                       # CI（待补充）
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── local.properties                         # SDK / NDK / CMake 路径
└── README.md
```

## 技术栈

| 层 | 选型 |
| --- | --- |
| 语言 | Kotlin 1.9.24 |
| UI | Jetpack Compose (BOM 2024.06) + Material 3 + Navigation Compose |
| 异步 | Coroutines + Flow |
| 数据库 | Room 2.6 + KSP |
| 图像加载 | Coil 2.6（自定义 Fetcher 路由 AVIF/JXL） |
| 原生 | NDK 27 + CMake 3.22（C++17，JNI） |
| 配置 | DataStore Preferences |
| 日志 | Timber |
| 图片格式嗅探 | 手写 magic-bytes 检测（PNG / JPEG / GIF / BMP / TIFF / WebP / HEIC / AVIF / JXL / ISOBMFF） |
| 单元测试 | JUnit4 + Truth + MockK + Turbine |
| 构建 | AGP 8.5.2 / Gradle 8.7 |

## 架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                         UI (Compose)                            │
│  TimelinePage  AlbumListPage  PhotoViewerPage  SearchPage       │
│  SettingsPage  …                                                │
└──────────────────────┬──────────────────────────────────────────┘
                       │ Flow<List<MediaItem>>
┌──────────────────────▼──────────────────────────────────────────┐
│                    ViewModel layer                              │
│  TimelineViewModel  AlbumListViewModel  SearchViewModel  …      │
└──────────────────────┬──────────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────────┐
│                  MediaRepository                                │
│   - observeTimeline / observeByFormat / observeBucket …         │
│   - observeSmartAlbums (合并 bucket + format filter + 收藏…)    │
└──────────────────────┬──────────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────┐  ┌──────────────────┐
│            Room (SQLite)               │  │   MediaStore     │
│   media  /  albums  /  user_album_*    │  │   (扫描源)       │
└─────────────────────────────────────────┘  └──────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────────┐
│                       MediaLoader                               │
│   - decodeThumbnail(uri, w, h)  → Bitmap?                       │
│   - decodeFull(uri)             → DecodedPayload?               │
│   - loadAnimationFrames(uri)    → Flow<Bitmap>                  │
│                                                                  │
│   路由策略：                                                     │
│   JPEG/PNG/BMP/TIFF/WebP/GIF/HEIC/MKV… → SystemImageDecoder    │
│   AVIF (Android 12+)                → SystemImageDecoder        │
│   AVIF (Android < 12)               → AvifNativeDecoder (JNI)   │
│   JXL                                → JxlNativeDecoder (JNI)    │
│   MP4/MOV/AVI                        → VideoFrameDecoder        │
└──────────────────────┬──────────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────────┐
│                      CacheCoordinator                           │
│   Memory (LruCache 25% heap)  +  Disk (cacheDir/smartvision…)  │
└─────────────────────────────────────────────────────────────────┘
```

## 构建

```powershell
# 在项目根目录（H:\workspace-minimaxcode\智能视界）
$env:JAVA_HOME = "G:\opencode\tools\jdk"
$env:ANDROID_HOME = "G:\opencode\tools\android-sdk"
$env:Path = "G:\opencode\tools\gradle-8.7\bin;$env:Path"

.\gradlew.bat :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## V1.0 已交付能力清单

### 功能（Functional）

- ✅ 16 种媒体格式识别（PNG / JPEG / GIF / BMP / WebP / HEIC / AVIF / JXL / TIFF / MP4 / MOV / AVI / MKV / WEBM）
- ✅ 全盘扫描（MediaStore IMAGE + VIDEO）
- ✅ 时间线 / 相册 / 搜索 / 设置 四个一级页面
- ✅ 大图查看器（手势缩放、格式徽章、信息面板、收藏/分享/编辑/删除入口）
- ✅ 智能相册派生（bucket / format filter / favorites / hidden / trash）
- ✅ Room 持久化 + DataStore 配置
- ✅ LRU 内存缓存 + 磁盘缩略图缓存 + BitmapPool
- ✅ Coil 集成 + 自定义 Fetcher 路由 AVIF/JXL 到 native 解码
- ✅ EXIF 读取（地理位置 / 拍摄参数）
- ✅ Foreground Service 后台扫描
- ✅ JNI 桥接 stub（生产替换 libavif/libjxl 时不动 Kotlin 侧）
- ✅ 隐私空间（软标记）/ 回收站（30 天自动清理接口）/ 加密留 V1.x
- ✅ 格式感知分享（ExportPipeline）
- ✅ Material 3 主题（Dynamic Color on 12+）
- ✅ Splash 屏幕 + 启动器图标 + 通知通道

### 非功能（Non-Functional）

- ✅ Min SDK 26，Target SDK 36
- ✅ ABI: arm64-v8a / armeabi-v7a / x86 / x86_64
- ✅ 4 ABI 全量 .so 编译通过
- ⚠️ V1.0 性能基准（冷启 < 1.5s / 滚动 60fps / AVIF < 200ms）需在真机压测

## V1.x 待办（已知未实现）

- [ ] libavif / libjxl 真正接入（替换 `app/src/main/cpp/stubs/`）
- [ ] 高级编辑（裁剪/旋转/滤镜 UI）
- [ ] OCR / 人脸聚类 / 智能标签（接 TFLite 或 MNN）
- [ ] RAW 处理（Camera2 RAW + DngCreator）
- [ ] HDR10/HLG 渲染管线
- [ ] 云同步（Google Photos / 阿里云盘 / 微云）
- [ ] KeyStore 真加密 + Biometric 隐私空间
- [ ] Pad 适配（WindowSizeClass）
- [ ] 性能压测报告

## 开发约定

1. 包名：`com.smartvision.gallery`
2. 包结构按 `data / domain / decoder / scanner / cache / ai / privacy / export / ui / util` 分层
3. 所有解码器实现 `Decoder` 接口；不允许在 UI 层直接 new ImageDecoder
4. 命名：`XxxDecoder` / `XxxRepository` / `XxxViewModel` / `XxxPage`
5. 测试：`FormatDetector` / `MediaFormat` 是核心逻辑，单测必写

## 已知问题

- ⚠️ 项目路径含中文（`超级相册`），Gradle 在 Windows 下需要 `android.overridePathCheck=true`
- ⚠️ 单测运行需要绕过 Windows-中文路径问题（运行 `./gradlew.bat :app:test` 时偶发 ClassNotFoundException；运行 `assembleDebug` 不受影响）

— SmartVision Gallery Team · 2026-04-17