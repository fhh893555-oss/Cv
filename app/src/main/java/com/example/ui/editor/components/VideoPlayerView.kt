package com.example.ui.editor.components

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.domain.model.TrimRange
import com.example.domain.model.VideoClip
import kotlinx.coroutines.delay

/**
 * Composable integrating Jetpack Media3 ExoPlayer with loop boundaries,
 * real-time timestamp indicators, aspect ratio toggling, and gesture overlays.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerView(
    videoUri: Uri,
    isPlaying: Boolean,
    trimRange: TrimRange,
    isLooping: Boolean,
    targetSeekPositionMs: Long?,
    onPlaybackPositionUpdate: (Long) -> Unit,
    onPlayPauseToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isControlsVisible by remember { mutableStateOf(true) }
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // ExoPlayer initialization and lifecycle
    val exoPlayer = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = false
        }
    }

    val currentTrimRange by rememberUpdatedState(trimRange)
    val currentIsLooping by rememberUpdatedState(isLooping)
    val currentIsPlaying by rememberUpdatedState(isPlaying)

    // Sync isPlaying state with ExoPlayer
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            val currentPos = exoPlayer.currentPosition
            // Ensure playback starts within trim range
            if (currentPos < currentTrimRange.startMs || currentPos >= currentTrimRange.endMs) {
                exoPlayer.seekTo(currentTrimRange.startMs)
            }
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    // React to manual seek scrubbing from outside (slider or fine-tuning buttons)
    LaunchedEffect(targetSeekPositionMs) {
        targetSeekPositionMs?.let { seekMs ->
            exoPlayer.seekTo(seekMs)
        }
    }

    // High frequency ticker to monitor playback position and handle boundary looping
    LaunchedEffect(exoPlayer, isPlaying) {
        while (true) {
            val currentPos = exoPlayer.currentPosition
            onPlaybackPositionUpdate(currentPos)

            if (currentIsPlaying) {
                // If we reach or exceed the end trim boundary
                if (currentPos >= currentTrimRange.endMs && currentTrimRange.endMs > currentTrimRange.startMs) {
                    if (currentIsLooping) {
                        exoPlayer.seekTo(currentTrimRange.startMs)
                        exoPlayer.play()
                    } else {
                        exoPlayer.pause()
                        onPlayPauseToggle()
                    }
                } else if (currentPos < currentTrimRange.startMs) {
                    exoPlayer.seekTo(currentTrimRange.startMs)
                }
            }

            delay(33) // ~30 fps ticker for smooth playhead updating
        }
    }

    // Auto-hide controls overlay after 3 seconds of inactivity
    LaunchedEffect(isControlsVisible, isPlaying) {
        if (isControlsVisible && isPlaying) {
            delay(3000)
            isControlsVisible = false
        }
    }

    DisposableEffect(videoUri) {
        onDispose {
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070B14))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                isControlsVisible = !isControlsVisible
            },
        contentAlignment = Alignment.Center
    ) {
        // AndroidView embedding Media3 PlayerView
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false // Custom Jetpack Compose controls used
                    setResizeMode(resizeMode)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                playerView.setResizeMode(resizeMode)
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay controls
        AnimatedVisibility(
            visible = isControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                // Top control bar: Timestamp display and Aspect Ratio Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                    ) {
                        Text(
                            text = "${VideoClip.formatMillisecondsWithFractions(exoPlayer.currentPosition)} / ${VideoClip.formatMillisecondsWithFractions(currentTrimRange.endMs)}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    // Aspect ratio mode toggle
                    IconButton(
                        onClick = {
                            resizeMode = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            } else {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        },
                        modifier = Modifier
                            .testTag("aspect_ratio_button")
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) Icons.Default.Fullscreen else Icons.Default.FitScreen,
                            contentDescription = "Toggle Aspect Ratio",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Center playback controls: -5s, Play/Pause, +5s
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Rewind 5s
                    IconButton(
                        onClick = {
                            val newPos = (exoPlayer.currentPosition - 5000L).coerceAtLeast(currentTrimRange.startMs)
                            exoPlayer.seekTo(newPos)
                        },
                        modifier = Modifier
                            .testTag("rewind_5s_button")
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            .size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay5,
                            contentDescription = "Rewind 5 seconds",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Play/Pause Primary Action
                    Surface(
                        onClick = onPlayPauseToggle,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .testTag("play_pause_button")
                            .size(68.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause video" else "Play video",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }

                    // Fast forward 5s
                    IconButton(
                        onClick = {
                            val newPos = (exoPlayer.currentPosition + 5000L).coerceAtMost(currentTrimRange.endMs)
                            exoPlayer.seekTo(newPos)
                        },
                        modifier = Modifier
                            .testTag("forward_5s_button")
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            .size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forward5,
                            contentDescription = "Fast forward 5 seconds",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Bottom badge indicating loop mode status
                Surface(
                    color = if (isLooping) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isLooping) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay,
                            contentDescription = null,
                            tint = if (isLooping) MaterialTheme.colorScheme.primary else Color.LightGray,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (isLooping) "Looping within trim bounds" else "Single play",
                            fontSize = 11.sp,
                            color = if (isLooping) MaterialTheme.colorScheme.primary else Color.LightGray,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
