package com.maxrave.simpmusic.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.error
import coil3.request.placeholder
import coil3.toBitmap
import com.maxrave.common.Config
import com.maxrave.simpmusic.MainActivity
import com.maxrave.simpmusic.R
import com.maxrave.simpmusic.viewModel.NowPlayingScreenData
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import java.util.Locale

@Volatile
private var currentIsLiked: Boolean = false

private val NowPlayingScreenData.isLiked: Boolean
    get() = currentIsLiked

private fun SharedViewModel.like(liked: Boolean) {
    onUIEvent(UIEvent.ToggleLike)
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ENGLISH, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ENGLISH, "%d:%02d", minutes, seconds)
    }
}

class AestheticPlayerWidget :
    GlanceAppWidget(),
    KoinComponent {

    companion object {
        val COMPACT = DpSize(180.dp, 56.dp)
        val EXPANDED = DpSize(260.dp, 110.dp)
    }

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(COMPACT, EXPANDED))

    private val sharedViewModel by inject<SharedViewModel>()
    private val serviceScope by inject<CoroutineScope>(named(Config.SERVICE_SCOPE))

    @SuppressLint("RestrictedApi")
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        serviceScope.launch {
            val controllerJob =
                launch {
                    sharedViewModel.controllerState.collectLatest { updateWidget(context) }
                }
            val nowPlayingJob =
                launch {
                    sharedViewModel.nowPlayingScreenData.collectLatest { updateWidget(context) }
                }
            controllerJob.join()
            nowPlayingJob.join()
        }

        provideContent {
            GlanceTheme {
                val size = LocalSize.current
                val isExpanded = size.width >= 250.dp && size.height >= 105.dp

                val controllerState by sharedViewModel.controllerState.collectAsState()
                val screenDataState by sharedViewModel.nowPlayingScreenData.collectAsState()
                val timelineState by sharedViewModel.timeline.collectAsState()

                currentIsLiked = controllerState.isLiked
                val isLiked = controllerState.isLiked
                val isPlaying = controllerState.isPlaying

                val title = screenDataState.nowPlayingTitle.ifBlank { "SimpMusic" }
                val artist = screenDataState.artistName.ifBlank { "Tap to play music" }

                var artwork by remember { mutableStateOf<Bitmap?>(null) }
                val thumbUrl by remember { derivedStateOf { screenDataState.thumbnailURL } }

                LaunchedEffect(thumbUrl) {
                    if (thumbUrl.isNullOrEmpty()) {
                        artwork = null
                        return@LaunchedEffect
                    }
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(thumbUrl)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .diskCacheKey(thumbUrl + "BIGGER")
                            .placeholder(R.drawable.holder)
                            .error(R.drawable.holder)
                            .allowHardware(false)
                            .build()
                    val loaded =
                        (ImageLoader(context).execute(request) as? SuccessResult)
                            ?.image
                            ?.toBitmap()
                    artwork = loaded
                }

                Box(
                    modifier =
                        GlanceModifier
                            .fillMaxSize()
                            .background(ImageProvider(R.drawable.widget_glass_card))
                            .cornerRadius(24.dp)
                            .clickable(actionStartActivity<MainActivity>(), rippleOverride = R.drawable.no_ripple)
                            .padding(if (isExpanded) 12.dp else 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isExpanded) {
                        ExpandedLayout(
                            artwork = artwork,
                            title = title,
                            artist = artist,
                            isLiked = isLiked,
                            isPlaying = isPlaying,
                            currentPosition = timelineState.current,
                            totalDuration = timelineState.total,
                            isLive = timelineState.isLive,
                            onLike = { sharedViewModel.like(!screenDataState.isLiked) },
                            onPrev = { sharedViewModel.onUIEvent(UIEvent.Previous) },
                            onPlayPause = { sharedViewModel.onUIEvent(UIEvent.PlayPause) },
                            onNext = { sharedViewModel.onUIEvent(UIEvent.Next) },
                        )
                    } else {
                        CompactLayout(
                            artwork = artwork,
                            title = title,
                            artist = artist,
                            isPlaying = isPlaying,
                            onPlayPause = { sharedViewModel.onUIEvent(UIEvent.PlayPause) },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun CompactLayout(
        artwork: Bitmap?,
        title: String,
        artist: String,
        isPlaying: Boolean,
        onPlayPause: () -> Unit,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    GlanceModifier
                        .size(48.dp)
                        .cornerRadius(12.dp)
                        .clickable(actionStartActivity<MainActivity>(), rippleOverride = R.drawable.no_ripple),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = artwork?.let { ImageProvider(it) } ?: ImageProvider(R.drawable.holder),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier =
                        GlanceModifier
                            .fillMaxSize()
                            .cornerRadius(12.dp),
                )
            }

            Spacer(GlanceModifier.width(10.dp))

            Column(
                modifier = GlanceModifier.defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    style =
                        TextStyle(
                            color = ColorProvider(Color.White),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                )
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    text = artist,
                    maxLines = 1,
                    style =
                        TextStyle(
                            color = ColorProvider(Color.White.copy(alpha = 0.75f)),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                        ),
                )
            }

            Spacer(GlanceModifier.width(8.dp))

            Box(
                modifier =
                    GlanceModifier
                        .size(40.dp)
                        .background(ImageProvider(R.drawable.widget_play_disc_accent))
                        .clickable(rippleOverride = R.drawable.no_ripple) {
                            onPlayPause()
                        },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider =
                        ImageProvider(
                            if (isPlaying) {
                                R.drawable.rounded_pause_24
                            } else {
                                R.drawable.rounded_play_arrow_24
                            }
                        ),
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    colorFilter = ColorFilter.tint(ColorProvider(Color(0xFF1C1C1E))),
                    modifier = GlanceModifier.size(24.dp),
                )
            }
        }
    }

    @Composable
    private fun ExpandedLayout(
        artwork: Bitmap?,
        title: String,
        artist: String,
        isLiked: Boolean,
        isPlaying: Boolean,
        currentPosition: Long,
        totalDuration: Long,
        isLive: Boolean,
        onLike: () -> Unit,
        onPrev: () -> Unit,
        onPlayPause: () -> Unit,
        onNext: () -> Unit,
    ) {
        val systemRtl = Resources.getSystem().configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

        val progress =
            if (totalDuration > 0L) {
                (currentPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }

        val likeButton: @Composable () -> Unit = {
            val heartIcon =
                if (isLiked) {
                    R.drawable.baseline_favorite_24
                } else {
                    R.drawable.baseline_favorite_border_24
                }
            val heartTint =
                if (isLiked) {
                    Color(0xFFD10000)
                } else {
                    Color.White.copy(alpha = 0.7f)
                }
            Image(
                provider = ImageProvider(heartIcon),
                contentDescription = if (isLiked) "Unlike" else "Like",
                colorFilter = ColorFilter.tint(ColorProvider(heartTint)),
                modifier =
                    GlanceModifier
                        .size(32.dp)
                        .padding(4.dp)
                        .clickable(rippleOverride = R.drawable.no_ripple) {
                            onLike()
                        },
            )
        }

        val prevButton: @Composable () -> Unit = {
            Image(
                provider = ImageProvider(R.drawable.rounded_skip_previous_24),
                contentDescription = "Previous",
                colorFilter = ColorFilter.tint(ColorProvider(Color.White)),
                modifier =
                    GlanceModifier
                        .size(32.dp)
                        .padding(4.dp)
                        .clickable(rippleOverride = R.drawable.no_ripple) {
                            onPrev()
                        },
            )
        }

        val playButton: @Composable () -> Unit = {
            Box(
                modifier =
                    GlanceModifier
                        .size(40.dp)
                        .background(ImageProvider(R.drawable.widget_play_disc_accent))
                        .clickable(rippleOverride = R.drawable.no_ripple) {
                            onPlayPause()
                        },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider =
                        ImageProvider(
                            if (isPlaying) {
                                R.drawable.rounded_pause_24
                            } else {
                                R.drawable.rounded_play_arrow_24
                            }
                        ),
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    colorFilter = ColorFilter.tint(ColorProvider(Color(0xFF1C1C1E))),
                    modifier = GlanceModifier.size(24.dp),
                )
            }
        }

        val nextButton: @Composable () -> Unit = {
            Image(
                provider = ImageProvider(R.drawable.rounded_skip_next_24),
                contentDescription = "Next",
                colorFilter = ColorFilter.tint(ColorProvider(Color.White)),
                modifier =
                    GlanceModifier
                        .size(32.dp)
                        .padding(4.dp)
                        .clickable(rippleOverride = R.drawable.no_ripple) {
                            onNext()
                        },
            )
        }

        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    GlanceModifier
                        .size(72.dp)
                        .cornerRadius(16.dp)
                        .clickable(actionStartActivity<MainActivity>(), rippleOverride = R.drawable.no_ripple),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = artwork?.let { ImageProvider(it) } ?: ImageProvider(R.drawable.holder),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier =
                        GlanceModifier
                            .fillMaxSize()
                            .cornerRadius(16.dp),
                )
            }

            Column(
                modifier =
                    GlanceModifier
                        .defaultWeight()
                        .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    style =
                        TextStyle(
                            color = ColorProvider(Color.White),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                )
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    text = artist,
                    maxLines = 1,
                    style =
                        TextStyle(
                            color = ColorProvider(Color.White.copy(alpha = 0.75f)),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                        ),
                )
                Spacer(GlanceModifier.height(6.dp))

                LinearProgressIndicator(
                    progress = progress,
                    modifier =
                        GlanceModifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .cornerRadius(2.dp),
                    color = ColorProvider(Color.White),
                    backgroundColor = ColorProvider(Color.White.copy(alpha = 0.25f)),
                )
                Spacer(GlanceModifier.height(2.dp))

                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isLive) "" else formatDuration(currentPosition),
                        style =
                            TextStyle(
                                color = ColorProvider(Color.White.copy(alpha = 0.65f)),
                                fontSize = 10.sp,
                            ),
                    )
                    Spacer(GlanceModifier.defaultWeight())
                    Text(
                        text = if (isLive) "LIVE" else formatDuration(totalDuration),
                        style =
                            TextStyle(
                                color = ColorProvider(Color.White.copy(alpha = 0.65f)),
                                fontSize = 10.sp,
                            ),
                    )
                }
                Spacer(GlanceModifier.height(4.dp))

                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (systemRtl) {
                        nextButton()
                        Spacer(GlanceModifier.defaultWeight())
                        playButton()
                        Spacer(GlanceModifier.defaultWeight())
                        prevButton()
                        Spacer(GlanceModifier.defaultWeight())
                        likeButton()
                    } else {
                        likeButton()
                        Spacer(GlanceModifier.defaultWeight())
                        prevButton()
                        Spacer(GlanceModifier.defaultWeight())
                        playButton()
                        Spacer(GlanceModifier.defaultWeight())
                        nextButton()
                    }
                }
            }
        }
    }

    private suspend fun updateWidget(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        manager.getGlanceIds(this@AestheticPlayerWidget.javaClass).forEach { glanceId ->
            this@AestheticPlayerWidget.update(context, glanceId)
        }
    }
}
