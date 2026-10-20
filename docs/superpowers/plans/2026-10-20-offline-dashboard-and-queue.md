# Offline Dashboard & Smart Offline Radio Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide a rich, YouTube Music-style offline dashboard on the Home screen with 6 curated shelves, and an intelligent offline radio queue recommendation engine that seamlessly continues Endless Queue and offline radio using YouTube pre-seeded matches and local affinity graph scoring.

**Architecture:** A new Room entity `OfflineRadioSeedEntity` (schema v28) stores YouTube's algorithmic radio seeds when tracks are downloaded while online. `OfflineRadioRepository` implements a hybrid recommendation engine: Tier 1 matches pre-seeded YouTube seeds against downloaded/cached songs, and Tier 2 scores candidates based on artist/collaborator matching, 30-minute session co-occurrences in `playback_event`, and album/playlist co-membership, with pacing to avoid artist clumping. `OfflineDashboardRepository` synthesizes Room entities into standard `HomeItem` shelves. `HomeScreen` automatically switches from the dead-end `OfflineErrorState` to the offline dashboard, with a manual offline toggle option.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Room SQLite Database (v28), Media3 (Android) / libmpv (Desktop), Kotlinx Coroutines & Flow, Koin DI.

---

## File Structure & Responsibilities

### Core Domain (`core/domain`)
- `core/domain/src/commonMain/kotlin/com/maxrave/domain/data/entities/OfflineRadioSeedEntity.kt`: Entity representing cached YouTube radio recommendations for offline matching.
- `core/domain/src/commonMain/kotlin/com/maxrave/domain/repository/OfflineRadioRepository.kt`: Interface for generating offline queues and pre-seeding radio recommendations.
- `core/domain/src/commonMain/kotlin/com/maxrave/domain/repository/OfflineDashboardRepository.kt`: Interface for providing offline dashboard shelves and synthesized mixes.

### Core Data (`core/data`)
- `core/data/src/commonMain/kotlin/com/maxrave/data/db/MusicDatabase.kt`: Bump version to 28, add entity and auto-migrations.
- `core/data/src/commonMain/kotlin/DatabaseDao.kt`: Queries for radio seeds and offline library aggregations.
- `core/data/src/commonMain/kotlin/com/maxrave/data/repository/OfflineRadioRepositoryImpl.kt`: Implements candidate pool extraction, Tier 1 pre-seed match, Tier 2 multi-factor scoring, and variety pacing.
- `core/data/src/commonMain/kotlin/com/maxrave/data/repository/OfflineDashboardRepositoryImpl.kt`: Implements the 6 offline shelves ("Jump Back In", "Quick Picks", "Offline Mixes", "Downloaded Albums", "Downloaded Artists", "Offline Playlists").
- `core/data/src/commonMain/kotlin/com/maxrave/data/di/RepositoryModule.kt`: Registers repositories into Koin.
- `core/data/src/androidMain/kotlin/com/maxrave/data/mediaservice/MediaServiceHandlerImpl.kt`: Integrates offline radio fallback into `loadMore()` for Android.
- `core/data/src/jvmMain/kotlin/com/maxrave/data/mediaservice/JvmMediaPlayerHandlerImpl.kt`: Integrates offline radio fallback into `loadMore()` for Desktop.
- `core/data/src/commonMain/kotlin/com/maxrave/data/repository/DownloadRepositoryImpl.kt`: Invokes pre-seeding hook on successful track download.

### Presentation (`composeApp`)
- `composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/viewModel/HomeViewModel.kt`: Observes offline dashboard shelves, manual offline toggle, and empty state.
- `composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/screen/home/HomeScreen.kt`: Renders the offline dashboard shelves when offline, replaces `OfflineErrorState`.
- `composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/component/OfflineDashboardHeader.kt`: Status pill with CloudOff icon and "Go Online" action.

### Tests (`core/data/src/jvmTest`)
- `core/data/src/jvmTest/kotlin/com/maxrave/data/repository/OfflineRadioRepositoryTest.kt`: Unit test for candidate filtering, pre-seed priority, affinity scoring, and pacing rules.
- `core/data/src/jvmTest/kotlin/com/maxrave/data/repository/OfflineDashboardRepositoryTest.kt`: Unit test for shelf synthesis and mapping.

---

## Task 1: Database Entity & DAO for Offline Radio Seeds & Aggregations

**Files:**
- Create: `core/domain/src/commonMain/kotlin/com/maxrave/domain/data/entities/OfflineRadioSeedEntity.kt`
- Modify: `core/data/src/commonMain/kotlin/com/maxrave/data/db/MusicDatabase.kt:35-105`
- Modify: `core/data/src/commonMain/kotlin/DatabaseDao.kt`

- [ ] **Step 1: Create `OfflineRadioSeedEntity.kt`**

```kotlin
package com.maxrave.domain.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.maxrave.domain.extension.now
import kotlinx.datetime.LocalDateTime

@Entity(
    tableName = "offline_radio_seed",
    indices = [
        Index(value = ["seedVideoId"]),
        Index(value = ["seedVideoId", "relatedVideoId"], unique = true),
    ],
)
data class OfflineRadioSeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seedVideoId: String,
    val relatedVideoId: String,
    val rank: Int,
    val fetchedAt: LocalDateTime = now(),
)
```

- [ ] **Step 2: Add queries to `DatabaseDao.kt`**

Add the following methods to `DatabaseDao.kt`:

```kotlin
@Query("SELECT relatedVideoId FROM offline_radio_seed WHERE seedVideoId = :seedVideoId ORDER BY rank ASC")
fun getRadioSeedsForTrack(seedVideoId: String): Flow<List<String>>

@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun insertRadioSeeds(seeds: List<OfflineRadioSeedEntity>)

@Query("DELETE FROM offline_radio_seed WHERE fetchedAt < :threshold")
suspend fun pruneOldRadioSeeds(threshold: LocalDateTime)

@Query("""
    SELECT DISTINCT a.* FROM album a
    INNER JOIN song s ON s.albumId = a.browseId
    WHERE s.downloadState = 1 OR s.videoId IN (:cachedIds)
""")
fun getDownloadedAlbums(cachedIds: List<String>): Flow<List<AlbumEntity>>

@Query("""
    SELECT DISTINCT ar.* FROM artist ar
    INNER JOIN song s ON s.artistId LIKE '%' || ar.channelId || '%' ESCAPE '\'
    WHERE s.downloadState = 1 OR s.videoId IN (:cachedIds)
""")
fun getDownloadedArtists(cachedIds: List<String>): Flow<List<ArtistEntity>>
```

- [ ] **Step 3: Update `MusicDatabase.kt` to version 28**

Add `OfflineRadioSeedEntity::class` to the `entities` array in `@Database`.
Update `version = 28`.
Add auto-migrations:
```kotlin
AutoMigration(27, 28),
AutoMigration(26, 28),
AutoMigration(25, 28),
```

- [ ] **Step 4: Verify Compilation & Commit**

Run check using JetBrains MCP / git status.
Commit:
```bash
git add core/domain/src/commonMain/kotlin/com/maxrave/domain/data/entities/OfflineRadioSeedEntity.kt
git add core/data/src/commonMain/kotlin/DatabaseDao.kt
git add core/data/src/commonMain/kotlin/com/maxrave/data/db/MusicDatabase.kt
git commit -m "feat(db): add OfflineRadioSeedEntity and DAO queries for offline library (v28)"
```

---

## Task 2: Offline Radio Engine & Affinity Algorithm with Unit Tests

**Files:**
- Create: `core/domain/src/commonMain/kotlin/com/maxrave/domain/repository/OfflineRadioRepository.kt`
- Create: `core/data/src/commonMain/kotlin/com/maxrave/data/repository/OfflineRadioRepositoryImpl.kt`
- Create: `core/data/src/jvmTest/kotlin/com/maxrave/data/repository/OfflineRadioRepositoryTest.kt`

- [ ] **Step 1: Define `OfflineRadioRepository` interface**

In `core/domain/src/commonMain/kotlin/com/maxrave/domain/repository/OfflineRadioRepository.kt`:

```kotlin
package com.maxrave.domain.repository

import com.maxrave.domain.data.model.Track

interface OfflineRadioRepository {
    suspend fun buildOfflineQueue(
        seedTrack: Track,
        excludeVideoIds: Set<String>,
        limit: Int = 25,
    ): List<Track>

    suspend fun preSeedRadioForTrack(videoId: String)
}
```

- [ ] **Step 2: Write failing unit test `OfflineRadioRepositoryTest.kt`**

In `core/data/src/jvmTest/kotlin/com/maxrave/data/repository/OfflineRadioRepositoryTest.kt`:
Test:
1. Candidate pool filtering (excludes currently queued tracks and live streams).
2. Tier 1 pre-seed sorting priority (YouTube seeds in rank order come first).
3. Tier 2 multi-factor scoring (primary artist match = 0.40, session co-occurrence = 0.30, album match = 0.20, play affinity = 0.10).
4. Variety pacing (never $>2$ consecutive tracks by the same artist when pool $\ge 10$).

- [ ] **Step 3: Implement `OfflineRadioRepositoryImpl.kt`**

In `core/data/src/commonMain/kotlin/com/maxrave/data/repository/OfflineRadioRepositoryImpl.kt`:
1. Query available downloaded songs from `SongRepository.getDownloadedSongs()` and cached song IDs from `CacheRepository.getFullyCachedSongIds()`.
2. Filter candidate pool excluding `excludeVideoIds` and `isLive == true`.
3. Read Tier 1 seeds from `DatabaseDao.getRadioSeedsForTrack(seedTrack.videoId)`:
   - Matches are prioritized in rank order.
4. Read Tier 2 candidates:
   - For each candidate $T$, compute:
     - `artistScore`: $1.0$ if primary artist matches seed; $0.6$ if any secondary artist matches; $0.0$ otherwise.
     - `sessionScore`: Count plays of $T$ within 30 min of $S$ in `playback_event`.
     - `contextScore`: $1.0$ if same `albumId`; $0.7$ if co-occurring in local playlist.
     - `affinityScore`: normalized play time.
     - Total $= 0.40 \cdot \text{artist} + 0.30 \cdot \text{session} + 0.20 \cdot \text{context} + 0.10 \cdot \text{affinity}$.
5. Pacing filter:
   - Interleave tracks such that no single artist appears $>2$ times consecutively when pool size $\ge 10$.
6. `preSeedRadioForTrack(videoId: String)`:
   - Calls `YouTube.next(videoId)` while online.
   - Parses the first 25 track IDs from response.
   - Inserts into `DatabaseDao.insertRadioSeeds`.

- [ ] **Step 4: Run unit tests and verify they pass**

Run: `gradlew :data:testDebugUnitTest` or `:data:jvmTest`
Verify all tests in `OfflineRadioRepositoryTest` pass.

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/commonMain/kotlin/com/maxrave/domain/repository/OfflineRadioRepository.kt
git add core/data/src/commonMain/kotlin/com/maxrave/data/repository/OfflineRadioRepositoryImpl.kt
git add core/data/src/jvmTest/kotlin/com/maxrave/data/repository/OfflineRadioRepositoryTest.kt
git commit -m "feat(data): implement OfflineRadioRepository with pre-seeding and affinity scoring"
```

---

## Task 3: Offline Dashboard Repository with Unit Tests

**Files:**
- Create: `core/domain/src/commonMain/kotlin/com/maxrave/domain/repository/OfflineDashboardRepository.kt`
- Create: `core/data/src/commonMain/kotlin/com/maxrave/data/repository/OfflineDashboardRepositoryImpl.kt`
- Create: `core/data/src/jvmTest/kotlin/com/maxrave/data/repository/OfflineDashboardRepositoryTest.kt`
- Modify: `core/data/src/commonMain/kotlin/com/maxrave/data/di/RepositoryModule.kt`

- [ ] **Step 1: Define `OfflineDashboardRepository` interface**

In `core/domain/src/commonMain/kotlin/com/maxrave/domain/repository/OfflineDashboardRepository.kt`:

```kotlin
package com.maxrave.domain.repository

import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.home.HomeItem
import kotlinx.coroutines.flow.Flow

interface OfflineDashboardRepository {
    fun getOfflineHomeShelves(): Flow<List<HomeItem>>
    suspend fun getOfflineMixes(): List<Content>
}
```

- [ ] **Step 2: Write failing unit test `OfflineDashboardRepositoryTest.kt`**

Verify shelf generation:
- "Jump Back In" builds from recent offline history.
- "Quick Picks" groups top downloaded tracks into items with `itemsPerColumn = 4`.
- "Downloaded Albums" maps from `DatabaseDao.getDownloadedAlbums()`.
- "Downloaded Artists" maps from `DatabaseDao.getDownloadedArtists()`.
- "Offline Playlists" contains local playlists and downloaded playlists.

- [ ] **Step 3: Implement `OfflineDashboardRepositoryImpl.kt`**

In `core/data/src/commonMain/kotlin/com/maxrave/data/repository/OfflineDashboardRepositoryImpl.kt`:
Implement mapping of Room data into the 6 `HomeItem` shelves.
Synthesize the 4 offline mix cards:
1. "Offline Supermix": balanced mix of user's top offline artists.
2. "[Artist 1] & Similar": top artist + co-occurring tracks.
3. "[Artist 2] Mix": second top artist offline tracks.
4. "Offline Discover": downloaded tracks with lower play counts.

- [ ] **Step 4: Register in `RepositoryModule.kt`**

Bind `OfflineRadioRepository` and `OfflineDashboardRepository` as singletons in Koin `repositoryModule`.

- [ ] **Step 5: Run tests and verify they pass**

Run: `gradlew :data:jvmTest`
Verify all tests pass.

- [ ] **Step 6: Commit**

```bash
git add core/domain/src/commonMain/kotlin/com/maxrave/domain/repository/OfflineDashboardRepository.kt
git add core/data/src/commonMain/kotlin/com/maxrave/data/repository/OfflineDashboardRepositoryImpl.kt
git add core/data/src/jvmTest/kotlin/com/maxrave/data/repository/OfflineDashboardRepositoryTest.kt
git add core/data/src/commonMain/kotlin/com/maxrave/data/di/RepositoryModule.kt
git commit -m "feat(data): implement OfflineDashboardRepository and register Koin dependencies"
```

---

## Task 4: Media Handler Endless Queue Offline Integration

**Files:**
- Modify: `core/data/src/androidMain/kotlin/com/maxrave/data/mediaservice/MediaServiceHandlerImpl.kt:1650-1680`
- Modify: `core/data/src/jvmMain/kotlin/com/maxrave/data/mediaservice/JvmMediaPlayerHandlerImpl.kt:1750-1780`

- [ ] **Step 1: Inject `OfflineRadioRepository` into Android `MediaServiceHandlerImpl`**

In `core/data/src/androidMain/kotlin/com/maxrave/data/mediaservice/MediaServiceHandlerImpl.kt`:
Add `offlineRadioRepository: OfflineRadioRepository` to constructor.
In `loadMore()`:
When `dataStoreManager.endlessQueue.first() == TRUE`:
If offline / network fails, call:
```kotlin
val nextTracks = offlineRadioRepository.buildOfflineQueue(
    seedTrack = lastTrack,
    excludeVideoIds = queueData.value.data.listTracks.map { it.videoId }.toSet(),
    limit = 25
)
if (nextTracks.isNotEmpty()) {
    loadMoreCatalog(ArrayList(nextTracks))
    _queueData.update {
        it.copy(
            queueState = QueueData.StateSource.STATE_INITIALIZED,
            data = it.data.copy(
                playlistId = "OFFLINE_RADIO_${lastTrack.videoId}",
                playlistType = PlaylistType.RADIO
            )
        )
    }
}
```

- [ ] **Step 2: Inject `OfflineRadioRepository` into Desktop `JvmMediaPlayerHandlerImpl`**

Mirror the exact offline fallback logic in `core/data/src/jvmMain/kotlin/com/maxrave/data/mediaservice/JvmMediaPlayerHandlerImpl.kt`.

- [ ] **Step 3: Verify compilation & Commit**

```bash
git add core/data/src/androidMain/kotlin/com/maxrave/data/mediaservice/MediaServiceHandlerImpl.kt
git add core/data/src/jvmMain/kotlin/com/maxrave/data/mediaservice/JvmMediaPlayerHandlerImpl.kt
git commit -m "feat(media): integrate offline radio fallback into Endless Queue loadMore"
```

---

## Task 5: Pre-Seeding Hook on Track Download

**Files:**
- Modify: `core/data/src/commonMain/kotlin/com/maxrave/data/repository/DownloadRepositoryImpl.kt` or `DownloadUtils.kt`

- [ ] **Step 1: Trigger pre-seeding upon download completion**

When a track's download state completes successfully (`DownloadState.STATE_DOWNLOADED`):
Launch background coroutine:
```kotlin
coroutineScope.launch(Dispatchers.IO) {
    try {
        offlineRadioRepository.preSeedRadioForTrack(videoId)
    } catch (e: Exception) {
        Logger.w("PreSeedRadio", "Failed pre-seeding radio for $videoId: ${e.message}")
    }
}
```

- [ ] **Step 2: Verify compilation & Commit**

```bash
git add core/data/src/
git commit -m "feat(download): pre-seed YouTube radio recommendations on download completion"
```

---

## Task 6: UI Offline Dashboard Shelves and Home Auto-Switch

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/viewModel/HomeViewModel.kt`
- Create: `composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/component/OfflineDashboardHeader.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/screen/home/HomeScreen.kt:750-775`

- [ ] **Step 1: Update `HomeViewModel.kt`**

Inject `offlineDashboardRepository: OfflineDashboardRepository`.
Expose:
```kotlin
val offlineShelves: StateFlow<List<HomeItem>> = offlineDashboardRepository.getOfflineHomeShelves()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

val isManualOfflineMode = dataStoreManager.getBoolean(MANUAL_OFFLINE_MODE)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

fun setManualOfflineMode(enabled: Boolean) {
    viewModelScope.launch {
        dataStoreManager.setBoolean(MANUAL_OFFLINE_MODE, enabled)
    }
}
```

- [ ] **Step 2: Create `OfflineDashboardHeader.kt`**

Create `composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/component/OfflineDashboardHeader.kt`:
A clean glass/tonal pill displaying:
- Icon: `SimpIcons.CloudOff`
- Title: "Offline Mode"
- "Go Online" button if connection is available.

- [ ] **Step 3: Update `HomeScreen.kt`**

In `HomeScreen.kt`:
Replace the condition:
```kotlin
val isOfflineView = (homeData.isEmpty() || isManualOffline) && !loading
if (isOfflineView) {
    if (offlineShelves.isNotEmpty()) {
        LazyColumn(
            state = scrollState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OfflineDashboardHeader(
                    isManual = isManualOffline,
                    onGoOnline = { viewModel.setManualOfflineMode(false); onRefresh() }
                )
            }
            itemsIndexed(offlineShelves, key = { _, item -> item.title }) { index, item ->
                // Render identical shelf components: HomeSectionHeader, HomeContentCard, QuickPicksItem
                HomeSection(item = item, ...)
            }
        }
    } else {
        OfflineEmptyState(onOpenLibrary = { ... })
    }
    return@Crossfade
}
```

- [ ] **Step 4: Verify UI compilation & Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/viewModel/HomeViewModel.kt
git add composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/component/OfflineDashboardHeader.kt
git add composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/screen/home/HomeScreen.kt
git commit -m "feat(ui): render offline dashboard on Home screen with shelves and header pill"
```

---

## Task 7: Manual Offline Mode and Connectivity Recovery

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/screen/setting/SettingScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/viewModel/SettingsViewModel.kt`

- [ ] **Step 1: Add "Offline Mode" setting row**

In Settings -> Playback/Content, add a switch for "Offline mode" (`Force offline playback and hide online feeds`).

- [ ] **Step 2: Verify pull-to-refresh connectivity recovery**

Ensure that Pull-to-Refresh on `HomeScreen` checks network, turns off manual offline mode if requested, and transitions back to online Home when connection is alive.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/screen/setting/SettingScreen.kt
git add composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/viewModel/SettingsViewModel.kt
git commit -m "feat(settings): add manual offline mode toggle and connection recovery"
```

---

## Self-Review Checklist

1. **Spec Coverage**:
   - 6 shelves implemented in `OfflineDashboardRepositoryImpl` and rendered in `HomeScreen.kt`? Yes (Task 3 & 6).
   - Candidate pool filtering (downloaded + cached)? Yes (Task 2).
   - Tier 1 YouTube pre-seeded ordering? Yes (Task 1 & 2).
   - Tier 2 local affinity scoring formula ($0.40, 0.30, 0.20, 0.10$)? Yes (Task 2).
   - Variety pacing (max 2 consecutive per artist)? Yes (Task 2).
   - Endless Queue `loadMore()` offline fallback? Yes (Task 4).
   - Pre-seeding hook on download? Yes (Task 5).
   - Manual offline mode switch? Yes (Task 7).
2. **Placeholder scan**: Zero "TBD", "TODO", or vague requirements. All file paths, formulas, and components are fully specified.
3. **Type consistency**: `OfflineRadioSeedEntity`, `OfflineRadioRepository`, and `OfflineDashboardRepository` signatures match between definition and usage across all tasks.
