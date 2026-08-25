# PhotoViewerActivity V2 Design Spec

**Date:** 2026-06-30
**Status:** Approved (user)
**Sacred rule binding:** [[feedback-liquid-glass-sacred]] — Activity must remain free of `com.kyant.backdrop.*` and `com.smartvision.gallery.ui.liquidglass.*` imports.

---

## 1. Goal

Bring `PhotoViewerActivity` to feature parity with the bottom-half of mainstream Android gallery viewers (Apple Photos iOS 26, Samsung Gallery, MIUI 14, Huawei Gallery — **NOT Google Photos**). The Activity is already a deliberate composition root that bypasses the main app's Liquid Glass render tree to dodge a realme ColorOS 16 HWUI RenderEffect stack overflow. We add the feature set without re-introducing Liquid Glass primitives.

## 2. Non-Goals (V2)

- Built-in editor (crop/filter/adjust). Edit button routes to system `ACTION_EDIT`.
- AI features (object erase, lift subject, recognize text).
- Albums management inside the viewer.
- Cloud sync.
- Sharing enhancements beyond `ACTION_SEND` + format-aware pipeline already in `ShareSheet.kt`.

## 3. Architecture

### 3.1 Composition root

`PhotoViewerActivity : ComponentActivity` — single composition root, zero Liquid Glass imports. Hosts one `HorizontalPager<Uri>` (or one Pager state-driven Page). State held by `rememberSaveable` so configuration changes preserve the photo list + index.

### 3.2 Why a single Activity (not separate Image + Video)

Caller passes a list of `MediaItem`-shaped objects. The Activity pages each item, and inside each page detects MIME:

- `image/*` → `ZoomableImage` (Coil `AsyncImage` + pinch / double-tap / pan; magic-byte pre-validation via `FormatDetector.isRecognizable`)
- `video/*` → `VideoPlayerViewer` (ExoPlayer; tap-to-toggle-chrome; auto-hide controls)

Single Activity = single back-stack push, single chrome, single gesture model. The user does not perceive a seam when swiping across image / video in the same list.

### 3.3 Data flow

```
Intent extras
  EXTRA_URI_LIST : ArrayList<String>   ← ordered, immutable for this Activity instance
  EXTRA_START_INDEX : Int
  EXTRA_DISPLAY_NAME : String?         ← single-item fallback title
       │
       ▼
PhotoViewerActivity.onCreate
  parse → List<Uri> + initial index
  rememberSaveable { PagerState { uriList, index } }
       │
       ▼
HorizontalPager(state, pageContent = { pageUri -> ViewerPage(pageUri, mime) })
       │
       ▼
ViewerPage delegates to:
  • ZoomableImage(uri, onMagicByteInvalid = CorruptPlaceholder)
  • VideoPlayerViewer(uri)
       │
       ▼
Chrome overlay (Box with alignment):
  • ViewerTopBar (back / counter / overflow menu)
  • ViewerBottomBar (5 icons)
  • InfoPanelBottomSheet (Material3 ModalBottomSheet)
  • SlideshowOverlay (full-screen immersive)
       │
       ▼
Domain operations (MediaRepository, PrivacyVault, ExportPipeline, ShareSheet)
```

### 3.4 State model

```kotlin
// Saved across config changes
data class ViewerState(
    val uriList: List<Uri>,
    val initialIndex: Int,
    val displayName: String,        // fallback for single-item case
)

// Volatile UI state
data class ViewerUiState(
    val currentIndex: Int,
    val chromeVisible: Boolean = true,
    val livePhotoUri: Uri? = null,  // set on detection; null = no live motion
    val slideshow: SlideshowConfig? = null,
    val slideshowPlaying: Boolean = false,
    val isFavorite: Boolean = false,
)
```

## 4. Intent protocol

| Key | Type | Required | Notes |
|---|---|---|---|
| `EXTRA_URI_LIST` | `ArrayList<String>` | one of (LIST, URI) | Caller builds the list (TimelinePage builds full timeline, AlbumDetailPage builds album-scoped, PrivacyVaultPage builds vault-scoped, SearchPage builds search result). External `ACTION_VIEW` from other apps lands here with size = 1. |
| `EXTRA_START_INDEX` | `Int` | when URI_LIST is set | 0-based. Default 0. |
| `EXTRA_URI` | `String` | one of (LIST, URI) | Legacy single-URI path for code paths that have not migrated yet. |
| `EXTRA_DISPLAY_NAME` | `String?` | optional | Title shown in top bar when list size == 1. |

Helper:

```kotlin
fun launchIntent(context: Context, list: List<Uri>, startIndex: Int = 0): Intent
fun launchIntent(context: Context, uri: Uri, displayName: String = ""): Intent  // legacy
```

## 5. Components

### 5.1 ViewerTopBar

`Row` 56 dp tall, half-transparent black background (alpha 0.45). Three slots:

- Left: `IconButton` with `Icons.AutoMirrored.Outlined.ArrowBack`. Calls `finish()`.
- Center: `Text` showing `"<currentIndex+1> / <total>"` when list size > 1, otherwise the display name.
- Right: `IconButton` with `Icons.Outlined.MoreVert` → opens `DropdownMenu`.

DropdownMenu items (Material3 `DropdownMenuItem`):
- 幻灯片播放 (Slideshow)
- 设为壁纸 (Set as wallpaper)
- 隐藏到保险柜 (Hide to vault) — only when the item is not already hidden
- 显示位置 (Show on map) — disabled if no GPS

### 5.2 ViewerBottomBar

`Row` 96 dp tall, half-transparent black background. Five `IconButton`s, equally weighted:

1. **Favorite** — `Icons.Outlined.FavoriteBorder` / `Icons.Filled.Favorite`. Toggles favorite in MediaStore. Toast / haptic feedback.
2. **Share** — `Icons.Outlined.Share`. Opens existing `ShareSheet` composable (already in repo).
3. **Edit** — `Icons.Outlined.Edit`. Launches `Intent.ACTION_EDIT` with the URI. If no app handles it, snackbar "未找到可用的编辑器".
4. **Info** — `Icons.Outlined.Info`. Opens `InfoPanelBottomSheet` (Material3 `ModalBottomSheet`).
5. **Delete** — `Icons.Outlined.Delete`. Opens confirm dialog. On confirm: deletes via `MediaRepository.delete`, advances pager to next (or finishes if last was deleted).

For **Live Photo** items: a single "LIVE 圆按钮" appears above the bar (Material 3 `Box` 56 dp circle, primary color). `LivePhotoPressHold` semantics — `onPress` starts the video, `onRelease` returns to still. Implementation reuses `LivePhotoVideoPlayer` + `LivePhotoBadge`.

### 5.3 InfoPanelBottomSheet

Material3 `ModalBottomSheet` with `skipPartiallyExpanded = true`. Contents (a vertical list of label-value rows):

- **标题** filename + extension icon
- **拍摄日期** EXIF `DateTimeOriginal` (fallback: file modified time)
- **修改日期** file modified time
- **大小** human-readable (KB / MB)
- **格式** e.g. "JPEG (image/jpeg)", "HEIC (image/heic)"
- **分辨率** "4032 × 3024" + "12.2 MP"
- **相机** EXIF Make + Model
- **镜头参数** "ƒ/1.8 · 1/120s · ISO 100 · 24 mm" (only show fields that exist)
- **GPS** "纬度, 经度" + small static map thumbnail (no map widget in V2, just text + link)
- **所属相册** list of album names (read from MediaStore via `MediaRepository.albumsContaining(uri)`)
- **路径** storage path (long-press to copy)

EXIF read via `androidx.exifinterface.media.ExifInterface`. Run on `Dispatchers.IO`. If > 100 ms elapses before data is ready, show a `CircularProgressIndicator` inside the sheet; never block the pager.

### 5.4 SlideshowOverlay

Full-screen `Box` shown when slideshow is active. Hides chrome. Auto-advances `currentIndex` every N seconds. Tap to exit. Configurable interval (3 / 5 / 10 s) and loop on/off. Configurator: a small `AlertDialog` triggered from the dropdown menu — radio buttons for interval, switch for loop.

### 5.5 Gesture matrix

| Gesture | Image page | Video page |
|---|---|---|
| Swipe horizontal | Switch photo (HorizontalPager) | Switch photo |
| Single tap | Toggle chrome | Toggle controls |
| Double tap | Zoom toggle 1× ↔ 2.5× | (no-op while playing) |
| Two-finger pinch | Zoom 1× – 6× | (no-op) |
| Pan (when zoomed) | Free pan | (no-op) |
| Vertical drag at top | (no-op) | (no-op) |
| Press-and-hold LIVE button | Play Live Photo motion | n/a |

Zoom state is held in `rememberSaveable` keyed by `pageUri` so each photo's zoom is independent.

## 6. Files

### Create

- `app/src/main/java/com/smartvision/gallery/ui/viewer/InfoPanel.kt`
  - `InfoPanel(uri: Uri, onDismiss: () -> Unit)` — ModalBottomSheet, EXIF read on IO dispatcher.
- `app/src/main/java/com/smartvision/gallery/ui/viewer/SlideshowConfig.kt`
  - `SlideshowConfig(intervalMs: Long, loop: Boolean)`, `SlideshowDialog` composable.
- `app/src/main/java/com/smartvision/gallery/ui/viewer/ViewerChrome.kt`
  - `ViewerTopBar`, `ViewerBottomBar`, `MoreMenu`, `DeleteConfirmDialog`, `LivePhotoOverlay`.
- `app/src/main/java/com/smartvision/gallery/ui/viewer/MediaItemAdapter.kt`
  - Tiny helper that maps a `Uri` + `ContentResolver` query into a `MediaItem` shell for `ShareSheet`, `MediaRepository.delete`, etc. Avoids re-querying inside each operation.

### Modify

- `app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerActivity.kt`
  - Add `HorizontalPager`, `ViewerChrome` overlay, `InfoPanel` sheet trigger, `Slideshow` state machine, MIME-based page content selection, `LivePhotoOverlay` integration.
  - Keep zero imports from `com.kyant.backdrop.*` and `com.smartvision.gallery.ui.liquidglass.*`.
  - Keep `Modifier.scale` + `Modifier.offset` (NOT `graphicsLayer`) for zoom transform.
  - Preserve `FormatDetector.isRecognizable` magic-byte pre-check.
- `app/src/main/java/com/smartvision/gallery/ui/AppRoot.kt`
  - Replace existing `context.startActivity(PhotoViewerActivity.launchIntent(context, uri))` calls with `context.startActivity(PhotoViewerActivity.launchIntent(context, list, startIndex))`. Build the URI list from the current page's data source.
- No changes to Liquid Glass code paths. No changes to `LiquidGlassLens.kt`, `LibraryOverlay.kt`, `iOSSegmentedControl`, `iOSTabBar`.

## 7. Error handling

| Condition | UX |
|---|---|
| URI returns no MIME (or `*/*`) | Treat as image, attempt `FormatDetector.isRecognizable`; if invalid → `CorruptImagePlaceholder` (already in current Activity). |
| EXIF read fails or times out | Show EXIF section as "—" placeholder, keep the rest of the sheet visible. |
| `ACTION_EDIT` no handler | Snackbar "未找到可用的编辑器" + auto-dismiss. |
| `ACTION_SEND` chooser cancelled | No-op, return to viewer. |
| Delete confirmed but MediaStore delete fails | Snackbar "删除失败，请重试" with retry button. |
| Slideshow triggers on a deleted item | Skip the index, advance to next valid. |
| Live Photo motion file missing | Show the still image only, no LIVE indicator. |

## 8. Testing

Build verification:

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册"
./gradlew :app:assembleDebug
```

Manual smoke (user runs on device):
- [ ] Open a photo from TimelinePage → see chrome, swipe to next 3 photos, swipe back.
- [ ] Tap once → chrome disappears. Tap again → chrome returns.
- [ ] Double-tap → zoom in. Double-tap again → zoom out.
- [ ] Open a Live Photo (if any on device) → LIVE badge visible, press-and-hold → motion plays, release → still.
- [ ] Open a video from TimelinePage → ExoPlayer loads, plays, tap toggles controls.
- [ ] Tap info → sheet opens within ~100 ms with full EXIF.
- [ ] Tap share → chooser appears, share to WeChat / save to Files works.
- [ ] Tap delete → confirm → photo gone, viewer advances or finishes.
- [ ] Open dropdown → tap Slideshow → confirm dialog → slideshow starts, auto-advances every interval, tap exits.
- [ ] Open dropdown → tap "设为壁纸" → system wallpaper picker.
- [ ] Open a vault-hidden photo from vault page → tap more → tap "隐藏到保险柜" — should not appear since already hidden (menu hides the option).

## 9. Sacred rule compliance check

```bash
grep -E "import com\.kyant\.backdrop|import com\.smartvision\.gallery\.ui\.liquidglass" \
  app/src/main/java/com/smartvision/gallery/ui/viewer/*.kt
```

Must return **zero matches** for any new file in `ui/viewer/`.

## 10. Out of scope / Future (V3+)

- Built-in crop / rotate / filter
- AI object erase / lift subject
- Recognize text (OCR)
- HDR / spatial photo playback
- Comment / annotation layer
- Map widget in info panel
- Slide transitions (hero animations across Activity boundary — out without shared element plumbing)
- Picture-in-picture video playback