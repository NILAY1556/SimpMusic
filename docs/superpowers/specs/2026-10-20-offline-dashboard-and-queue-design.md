# Offline Dashboard & Smart Offline Radio Queue Design Spec

**Date:** 2026-10-20  
**Status:** Approved  
**Target:** Android & Desktop (Compose Multiplatform)

---

## 1. Executive Summary & Goals

### 1.1 Problem Statement
When a user launches SimpMusic without an active internet connection (or when Home data fails to load), the application currently displays a dead-end `OfflineErrorState` ("You're offline" with a CloudOff icon) pointing only to a single "Listen to downloaded songs" button. Tapping that button navigates to `LibraryDynamicPlaylistScreen(type = Downloaded)`, which is merely a flat, uncurated list of tracks. Furthermore, when playing offline, Endless Queue and Radio functionality abruptly halt because they rely on online calls to YouTube's `next(videoId)` endpoint (`RDAMVM...`).

### 1.2 Goals
1. **Rich Offline Home Dashboard**: Replace the dead-end error state with a full YouTube Music-style dashboard powered entirely by local Room database entities and offline assets, reusing existing `HomeScreen` UI components (carousels, stacked 4-item columns, cover art, hover play buttons).
2. **Six Dedicated Shelves**:
   - **Jump Back In**: Recently played offline tracks and albums from local history.
   - **Quick Picks**: Top-played offline tracks organized into 4-item stacked columns.
   - **Offline Mixes**: Synthesized smart radios (e.g. *Offline Supermix*, *[Top Artist] Mix*, *Offline Discover*).
   - **Downloaded Albums**: Carousel of downloaded albums with full metadata.
   - **Downloaded Artists**: Circular avatars of all artists present in the offline library.
   - **Offline & Local Playlists**: Local user playlists and downloaded YouTube playlists.
3. **Smart Offline Radio & Queue Engine**: Build an intelligent offline queue recommendation engine that seamlessly continues Endless Queue and powers offline "Start Radio":
   - **Tier 1 (YouTube Pre-Seeding)**: When songs are downloaded while online, pre-cache the top YouTube radio recommendation IDs; offline radio prioritizes any matching downloaded tracks to replicate YouTube Music's exact algorithmic order.
   - **Tier 2 (Local Affinity Graph)**: When pre-seeded tracks run out or are unavailable, dynamically score candidates using artist/collaborator affinity, local session co-occurrence from `playback_event`, and album/playlist clustering.
   - **Variety & Pacing**: Limit consecutive tracks by the same artist (max 2) and prevent repeats until the pool is exhausted.
4. **Cache & Download Unification**: Seamlessly include Android's fully cached tracks (`playerCache.isFullyCached`) alongside explicitly downloaded tracks.
5. **Manual Offline Mode**: Allow users to toggle "Force Offline Mode" from Settings or the top dashboard bar to enjoy an intentional offline experience anytime.

---

## 2. Architecture & Data Layer

### 2.1 Database Additions (Room Schema Version 28)

#### New Entity: `OfflineRadioSeedEntity`
Stores top related track recommendations fetched opportunistically when tracks are downloaded while online.

```kotlin
@Entity(
    tableName = "offline_radio_seed",
    indices = [
        Index(value = ["seedVideoId"]),
        Index(value = ["seedVideoId", "relatedVideoId"], unique = true)
    ]
)
data class OfflineRadioSeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seedVideoId: String,
    val relatedVideoId: String,
    val rank: Int, // 1..25 order as returned by YouTube RDAMVM
    val fetchedAt: LocalDateTime = now(),
)
```

#### Database Migration (v27 $\to$ v28)
An AutoMigration or explicit `Migration(27, 28)` that creates the `offline_radio_seed` table.

#### DAO Queries (`DatabaseDao.kt`)
1. **Radio Seeds**:
   ```kotlin
   @Query("SELECT relatedVideoId FROM offline_radio_seed WHERE seedVideoId = :seedVideoId ORDER BY rank ASC")
   fun getRadioSeedsForTrack(seedVideoId: String): Flow<List<String>>

   @Insert(onConflict = OnConflictStrategy.REPLACE)
   suspend fun insertRadioSeeds(seeds: List<OfflineRadioSeedEntity>)

   @Query("DELETE FROM offline_radio_seed WHERE fetchedAt < :threshold")
   suspend fun pruneOldRadioSeeds(threshold: LocalDateTime)
   ```
2. **Downloaded & Cached Aggregations**:
   ```kotlin
   @Query("""
       SELECT * FROM song 
       WHERE downloadState = 1 OR videoId IN (:cachedIds)
       ORDER BY downloadedAt DESC
   """)
   fun getDownloadedAndCachedSongs(cachedIds: List<String>): Flow<List<SongEntity>>

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

### 2.2 Repositories

#### `OfflineDashboardRepository` (Domain Interface & Data Implementation)
```kotlin
interface OfflineDashboardRepository {
    fun getOfflineHomeShelves(): Flow<List<HomeItem>>
    suspend fun getOfflineMixes(): List<Content>
}
```
* Responsibilities:
  * Reads downloaded songs and (on Android) fully cached song IDs from `CacheRepository.getFullyCachedSongIds()`.
  * Queries `playback_event` for "Jump Back In" history filtered to songs currently offline.
  * Groups top played offline tracks into columns of 4 for "Quick Picks".
  * Synthesizes 4 distinct "Offline Mix" playlists:
    1. *Offline Supermix*: Balanced cross-section of top offline artists.
    2. *[Artist 1] & Similar*: Top artist offline songs + collaborators.
    3. *[Artist 2] Mix*: Second top artist offline songs.
    4. *Offline Discover*: Downloaded songs with low play counts to rediscover saved gems.
  * Formats downloaded albums, artists, and playlists into standard `HomeItem` objects.

#### `OfflineRadioRepository` (Domain Interface & Data Implementation)
```kotlin
interface OfflineRadioRepository {
    suspend fun buildOfflineQueue(
        seedTrack: Track,
        excludeVideoIds: Set<String>,
        limit: Int = 25,
    ): List<Track>

    suspend fun preSeedRadioForTrack(videoId: String)
}
```
* Responsibilities:
  * Executes the multi-tier recommendation algorithm.
  * Called by `downloadTrack()` in background when online to populate `offline_radio_seed`.

---

## 3. Offline Radio & Queue Recommendation Algorithm

### 3.1 Candidate Pool
1. Collect all songs from local database where `downloadState == STATE_DOWNLOADED` or `videoId in fullyCachedIds`.
2. Filter out:
   - Tracks currently present in `queueData.listTracks` (strict anti-repeat).
   - Live streams (`isLive == true`).
   - Tracks with missing/corrupted local audio.

### 3.2 Tier 1: YouTube Pre-Seeded Matches (Online Replica)
1. Query `offline_radio_seed` for `seedTrack.videoId`.
2. Inspect `relatedVideoId` in ascending `rank` order.
3. If `relatedVideoId` exists in the Candidate Pool, enqueue it immediately.
4. Continue until either all pre-seeded matches are added or the requested batch `limit` (e.g. 25 tracks) is satisfied.

### 3.3 Tier 2: Local Affinity Graph & Collaborative Filtering
If Tier 1 yields fewer tracks than `limit`:
For every candidate track $T$ in the remaining Candidate Pool, compute composite affinity score against seed $S$:

$$\text{Score}(T) = 0.40 \cdot \text{Artist}(S, T) + 0.30 \cdot \text{SessionCooccurrence}(S, T) + 0.20 \cdot \text{Context}(S, T) + 0.10 \cdot \text{PlayAffinity}(T)$$

* **$\text{Artist}(S, T)$ (0.40 weight)**:
  * $1.0$ if primary artist matches (`artistId[0] == seed.artistId[0]`).
  * $0.6$ if any secondary/collaborating artist matches.
  * $0.0$ otherwise.
* **$\text{SessionCooccurrence}(S, T)$ (0.30 weight)**:
  * Number of times track $T$ was played within a 30-minute window of track $S$ in `playback_event`, normalized by $\max(1, \text{total sessions of } S)$.
* **$\text{Context}(S, T)$ (0.20 weight)**:
  * $1.0$ if both belong to the same album (`albumId`).
  * $0.7$ if both co-exist in any user local playlist (`pair_song_local_playlist`).
  * $0.0$ otherwise.
* **$\text{PlayAffinity}(T)$ (0.10 weight)**:
  * Normalized play count and recency: $\frac{\text{totalPlayTime}}{\max(\text{playTimes})}$.

### 3.4 Variety & Pacing Rules
* **Artist Clumping Guard**: When candidate pool contains $\ge 10$ tracks, no more than 2 consecutive tracks in the queue may share the same primary artist. Related and collaborator tracks are dynamically interleaved.
* **Weighted Roulette Sampling**: Instead of deterministic descending sorting, candidates in the top quartile of scores are selected using weighted stochastic sampling to keep repeated radio sessions fresh.

### 3.5 Endless Queue Integration
In `MediaServiceHandlerImpl` (Android) and `JvmMediaPlayerHandlerImpl` (Desktop):
* Modify `loadMore()`:
  ```kotlin
  if (networkIsUnavailable || isManualOfflineMode) {
      val lastTrack = queueData.value.data.listTracks.lastOrNull() ?: return
      val nextBatch = offlineRadioRepository.buildOfflineQueue(
          seedTrack = lastTrack,
          excludeVideoIds = queueData.value.data.listTracks.map { it.videoId }.toSet(),
          limit = 25
      )
      if (nextBatch.isNotEmpty()) {
          loadMoreCatalog(ArrayList(nextBatch))
      }
      return
  }
  ```
* Endless Queue continues seamlessly without interruptions, error toasts, or dead-ends.

---

## 4. Presentation & UI Layer

### 4.1 Home Screen State Machine (`HomeScreen.kt`)
* State detection:
  * `val isOffline = homeData.isEmpty() || isManualOfflineMode`
* When `isOffline` is true and local downloaded/cached tracks exist:
  * Replace `OfflineErrorState` with the **Offline Home Dashboard**.
* Top Bar Header:
  * Displays an "Offline Mode" chip with `SimpIcons.CloudOff`.
  * If the device has an active internet connection (e.g. manual offline mode was toggled), displays a "Go Online" button.
  * Pull-To-Refresh checks connectivity: if connected, fetches online Home data and smoothly transitions back to online view.

### 4.2 The 6 Dashboard Shelves
All shelves reuse existing SimpMusic components and design tokens:
1. **Jump Back In**:
   * Uses `HomeDiscoverSection` horizontal carousel.
   * Cards for recent offline listening sessions with 1-tap play and hover play buttons.
2. **Quick Picks**:
   * Renders with `itemsPerColumn = 4` using standard `QuickPicksItem`.
   * Displays 12–20 top offline tracks.
3. **Offline Mixes**:
   * Horizontal carousel of 4 cards wearing dynamic gradient backgrounds:
     * *Offline Supermix*
     * *[Top Artist] & Similar Mix*
     * *[Second Artist] Mix*
     * *Offline Discover* (lesser played downloaded tracks)
4. **Downloaded Albums**:
   * Horizontal carousel of album cards showing sleeve artwork, title, artist, and year.
   * Clicking navigates to `AlbumDestination`.
5. **Downloaded Artists**:
   * Horizontal carousel of circular artist avatars with channel titles.
   * Clicking navigates to `ArtistDestination`.
6. **Offline & Local Playlists**:
   * Cards showing user local playlists + downloaded YouTube playlists with item count and play buttons.

### 4.3 Cached Song Indicators
* Tracks provided via player cache (Android) are subtly badged with a small "Cached" tag or icon in item secondary info to distinguish them from permanent user downloads.

---

## 5. Edge Cases & Error Handling

1. **Zero Downloaded / Cached Songs**:
   * If the user is offline and has 0 playable offline tracks, render a friendly empty state:
     * Illustration / Icon: `SimpIcons.DownloadForOffline`
     * Title: *"No Offline Music"*
     * Subtitle: *"Download your favorite songs, albums, and playlists while online to listen anywhere."*
     * Action button: *"Open Library"* or *"Retry Connection"*.
2. **Small Library (< 10 tracks)**:
   * Relax variety pacing (allow consecutive tracks from the same artist if necessary).
   * Collapse the 4 "Offline Mixes" into 1 or 2 mixes to avoid duplicate track listings across cards.
3. **Pre-Seeding Failure / Rate Limits**:
   * If `preSeedRadioForTrack()` fails or times out while downloading, it is silently caught and logged. Tier 2 (Local Affinity Graph) operates autonomously with zero impact on the user.
4. **Network State Recovery**:
   * When internet connectivity returns, Pull-to-refresh or tapping "Go Online" immediately re-runs `viewModel.getHomeData()`, seamlessly restoring the online dashboard.

---

## 6. Verification & Test Plan

### 6.1 Unit Tests (`core/domain` & `core/data` in `jvmTest`)
* **`OfflineRadioGeneratorTest`**:
  * Verify candidate pool filtering (excludes live streams and tracks currently in queue).
  * Verify Tier 1 YouTube pre-seeded ordering takes precedence when matches exist.
  * Verify Tier 2 formula weights (artist match, session co-occurrence, album/playlist proximity).
  * Verify variety pacing constraints (no $>2$ consecutive tracks by same artist when pool $\ge 10$).
  * Verify graceful looping when pool has $\le 3$ songs.
* **`OfflineDashboardRepositoryTest`**:
  * Verify mapping of `SongEntity`, `AlbumEntity`, `ArtistEntity`, and `PlaybackEventEntity` into `HomeItem` and `Content` shelves.

### 6.2 Manual Verification Scenarios
* **Scenario A (Clean Offline Switch)**:
  1. Download 20 songs across 3 artists and 2 albums.
  2. Enable Airplane mode or toggle Manual Offline Mode.
  3. Launch / open Home screen: Verify all 6 shelves appear with correct metadata, thumbnails, and play counts.
* **Scenario B (Offline Radio & Endless Queue)**:
  1. While in Airplane mode, tap a track from Quick Picks.
  2. Let track play to near-end (or scrub to end): Verify Endless Queue triggers `loadMore()` and appends similar offline tracks automatically.
  3. Observe Queue sheet: verify no tracks repeat and artist clumping is avoided.
* **Scenario C (Network Reconnection)**:
  1. Re-enable Wi-Fi.
  2. Pull down to refresh on Home: Verify smooth transition back to the live YouTube Music online dashboard.
