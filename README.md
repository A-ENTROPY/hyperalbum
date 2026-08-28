# Liquid Gallery

> 全格式智能相册 App — 液态玻璃界面（iOS 26 Liquid Glass 风格），AVIF / JPEG XL 在 Android 端的完整落地。

[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE) [![Android](https://img.shields.io/badge/Android-26%2B-green.svg)](https://developer.android.com/) [![Release](https://img.shields.io/badge/Release-v1.0--smb--fix-orange.svg)](https://github.com/A-ENTROPY/hyperalbum/releases)

Liquid Gallery 是一款以「液态玻璃」为核心视觉语言的 Android 相册应用。它不是一套套壳的主题皮肤，而是从渲染管线到交互手势都围绕折射、透镜、质感而重建的相册体验。同时内置次世代图像解码（AVIF / JPEG XL）、局域网 SMB 共享、端侧 AI 识别与加密隐私空间。

## 特性

### 液态玻璃界面（Liquid Glass）

- **真实像素折射**：相册 chrome（顶栏胶囊、分段控制、悬浮操作栏、查看器工具条）采样下方真实照片像素，经 blur + lens + vibrancy 复合渲染 —— 扫过照片时文字与控件逐像素改变颜色，非纯色填充。
- **透镜（Lens）交互**：长按唤出放大透镜，扫过照片时字体重叠区域实时折射。
- **嵌套 Backdrop 捕获**：每个页面在自己的 capture 子树内构建局部 layer backdrop，避免 ColorOS 自采样递归崩溃，同时实现「局部玻璃」效果（如 SMB 网格内的分段控制器折射真实缩略图）。
- **可调玻璃规格**：内建 GlassConfigPanel，blur 半径 / lens 强度 / 渐晕等参数实时可调。
- **苹果风格组件**：iOS 分段控件、ContinuousCapsule 胶囊、玻璃 chip、液态弹层，全局统一。

### 次世代图像解码

| 格式 | 解码管线 |
| --- | --- |
| **JPEG XL** | libjxl 全核解码；`JxlProgressiveController` 渐进加载；`JxlFullResPrecacher` 走 native 1:1（禁止降采样）；RAW 瓦片管线 |
| **AVIF** | libavif + dav1d 解码；16K 单帧、32K grid（ISOBMFF 解析 + 逐 cell 解码 + 并行加速）；SVRAW 瓦片管线；GPU 硬件分配器 |
| **JPEG / PNG / WebP / HEIC / GIF / TIFF** | 系统解码器 + 动画帧解码 |

- **瓦片化查看器**：超分辨率（16K/32K）图片走 `RawImageRegionDecoder` 瓦片解码，任意放大不 OOM。
- **1:1 缓存管线**：JXL → native 转码 JPEG，AVIF → SVRAW，全分辨率级缓存。
- **两遍解码**：先读 bounds 算 inSampleSize 再真解码，缩略图秒出。

### 局域网 SMB 共享

- **网络位置浏览**：SMB 设备发现（NetBIOS UDP 137 + mDNS 辅助）、主机/分享名/凭据管理。
- **远程相册网格**：直接浏览共享文件夹，缩略图 LRU 磁盘缓存（按主机隔离、TTL 过期）。
- **全屏查看器**：与本地照片一致的液态玻璃体验 —— 顶部/底部真实像素玻璃 bar、letterbox 模糊快照背景、双击放大、单击切换 chrome。
- **下载与删除**：下载写入 MediaStore 公共目录（IS_PENDING 原子可见）；删除带二次确认。
- **ExoPlayer DataSource**：SMB random-access 视频流播。

### 端侧 AI

- **多模型识别**：MobileCLIP（语义）、deepdanbooru / wd_convnext_tagger（动漫标签）、places365（场景）、mobilenet（物体）。
- **推理引擎**：TFLite + GPU delegate + ONNX Runtime，本地运行、数据不出设备。
- **发热/卡顿控制**：降并发 + 冷却 + 前台暂停策略 —— 无感后台打标，宁可慢也不让手机发烫。
- **启发式分类**：HeuristicClassifier 规则引擎补充模型盲区。

### 隐私与安全

- **加密隐私空间**：EncryptedPrivacyVault（KeyStore 派生密钥 + 加密存储），指纹解锁（Biometric）。
- **回收站**：删除进回收站，30 天自动清理。
- **加密凭据**：SMB 主机密码加密保存。

### 其他

- **云同步**：CloudSync 页面（提供方接入框架）。
- **Live Photo**：检测 + 视频播放。
- **HDR**：HdrController + 色彩模式权威仲裁。
- **照片编辑**：PhotoEditor 活动。
- **幻灯片播放**、收藏、隐藏、搜索（Flow debounce）、相册派生、EXIF 信息面板。
- **分享**：格式感知分享面板。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 + kyant/backdrop（液态玻璃）、capsule、lucide 图标 |
| 异步 | Coroutines + Flow |
| 数据库 | Room + KSP |
| 图像加载 | Coil + 自定义 Fetcher 路由 AVIF/JXL |
| 推理 | TFLite（含 GPU delegate）+ ONNX Runtime + ML Kit（人脸 / 图像标注） |
| 网络 | OkHttp + jcifs-ng（SMB）+ NanoHTTPD（本地服务） |
| 原生 | NDK + CMake（C++17）：libjxl、libavif、dav1d、aom、libjpeg-turbo、hwy、brotli |
| 构建 | AGP + Gradle（版本目录） |

## 架构

```
┌──────────────────────────────────────────────────────┐
│                    UI (Compose)                       │
│  AppRoot (LiquidGlass capture 树 + Z=0/1/2 chrome)   │
│  Timeline  Albums  Search  Settings  Trash  LAN 云   │
└───────────────────────┬──────────────────────────────┘
                        │
┌───────────────────────▼──────────────────────────────┐
│                  MediaLoader / 解码路由                │
│  SystemImageDecoder │ JxlNativeDecoder │ AvifNative  │
│  RawImageDecoder(瓦片) │ AnimatedFrame │ VideoFrame   │
└───────────────────────┬──────────────────────────────┘
                        │
┌───────────────────────▼──────────────────────────────┐
│   CacheCoordinator                                    │
│   内存 LruCache + 磁盘缩略图缓存 + SMB LRU + RAW 缓存  │
└───────────────────────┬──────────────────────────────┘
                        │
┌───────────────┬───────┴────────┬────────────────────┐
│ Room (SQLite) │ MediaStore     │ SMB / 云 / 本地服务  │
│               │ (扫描源)        │ (jcifs-ng / NanoHTTPD)│
└───────────────┴────────────────┴────────────────────┘
```

### 液态玻璃架构要点

- `AppRoot` 持有全局 `liquidBackdrop`（layer capture），chrome 组件作为 capture 子树**外的 sibling** 采样真实屏幕像素。
- capture 子树**内**的组件（如 SMB 网格内的分段控制）改用**嵌套局部 backdrop** 采样真实缩略图，规避 ColorOS RenderNode 自采样递归崩溃。
- backdropOnly twin + 全局 backdrop state 模式：隐藏静态 twin 捕获文字层，透镜/分段控制折射出逐像素变色。

## 构建

```bash
# 需要 Android SDK / NDK / CMake（路径见 local.properties）
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

- Min SDK 26，Target SDK 36
- ABI：arm64-v8a（含全部 native 解码库）
- AI 模型资产（合计 ~1.25GB）超 GitHub 单文件/配额限制，**不随仓库提交**，需按下方「AI 模型资产」放置后再构建。

### AI 模型资产

模型由 `app/src/main/java/com/smartvision/gallery/data/ai/` 从 `assets/` 加载（无运行时下载），缺失则 AI 打标功能不可用，其余功能不受影响。模型清单（均为公开开源模型，各许可见下表）：

| 文件 | 大小 | 用途 | 来源 |
| --- | --- | --- | --- |
| `deepdanbooru.onnx` | ~615MB | 动漫标签 | deepdanbooru 项目（MIT） |
| `wd_convnext_tagger_v3.onnx` | ~377MB | 动漫标签 | SmilingWolf/wd-convnext-tagger-v3 |
| `wd_convnext_tagger_v3-int8.onnx` | ~96MB | 动漫标签（int8） | 由 v3 量化（`scripts/quantize_wd_int8.py`） |
| `mobileclip_s2_image.tflite` | ~69MB | 语义识别 | Apple MobileCLIP-S2（MIT） |
| `places365_resnet50_int8.tflite` | ~93MB | 场景识别 | MIT Places365（MIT） |
| `mobilenet_v2_1.0_224_quant.tflite` | ~4MB | 物体识别 | Google MobileNetV2（Apache-2.0） |

将上述文件放入 `app/src/main/assets/` 后重新构建即可。文件较大，推荐从各模型官方发布页获取（WD-tagger 系列在 HuggingFace `SmilingWolf/`、MobileCLIP 在 `apple/MobileCLIP-S2`、Places365 在 MIT CSAIL、MobileNetV2 在 TF Hub）。

## 目录

```
app/src/main/
├── java/com/smartvision/gallery/
│   ├── ui/           # AppRoot 液态玻璃架构 + 全部页面
│   │   ├── liquidglass/  # 液态玻璃组件库
│   │   ├── lan/          # SMB 网格 / 查看器 / 发现
│   │   ├── viewer/       # 瓦片解码查看器 / JXL / AVIF 管线
│   │   └── ...
│   ├── decoder/      # 统一解码框架 + 格式路由
│   ├── lan/          # SMB 连接 / 索引 / 缩略图缓存
│   ├── ai/           # 推理引擎 + 打标
│   ├── privacy/      # 加密隐私空间
│   ├── cache/        # 多级缓存
│   └── cloud/ livephoto/ hdr/ scanner/
├── cpp/              # native 解码器（libjxl / libavif / libjpeg 封装）
└── assets/           # AI 模型（本地放置，不入库）
```

## 已知问题

- ColorOS 16 上，capture 子树内读取 screen backdrop 会触发 RenderNode 递归崩溃 —— 架构上已用嵌套 backdrop 规避，新增页面须遵循。
- 项目路径含中文时 Gradle 需 `android.overridePathCheck=true`（gradle.properties 已配置）。

## 许可证与致谢

本项目以 **Apache License 2.0** 开源，见 [LICENSE](LICENSE)。

依赖声明：

- **jcifs-ng**（SMB/CIFS 客户端）遵循 **LGPL-2.1**，以 unmodified 二进制形式链接使用；修改该库源码的衍生作品须遵守 LGPL 条款。
- **libjxl**（BSD-3）、**libavif**（BSD-2）、**dav1d**（BSD-2）、**libjpeg-turbo**（BSD-3 + IJG）、**hwy**（Apache-2.0）为 native 解码依赖，随包静态链接，保留各自版权声明。
- AndroidX / Jetpack Compose / Media3 / TFLite / Coil / Telephoto / OkHttp / kotlinx（Apache-2.0）、ONNX Runtime / MobileCLIP / Places365（MIT）、NanoHTTPD（BSD-3）、lucide（ISC）、SLF4J（MIT）。
- AI 模型版权归各自作者，许可见上表；模型仅作端侧推理，不随本仓库分发。
