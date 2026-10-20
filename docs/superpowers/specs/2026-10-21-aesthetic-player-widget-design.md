# Aesthetic Player Widget Design Spec

**Date:** 2026-10-21  
**Status:** Approved  
**Target:** Android (Jetpack Glance 1.2.0)

---

## 1. Executive Summary & Goals

### 1.1 Problem Statement
The current SimpMusic widgets (`TurntableWidget`, `PlaylistsWidget`, `ListeningInsightsWidget`) either focus on niche retro vinyl physics or dense playlist lists. There is no modern, ultra-clean, aesthetic Now-Playing widget that matches the sleek frosted glass design language of Apple Music combined with the tactile UX and progress tracking of Samsung's One UI music player.

### 1.2 Goals
1. **Aesthetic One UI Glass Design**: A modern, minimalistic, beautiful music player widget featuring frosted translucent glass with subtle album artwork tinting and clean squircle geometry.
2. **Responsive Breakpoints (4x1 to 4x2)**: Uses `SizeMode.Responsive` to cleanly adapt between:
   - **Compact (4x1 / 2x2)**: Minimal horizontal pill with 52dp squircle artwork, bold title, artist, and circular play/pause button.
   - **Expanded (4x2 / 4x3)**: Rich player card with 76dp squircle artwork, dual-line metadata, thin seek progress bar with timestamps, and full Samsung One UI-style controls: `[Heart Like] [Previous] [Play/Pause Disc] [Next]`.
3. **Interactive Control & Feedback**:
   - 1-tap Play/Pause toggle with tactile circular button.
   - 1-tap Like / Favorite toggle (heart icon turns vibrant red and syncs to Room database).
   - Track skipping (Previous / Next) with automatic RTL layout support.
   - Tapping artwork or title launches `MainActivity` directly into the Now Playing screen.
4. **Performance & Battery Preservation**:
   - Hardware-disabled bitmap decoding (`allowHardware(false)`) to guarantee RemoteViews compatibility across all Android versions.
   - Event-driven updates triggered only by track, playback state, or like status changes.

---

## 2. Architecture & Glance Configuration

### 2.1 Component Structure
* **`AestheticPlayerWidget.kt`**:
  * Extends `GlanceAppWidget()` and implements `KoinComponent`.
  * Breakpoint definition:
    ```kotlin
    companion object {
        val COMPACT = DpSize(200.dp, 60.dp)
        val EXPANDED = DpSize(260.dp, 120.dp)
    }
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(COMPACT, EXPANDED))
    ```
* **`AestheticPlayerWidgetReceiver.kt`**:
  * Extends `GlanceAppWidgetReceiver()`, overriding `val glanceAppWidget: GlanceAppWidget = AestheticPlayerWidget()`.
* **`AndroidManifest.xml` Registration**:
  * Registers `AestheticPlayerWidgetReceiver` with `@string/widget_aesthetic_player_label` and `appwidget-provider` XML metadata.
* **`appwidget-provider` XML (`res/xml/aesthetic_player_widget_info.xml`)**:
  * Defines initial dimensions, preview image, resize modes (`horizontal|vertical`), and update frequency.

### 2.2 Reactive State Integration
* Injected dependencies:
  * `sharedViewModel: SharedViewModel by inject()`
  * `serviceScope: CoroutineScope by inject(named(Config.SERVICE_SCOPE))`
* State collection in `provideGlance`:
  ```kotlin
  val controllerState by sharedViewModel.controllerState.collectAsState()
  val screenDataState by sharedViewModel.nowPlayingScreenData.collectAsState()
  val isLiked = screenDataState.isLiked
  val isPlaying = controllerState.isPlaying
  ```

---

## 3. Visual Aesthetic, Frosted Glass & Artwork

### 3.1 Frosted Glass Container
* **Geometry**: Outermost card uses `cornerRadius(24.dp)` with a 1dp subtle highlight rim.
* **Ambient Palette Tint**:
  * Uses `kmpalette` (`rememberPaletteState`) on the decoded album artwork bitmap.
  * In Dark theme: Blends `Color(0xFF141414).copy(alpha = 0.55f)` with 45% of the artwork's dominant tone.
  * In Light theme: Blends `Color(0xFFF5F5F5).copy(alpha = 0.60f)` with 40% of the artwork's dominant tone.
  * Allows the user's home screen wallpaper to show through with a soft blur feel.

### 3.2 Squircle Artwork
* **Compact Mode**: 52dp $\times$ 52dp with `cornerRadius(14.dp)`.
* **Expanded Mode**: 76dp $\times$ 76dp with `cornerRadius(18.dp)`.
* Decoded via Coil's `ImageLoader(context)` with `allowHardware(false)` and `placeholder(R.drawable.holder)`.

### 3.3 Typography & Info Hierarchy
* **Track Title**: `TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = ColorProvider(Color.White))` with `maxLines = 1`.
* **Artist Name**: `TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, color = ColorProvider(Color.White.copy(alpha = 0.75f)))` with `maxLines = 1`.

---

## 4. Interactive Controls & Progress Bar Integration

### 4.1 Expanded (4x2) Layout
* **Layout Structure**:
  * Horizontal row with 76dp Artwork on the left.
  * Right column containing:
    1. Title + Artist column.
    2. 3dp Linear progress bar with elapsed (`1:24`) and duration (`3:45`) timestamps in 10sp text.
    3. Symmetrical Samsung One UI transport row:
       - **Heart Button**: 32dp target. Filled red (`#FFD10000`) when liked, outlined 70% white when unliked. Triggers `sharedViewModel.like(!isLiked)`.
       - **Previous Button**: 36dp arrow. Triggers `sharedViewModel.onUIEvent(UIEvent.Previous)`. (Reversed in RTL).
       - **Play/Pause Button**: 44dp circular elevated disc with high-contrast icon. Triggers `sharedViewModel.onUIEvent(UIEvent.PlayPause)`.
       - **Next Button**: 36dp arrow. Triggers `sharedViewModel.onUIEvent(UIEvent.Next)`.

### 4.2 Compact (4x1) Layout
* Squeezed row when launcher height $< 110$dp:
  * Left: 52dp Squircle Artwork.
  * Middle: Title + Artist (flexible weight).
  * Right: 44dp Circular Play/Pause button.
  * Drops the progress line and skip arrows to keep the UI clean, uncluttered, and readable.

### 4.3 Click Actions
* Clicking anywhere on the artwork or metadata area invokes `actionStartActivity<MainActivity>()` to launch the full app into Now Playing.

---

## 5. Edge Cases & Error Handling

1. **Idle / App Boot State**:
   * If no song has been played yet, displays the SimpMusic app logo, Title: *"SimpMusic"*, Subtitle: *"Tap to play music"*.
2. **Long Titles**:
   * Title and Artist both enforce `maxLines = 1` with ellipsis, ensuring controls remain in their fixed positions.
3. **RTL Locales**:
   * Checks `Resources.getSystem().configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL` and flips the order of Previous/Next buttons so playback direction follows layout conventions.
4. **RemoteViews IPC Safety**:
   * Hardware bitmaps are strictly disallowed (`allowHardware(false)`) when loading images for RemoteViews.

---

## 6. Verification Plan

1. **Compile Android App**:
   * Run `gradlew.bat :androidApp:assembleDebug` using the local Android SDK.
2. **Install to Device**:
   * Use ADB to install `androidApp-debug.apk` directly to the connected device (`0037c9240207`).
3. **Manual Verification**:
   * Add the widget as 4x1 $\to$ verify compact squircle + title/artist + play button.
   * Resize to 4x2 $\to$ verify expanded card with artwork, progress line, timestamps, heart toggle, and transport buttons.
   * Tap Play/Pause $\to$ verify immediate playback toggle.
   * Tap Heart $\to$ verify favorite state changes and updates in app database.
   * Tap Artwork $\to$ verify SimpMusic opens directly to Now Playing.
