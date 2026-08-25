# LAN SMB 跨设备相册设计

## 背景

当前局域网共享功能仅支持 Android↔Android 通过 NanoHTTPD 服务器传输照片，无法访问 Windows 共享文件夹。用户需要跨设备（Windows↔Android）、跨系统访问和浏览照片/视频/动图，体验上接近本地相册而非文件传输工具。

## 范围

### IN（包含）
- 通过 SMB/CIFS 协议访问 Windows 共享文件夹
- 支持凭据认证（用户名+密码），匿名访问作为 fallback（用户主动选择）
- 浏览共享目录中的照片/视频/动图（网格预览）
- 全屏查看照片（支持缩放）
- 视频播放（支持进度拖拽）
- GIF/动图播放
- 文件增删改查（复制到本地、删除网络文件、重命名）
- 手动添加/编辑网络位置（IP + 共享名 + 凭据）
- NetBIOS 名称查询辅助发现局域网中的 SMB 设备
- 缩略图缓存（LRU 磁盘缓存，每主机 500 条目，总上限 2000）
- 递归扫描的取消/进度/增量更新
- 网络状态变化处理（断线重连、错误状态显示）
- 所有 UI 与现有 Liquid Glass / iOS 风格一致

### OUT（不包含）
- 局域网相册不混入本地相册时间线
- AI 分类不扫描局域网文件
- 不提供编辑功能
- 不提供浏览器访问方案
- 不提供端口 445 全子网扫描（Android 不可行）
- 不提供 SMB1 支持（默认 SMB2/3，仅在极旧 NAS 场景手动配置）

## 架构

### 协议层

使用 **jcifs-ng** 纯 Java SMB2/3 客户端库：
- 版本：`eu.agno3.jcifs:jcifs-ng:2.2.2`（需实施前验证 Maven Central 坐标）
- 补充依赖：`org.slf4j:slf4j-nop:2.0.16`（防止 Android 上 slf4j 日志发散）
- 支持凭据认证、匿名访问、目录枚举、随机读写
- 通过 `SmbFile` 进行文件操作，`SmbRandomAccess` 实现视频流式播放
- SMB 方言协商：默认 SMB2/3，不启用 SMB1（安全性考虑）

### 组件分层

```
lan/smb/
├── SmbDevice.kt              # 数据类：网络位置（host, share, domain, credentials）
├── SmbCredentials.kt         # 凭据数据类（用户名/密码，加密存储到 DataStore）
├── SmbShareManager.kt        # SMB 连接管理、目录浏览、文件 CRUD
├── SmbAlbumIndex.kt          # 扫描共享文件夹，缓存媒体文件元数据
├── SmbThumbnailCache.kt      # 缩略图 LRU 磁盘缓存
├── SmbMediaDataSource.kt     # ExoPlayer DataSource 实现（SMB 随机访问）
├── SmbFetcher.kt             # Coil 自定义 Fetcher（SMB → Bitmap）
├── SmbDiscovery.kt           # NetBIOS 名称查询 + 手动添加

ui/lan/
├── SmbHostList.kt            # 网络位置列表页（LiquidGlassCard）
├── AddSmbHostDialog.kt       # 添加网络位置对话框（IP + 共享名 + 凭据）
├── SmbMediaGrid.kt           # 照片网格页
├── SmbPhotoViewer.kt         # 全屏查看器

LanSharePage.kt               # 主页面（重构：本机服务器 + 网络位置 + 已发现设备）
```

### 数据流

#### 缩略图加载（SMB Fetcher）
1. Coil 请求图片 → 自定义 `SmbFetcher`（Fetcher.Factory<SmbFile>）
2. 注册方式：`ImageLoader.Builder` → `components { add(SmbFetcherFactory()) }`
3. JPEG 文件：读取文件头前 32KB（SOF 段含宽高 + DHT 含 Huffman 表）即可解码缩略图
4. 非 JPEG 格式（PNG/HEIC/AVIF/GIF）：下载完整文件到本地缓存后再解码
5. 缩略图缓存到磁盘 LRU 缓存（每主机 500 条目，总上限 2000，30 天 TTL）

#### 视频播放（ExoPlayer DataSource）
1. ExoPlayer 请求媒体数据 → `SmbMediaDataSource`（实现 `DataSource` 接口）
2. `open(DataSpec)`：通过 `SmbFile.openRandomAccess("r")` 打开连接，验证读取前 256 字节确认连通性；若失败抛 `IOException` 触发 ExoPlayer 重试
3. `read(buffer, offset, length)`：委托 `SmbRandomAccess.read(byte[], int, int)`，返回实际读取字节数
4. `seek(position)`：`SmbRandomAccess.seek(position)`
5. `close()`：`SmbRandomAccess.close()`
6. 超时配置：connectTimeout=10s, readTimeout=30s
7. 网络中断：`SmbException` → 包装为 `IOException` → ExoPlayer 自动重试（getRetryPolicy）

#### 文件 CRUD
- **浏览**: `SmbFile.listFiles()`，过滤媒体扩展名（jpg/jpeg/png/gif/webp/heic/avif/bmp/mp4/mkv/webm/mov/avi）
- **复制到本地**: SMB InputStream → 本地 FileOutputStream，显示进度
- **删除**: `SmbFile.delete()`，需用户确认
- **重命名**: `SmbFile.renameTo(SmbFile)`

#### 凭据存储
- `SmbCredentials` 通过 `EncryptedSharedPreferences`（AndroidX Security）加密存储
- 仅存储用户名/密码，不存储域名
- 用户可在添加/编辑网络位置时输入凭据
- 可选"记住凭据"开关，默认开启

### 错误处理与网络状态

#### 网络状态变化
- 注册 `ConnectivityManager.NetworkCallback` 监听 WiFi 连接状态
- 断线时：显示横幅"网络连接已断开"，暂停所有 SMB 操作
- 重连时：自动重试失败的操作（指数退避：1s, 2s, 4s, 8s, max 30s）
- 所有 SMB 操作在 Dispatchers.IO 执行，不阻塞 UI

#### 连接失败处理
- 认证失败：显示"凭据错误，请检查用户名和密码"
- 主机不可达：显示"无法连接到 {host}"，提供重试/编辑/删除选项
- 共享不存在：显示"共享文件夹 {share} 不存在"
- 超时：显示"连接超时，请检查网络"

#### 扫描进度与取消
- `SmbAlbumIndex.scan()` 接收 `CoroutineScope` 和 `(progress: ScanProgress) -> Unit` 回调
- `ScanProgress` 包含：`scannedCount`, `foundCount`, `currentPath`, `isComplete`
- 扫描在 `viewModelScope` 的 Job 中执行，离开页面时自动取消
- 增量扫描：首次全量，后续检查文件修改时间戳，仅更新变化部分
- UI 显示扫描进度条或"正在扫描...已发现 X 个文件"

## UI 设计

### 整体风格

所有新 UI 组件遵循现有 Liquid Glass / iOS 风格：
- `LiquidGlassCard` 作为容器
- `iOSListRow` / `iOSListSection` 用于列表
- 毛玻璃效果、圆角、iOS 蓝色强调色 (#007AFF)
- 深色/浅色主题自适应

### 页面结构

#### 添加网络位置对话框
```
┌──────────────────────────────┐
│        添加网络位置            │
│                              │
│  IP 地址 / 主机名             │
│  ┌────────────────────────┐  │
│  │ 192.168.1.100          │  │
│  └────────────────────────┘  │
│                              │
│  共享文件夹名                 │
│  ┌────────────────────────┐  │
│  │ Share                   │  │
│  └────────────────────────┘  │
│                              │
│  用户名（可选，留空=匿名）    │
│  ┌────────────────────────┐  │
│  │                        │  │
│  └────────────────────────┘  │
│                              │
│  密码（可选）                 │
│  ┌────────────────────────┐  │
│  │  ●●●●●●●●              │  │
│  └────────────────────────┘  │
│                              │
│  ☑ 记住凭据                   │
│                              │
│     [取消]     [连接]         │
└──────────────────────────────┘
```

#### LanSharePage（重构）
```
┌─────────────────────────────┐
│  TopBar: "局域网共享"         │
├─────────────────────────────┤
│  ┌─ 本机服务器 ───────────┐  │
│  │  Wi-Fi 图标 + 开关按钮  │  │
│  │  IP:端口 (运行中/未启动) │  │
│  └─────────────────────────┘  │
│                               │
│  ┌─ 网络位置 (SMB) ────────┐  │
│  │  + 添加网络位置          │  │  ← 主入口
│  │  ─────────────────────  │  │
│  │  DESKTOP-PC             │  │
│  │  192.168.1.100          │  │
│  │  │  ├ Share\照片        │  │
│  │  │  ├ Share\视频        │  │
│  │  │  └ Share\动图        │  │
│  │  ─────────────────────  │  │
│  │  NAS                    │  │
│  │  192.168.1.200          │  │
│  │  │  └ PhotoShare        │  │
│  └─────────────────────────┘  │
│                               │
│  ┌─ 发现的设备 ────────────┐  │
│  │  (NetBIOS 查询结果)      │  │
│  │  DESKTOP-PC             │  │  ← 点击快速添加
│  │  NAS-01                 │  │
│  └─────────────────────────┘  │
│                               │
│  ┌─ 已发现 Android 设备 ────┐  │
│  │  (NSD 发现)              │  │
│  │  HUAWEI-P40             │  │
│  │  MI-14-Pro              │  │
│  └─────────────────────────┘  │
└─────────────────────────────┘
```

#### 照片网格（SmbMediaGrid）
```
┌──────────────────────────────┐
│  ← 共享文件夹名               │  ← TopBar
├──────────────────────────────┤
│  正在扫描... 已发现 42 个文件   │  ← 扫描进度（首次加载时）
│                              │
│  [图][图][图] [图][图][图]    │  ← LazyVerticalGrid 3列
│  [图][图][图] [图][图][图]    │
│  [图][图][图] [图][图][图]    │
│                              │
│  ┌────────────────────────┐  │
│  │ 全部(50) 照片(42) 视频(8)│  │  ← iOSSegmentedControl
│  └────────────────────────┘  │  （扫描完成后显示）
│                              │
│  网络不可用                    │  ← 断线时显示横幅
└──────────────────────────────┘
```

#### 全屏查看器（SmbPhotoViewer）
```
┌──────────────────────────────┐
│  ← 关闭    文件名     ···    │  ← TopBar (半透明)
├──────────────────────────────┤
│                              │
│          [图片内容]          │  ← 可缩放 (Telephoto)
│                              │
│                              │
├──────────────────────────────┤
│  [下载]  [删除]  [分享]      │  ← 底部操作栏
└──────────────────────────────┘
```

### 色板与间距

所有颜色/间距沿用现有 Liquid Glass 规范：
- 强调色：`#007AFF`（iOS 蓝）
- 毛玻璃背景：`Color.White.copy(alpha = 0.18f)`
- 圆角：`12.dp`（卡片）、`10.dp`（行）
- 内边距：`16.dp`（水平）
- 卡片间间距：`12.dp`

## 依赖

```toml
# gradle/libs.versions.toml 新增
jcifsng = "2.2.2"
slf4j = "2.0.16"

# gradle/libs.versions.toml 新增 library
jcifsng = { group = "eu.agno3.jcifs", name = "jcifs-ng", version.ref = "jcifsng" }
slf4j-nop = { group = "org.slf4j", name = "slf4j-nop", version.ref = "slf4j" }
```

**注意：实施前需验证**
1. `eu.agno3.jcifs:jcifs-ng:2.2.2` 在 Maven Central 上存在
2. jcifs-ng 的 Android 兼容性（jcifs-ng 主要面向 Java SE，但纯 Java 实现应兼容 Android API 26+）
3. slf4j-nop 正确抑制 jcifs-ng 的日志输出，不产生崩溃

## 实现计划

### 第一阶段：依赖验证与基础 SMB 层
1. 验证 jcifs-ng Maven 坐标 + 添加依赖
2. 创建 `SmbDevice.kt` / `SmbCredentials.kt` 数据类
3. 创建 `SmbShareManager.kt` 连接管理 + 目录浏览 + CRUD
4. 创建 `SmbAlbumIndex.kt` 媒体文件元数据扫描（含取消/进度/增量）
5. 创建 `SmbThumbnailCache.kt` 缩略图缓存（每主机 500，总上限 2000）

### 第二阶段：UI 组件
6. 重构 `LanSharePage.kt` 添加 SMB 区域
7. 创建 `SmbHostList.kt` 网络位置列表
8. 创建 `AddSmbHostDialog.kt` 添加网络位置对话框
9. 创建 `SmbMediaGrid.kt` 照片网格（含扫描进度、分类标签、空状态）
10. 创建 `SmbPhotoViewer.kt` 全屏查看器

### 第三阶段：媒体播放 + 缩略图
11. 创建 `SmbMediaDataSource.kt` ExoPlayer DataSource 实现
12. 创建 `SmbFetcher.kt` Coil 自定义 Fetcher (JPEG 头 32KB + 非 JPEG 全量)
13. 注册 Fetcher 到 AppImageLoaderFactory

### 第四阶段：设备发现 + 网络状态
14. 创建 `SmbDiscovery.kt` NetBIOS 名称查询（UDP 137）
15. 添加 ConnectivityManager 网络状态监听
16. 添加错误处理/重试逻辑

## 安全问题

- 凭据通过 `EncryptedSharedPreferences`（AES-256 GCM）加密存储
- 所有操作在局域网内完成，不经过公网
- 匿名访问仅作为用户主动选择的 fallback
- 删除操作需用户确认
- 不启用 SMB1（已知安全漏洞）
- 凭据默认不导出到备份（allowBackup=false）