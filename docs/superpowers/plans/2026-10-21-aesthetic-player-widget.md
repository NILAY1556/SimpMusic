# Aesthetic Player Widget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a modern, aesthetic, and responsive Now-Playing widget (`AestheticPlayerWidget`) using Jetpack Glance 1.2.0 combining Apple Music's frosted glass backdrop and clean typography with Samsung One UI's squircle artwork, thin seek progress bar with timestamps, and full tactile transport controls (`[Heart Like] [Prev] [Play/Pause Disc] [Next]`).

**Architecture:** A responsive `GlanceAppWidget` registered as `AestheticPlayerWidget` in `androidApp` utilizing `SizeMode.Responsive` across two breakpoint sizes (`COMPACT = 200x60dp`, `EXPANDED = 260x120dp`). Draws a translucent frosted glass card container (`cornerRadius(24.dp)`) with a subtle 1dp highlight rim, tinted with the current album's palette using `kmpalette`. Collects `SharedViewModel.controllerState` and `nowPlayingScreenData` reactively. Tapping artwork/title launches `MainActivity` directly into Now Playing.

**Tech Stack:** Android, Jetpack Glance 1.2.0, Jetpack Compose, Koin, kmpalette, Coil 3 (with `allowHardware(false)` for RemoteViews IPC safety).

---

## File Structure & Responsibilities

### Presentation & Widget (`androidApp`)
- `androidApp/src/main/java/com/maxrave/simpmusic/ui/widget/AestheticPlayerWidget.kt`: Responsive Glance widget layout implementing Compact (4x1) and Expanded (4x2) views.
- `androidApp/src/main/java/com/maxrave/simpmusic/ui/widget/AestheticPlayerWidgetReceiver.kt`: BroadcastReceiver extending `GlanceAppWidgetReceiver`.
- `androidApp/src/main/res/xml/aesthetic_player_widget_info.xml`: AppWidgetProvider metadata (dimensions, resize mode, preview).
- `androidApp/src/main/res/drawable/widget_glass_card.xml`: Translucent frosted glass background shape with rounded corners (24dp) and subtle 1dp stroke.
- `androidApp/src/main/res/drawable/widget_progress_track.xml`: Background track for the linear progress bar.
- `androidApp/src/main/res/drawable/widget_play_disc_accent.xml`: Circular elevated play/pause button disc.
- `androidApp/src/main/res/values/strings.xml`: User-facing widget labels and descriptions.
- `androidApp/src/main/AndroidManifest.xml`: Registration of `AestheticPlayerWidgetReceiver`.

---

## Task 1: Widget Provider XML, Drawables & Manifest Registration

**Files:**
- Create: `androidApp/src/main/res/xml/aesthetic_player_widget_info.xml`
- Create: `androidApp/src/main/res/drawable/widget_glass_card.xml`
- Create: `androidApp/src/main/res/drawable/widget_play_disc_accent.xml`
- Create: `androidApp/src/main/java/com/maxrave/simpmusic/ui/widget/AestheticPlayerWidgetReceiver.kt`
- Modify: `androidApp/src/main/res/values/strings.xml`
- Modify: `androidApp/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add strings to `androidApp/src/main/res/values/strings.xml`**

```xml
    <string name="widget_aesthetic_player_label">Aesthetic Player</string>
    <string name="widget_aesthetic_player_description">Clean, modern music player with album artwork, progress bar, and playback controls.</string>
```

- [ ] **Step 2: Create `androidApp/src/main/res/xml/aesthetic_player_widget_info.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/widget_aesthetic_player_description"
    android:previewImage="@drawable/widget_preview_img_turntable"
    android:resizeMode="horizontal|vertical"
    android:targetCellWidth="4"
    android:targetCellHeight="2"
    android:minWidth="180dp"
    android:minHeight="56dp"
    android:minResizeWidth="180dp"
    android:minResizeHeight="56dp"
    android:updatePeriodMillis="0"
    android:widgetCategory="home_screen" />
```

- [ ] **Step 3: Create drawables for frosted glass card and play disc**

In `androidApp/src/main/res/drawable/widget_glass_card.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#731C1C1E" />
    <corners android:radius="24dp" />
    <stroke android:width="1dp" android:color="#26FFFFFF" />
</shape>
```

In `androidApp/src/main/res/drawable/widget_play_disc_accent.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="#FFFFFFFF" />
    <stroke android:width="1dp" android:color="#33000000" />
</shape>
```

- [ ] **Step 4: Create `AestheticPlayerWidgetReceiver.kt`**

In `androidApp/src/main/java/com/maxrave/simpmusic/ui/widget/AestheticPlayerWidgetReceiver.kt`:
```kotlin
package com.maxrave.simpmusic.ui.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class AestheticPlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AestheticPlayerWidget()
}
```

- [ ] **Step 5: Register receiver in `androidApp/src/main/AndroidManifest.xml`**

Inside `<application>` tag:
```xml
        <receiver android:name=".ui.widget.AestheticPlayerWidgetReceiver"
            android:label="@string/widget_aesthetic_player_label"
            android:exported="true">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/aesthetic_player_widget_info" />
        </receiver>
```

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/res/xml/aesthetic_player_widget_info.xml
git add androidApp/src/main/res/drawable/widget_glass_card.xml
git add androidApp/src/main/res/drawable/widget_play_disc_accent.xml
git add androidApp/src/main/java/com/maxrave/simpmusic/ui/widget/AestheticPlayerWidgetReceiver.kt
git add androidApp/src/main/res/values/strings.xml
git add androidApp/src/main/AndroidManifest.xml
git commit -m "feat(widget): register AestheticPlayerWidget receiver and XML provider metadata"
```

---

## Task 2: Responsive Aesthetic Player Widget Layouts (Compact & Expanded)

**Files:**
- Create: `androidApp/src/main/java/com/maxrave/simpmusic/ui/widget/AestheticPlayerWidget.kt`

- [ ] **Step 1: Create `AestheticPlayerWidget.kt` with state observation**

In `androidApp/src/main/java/com/maxrave/simpmusic/ui/widget/AestheticPlayerWidget.kt`:
- Configure `sizeMode = SizeMode.Responsive(setOf(COMPACT, EXPANDED))`:
  - `COMPACT = DpSize(200.dp, 60.dp)`
  - `EXPANDED = DpSize(260.dp, 120.dp)`
- Inject `SharedViewModel` and `serviceScope`.
- Setup coroutine listeners for `controllerState` and `nowPlayingScreenData` to trigger `updateWidget(context)`.
- Use Coil to load album artwork with `allowHardware(false)` and `placeholder(R.drawable.holder)`.
- Use `kmpalette` (`rememberPaletteState`) to extract dominant tone for the frosted card tint.

- [ ] **Step 2: Implement Compact Layout (4x1)**

When `LocalSize.current.height < 110.dp || LocalSize.current.width < 260.dp`:
- Outer container: `widget_glass_card` background, `padding(10.dp)`, `clickable(actionStartActivity<MainActivity>())`.
- Row:
  - 52dp $\times$ 52dp squircle artwork with `cornerRadius(14.dp)`.
  - Spacer(12.dp).
  - Column (weight 1f):
    - Title: Bold `15.sp`, white, `maxLines = 1`.
    - Artist: Medium `13.sp`, 75% white, `maxLines = 1`.
  - 44dp circular play/pause disc button.

- [ ] **Step 3: Implement Expanded Layout (4x2)**

When expanded:
- Outer container: `widget_glass_card` background, `padding(12.dp)`, `clickable(actionStartActivity<MainActivity>())`.
- Row:
  - 76dp $\times$ 76dp squircle artwork with `cornerRadius(18.dp)`.
  - Spacer(14.dp).
  - Column (weight 1f, vertical alignment Center):
    - Row: Title (`15.sp`, bold) + Artist (`13.sp`, muted).
    - Spacer(6.dp).
    - Progress Bar: LinearProgressIndicator with elapsed and remaining duration timestamps.
    - Spacer(6.dp).
    - Transport Row: `[Heart Like] [Previous] [Play/Pause Disc] [Next]`.

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/maxrave/simpmusic/ui/widget/AestheticPlayerWidget.kt
git commit -m "feat(widget): implement responsive compact and expanded layouts in AestheticPlayerWidget"
```

---

## Task 3: Interactive Controls (Heart Like, Transport, Progress Line) & State Integration

**Files:**
- Modify: `androidApp/src/main/java/com/maxrave/simpmusic/ui/widget/AestheticPlayerWidget.kt`

- [ ] **Step 1: Wire Heart Like Button**

- Read `screenDataState.isLiked`.
- If true: render filled red heart (`R.drawable.baseline_favorite_24`, `#FFD10000`).
- If false: render outline heart (`R.drawable.rounded_favorite_border_24`, white 70%).
- `clickable`: calls `sharedViewModel.like(!screenDataState.isLiked)`.

- [ ] **Step 2: Wire Previous, Play/Pause, and Next**

- Center Play/Pause button uses `widget_play_disc_accent`:
  - Renders `R.drawable.rounded_pause_24` (when `isPlaying`) or `R.drawable.rounded_play_arrow_24`.
  - Disc background tinted with artwork primary color or clean white.
  - `clickable`: calls `sharedViewModel.onUIEvent(UIEvent.PlayPause)`.
- Previous & Next buttons:
  - 36dp touch target with `R.drawable.rounded_skip_previous_24` and `R.drawable.rounded_skip_next_24`.
  - RTL-aware order flip (`if (systemRtl) next() else previous()`).
  - Calls `sharedViewModel.onUIEvent(UIEvent.Previous)` and `UIEvent.Next`.

- [ ] **Step 3: Wire Linear Progress Bar & Timestamps**

- Read `controllerState.currentPosition` and `controllerState.duration`.
- Compute progress float `(current / duration.coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f)`.
- Render `LinearProgressIndicator` (height 3dp, `cornerRadius(2.dp)`).
- Render elapsed time on the left (e.g. `1:24`) and total duration on the right (e.g. `3:45`) in 10sp text.

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/maxrave/simpmusic/ui/widget/AestheticPlayerWidget.kt
git commit -m "feat(widget): connect interactive heart like, transport controls, and progress bar"
```

---

## Task 4: Local Build & On-Device ADB Verification

**Files:**
- Verify: `androidApp/build/outputs/apk/debug/androidApp-armeabi-v7a-debug.apk`

- [ ] **Step 1: Compile Android debug APK**

Run in terminal:
```cmd
gradlew.bat :androidApp:assembleDebug
```
Expected output: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Install to connected phone**

Run in terminal:
```cmd
"C:\Users\nilay\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s 0037c9240207 install -r androidApp\build\outputs\apk\debug\androidApp-armeabi-v7a-debug.apk
```

- [ ] **Step 3: Manual Verification**
1. Long-press home screen $\to$ Widgets $\to$ select "Aesthetic Player".
2. Add as 4x1: verify compact squircle artwork, bold title, and circular play/pause button.
3. Resize to 4x2: verify expanded view with 76dp artwork, progress line with timestamps, heart like button, and transport controls.
4. Tap Play/Pause: verify instant toggle.
5. Tap Like: verify heart turns vibrant red and toggles favorite status.
6. Tap Artwork: verify app opens directly into Now Playing screen.

- [ ] **Step 4: Commit**

```bash
git commit --allow-empty -m "chore(widget): verify AestheticPlayerWidget builds and installs successfully"
```

---

## Self-Review Checklist
1. **Spec Coverage**:
   - Responsive breakpoints (200x60 and 260x120)? Yes (Task 2).
   - Translucent frosted glass card with 24dp corners? Yes (Task 1 & 2).
   - Squircle artwork (52dp compact, 76dp expanded)? Yes (Task 2).
   - Symmetrical Samsung One UI controls (Like, Prev, Play disc, Next)? Yes (Task 3).
   - Thin linear progress bar with elapsed/duration timestamps? Yes (Task 3).
   - RTL auto-mirroring for skip arrows? Yes (Task 3).
   - Local compilation and device installation via ADB? Yes (Task 4).
2. **Placeholder Scan**: No "TBD", "TODO", or missing blocks.
3. **Type Consistency**: Uses exact `SharedViewModel`, `controllerState`, `nowPlayingScreenData`, `UIEvent`, and Glance 1.2 APIs.
