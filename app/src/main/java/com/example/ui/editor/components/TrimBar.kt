package com.example.ui.editor.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.TrimRange
import com.example.domain.model.VideoClip
import kotlin.math.roundToInt

/**
 * Interactive Timeline & Trimming UI component.
 *
 * Features:
 * - Filmstrip background displaying sampled video frame thumbnails.
 * - Dual-handle slider to select START and END trim timestamps.
 * - Draggable tactile handles with minimum 48dp touch targets.
 * - Highlighted active region with electric accent border and dimmed excluded areas.
 * - Playhead needle tracking current playback position.
 * - Floating frame preview tooltip while dragging.
 * - Precision +/- 100ms and +/- 1s fine-tuning controls.
 */
@Composable
fun TrimBar(
    totalDurationMs: Long,
    trimRange: TrimRange,
    playbackPositionMs: Long,
    thumbnails: List<Bitmap>,
    isExtractingThumbnails: Boolean,
    scrubPreviewFrame: Bitmap?,
    isScrubbing: Boolean,
    onTrimRangeChange: (startMs: Long, endMs: Long) -> Unit,
    onScrub: (timeMs: Long) -> Unit,
    onScrubFinished: () -> Unit,
    onAdjustStart: (deltaMs: Long) -> Unit,
    onAdjustEnd: (deltaMs: Long) -> Unit,
    onResetTrim: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val handleTouchWidthDp = 48.dp
    val handleVisualWidthDp = 18.dp
    val timelineHeightDp = 64.dp

    var activeDragHandle by remember { mutableStateOf<String?>(null) } // "start", "end", "playhead", or null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Floating frame preview tooltip when user is scrubbing
        if (isScrubbing && scrubPreviewFrame != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF1E293B),
                    border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    shadowElevation = 8.dp
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Image(
                            bitmap = scrubPreviewFrame.asImageBitmap(),
                            contentDescription = "Scrub Frame Preview",
                            modifier = Modifier
                                .size(width = 120.dp, height = 68.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Text(
                            text = VideoClip.formatMillisecondsWithFractions(playbackPositionMs),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // Timeline Container
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(timelineHeightDp + 16.dp) // extra padding for handle grab tabs
                .padding(horizontal = 8.dp)
        ) {
            val containerWidthPx = with(density) { (maxWidth - handleTouchWidthDp).toPx() }
            val containerWidthDp = maxWidth - handleTouchWidthDp

            if (totalDurationMs > 0 && containerWidthPx > 0) {
                val startRatio = (trimRange.startMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                val endRatio = (trimRange.endMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                val playheadRatio = (playbackPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)

                val startOffsetPx = startRatio * containerWidthPx
                val endOffsetPx = endRatio * containerWidthPx
                val playheadOffsetPx = playheadRatio * containerWidthPx

                // Base filmstrip container
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(timelineHeightDp)
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0F172A))
                ) {
                    // Row of extracted video thumbnails
                    if (thumbnails.isNotEmpty()) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            thumbnails.forEach { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    } else if (isExtractingThumbnails) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Analyzing frames...",
                                    fontSize = 12.sp,
                                    color = Color.LightGray
                                )
                            }
                        }
                    }

                    // Canvas overlay for Dimmed Unselected Regions and Selection Border
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val canvasWidth = size.width
                        val canvasHeight = size.height

                        // 1. Dim left unselected region
                        if (startOffsetPx > 0) {
                            drawRect(
                                color = Color.Black.copy(alpha = 0.65f),
                                topLeft = Offset.Zero,
                                size = Size(startOffsetPx, canvasHeight)
                            )
                        }

                        // 2. Dim right unselected region
                        if (endOffsetPx < canvasWidth) {
                            drawRect(
                                color = Color.Black.copy(alpha = 0.65f),
                                topLeft = Offset(endOffsetPx, 0f),
                                size = Size(canvasWidth - endOffsetPx, canvasHeight)
                            )
                        }

                        // 3. Highlighted active region frame (cyan/electric primary)
                        val trimWidth = (endOffsetPx - startOffsetPx).coerceAtLeast(0f)
                        drawRoundRect(
                            color = Color(0xFF06B6D4), // Vibrant Cyan Accent
                            topLeft = Offset(startOffsetPx, 0f),
                            size = Size(trimWidth, canvasHeight),
                            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                }

                // Playhead needle indicator
                Box(
                    modifier = Modifier
                        .offset { IntOffset(playheadOffsetPx.roundToInt(), 0) }
                        .width(3.dp)
                        .height(timelineHeightDp + 8.dp)
                        .align(Alignment.CenterStart)
                        .background(Color.White, RoundedCornerShape(2.dp))
                )

                // Draggable START Handle (Left)
                Box(
                    modifier = Modifier
                        .offset { IntOffset(startOffsetPx.roundToInt() - with(density) { (handleTouchWidthDp / 2).toPx() }.roundToInt(), 0) }
                        .width(handleTouchWidthDp)
                        .fillMaxHeight()
                        .align(Alignment.CenterStart)
                        .testTag("trim_handle_start")
                        .pointerInput(totalDurationMs, containerWidthPx) {
                            detectDragGestures(
                                onDragStart = {
                                    activeDragHandle = "start"
                                },
                                onDragEnd = {
                                    activeDragHandle = null
                                    onScrubFinished()
                                },
                                onDragCancel = {
                                    activeDragHandle = null
                                    onScrubFinished()
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val currentRatio = (trimRange.startMs.toFloat() / totalDurationMs.toFloat())
                                    val deltaRatio = dragAmount.x / containerWidthPx
                                    val newRatio = (currentRatio + deltaRatio).coerceIn(0f, 1f)
                                    val newStartMs = (newRatio * totalDurationMs).toLong()
                                    val clampedStart = newStartMs.coerceIn(0L, trimRange.endMs - 500L)
                                    onTrimRangeChange(clampedStart, trimRange.endMs)
                                    onScrub(clampedStart)
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Tactile Handle Bar
                    Surface(
                        modifier = Modifier
                            .width(handleVisualWidthDp)
                            .height(timelineHeightDp + 10.dp),
                        shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 4.dp, bottomEnd = 4.dp),
                        color = Color(0xFF06B6D4),
                        shadowElevation = 6.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                repeat(3) {
                                    Box(
                                        modifier = Modifier
                                            .width(2.dp)
                                            .height(8.dp)
                                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                    )
                                }
                            }
                        }
                    }
                }

                // Draggable END Handle (Right)
                Box(
                    modifier = Modifier
                        .offset { IntOffset(endOffsetPx.roundToInt() - with(density) { (handleTouchWidthDp / 2).toPx() }.roundToInt(), 0) }
                        .width(handleTouchWidthDp)
                        .fillMaxHeight()
                        .align(Alignment.CenterStart)
                        .testTag("trim_handle_end")
                        .pointerInput(totalDurationMs, containerWidthPx) {
                            detectDragGestures(
                                onDragStart = {
                                    activeDragHandle = "end"
                                },
                                onDragEnd = {
                                    activeDragHandle = null
                                    onScrubFinished()
                                },
                                onDragCancel = {
                                    activeDragHandle = null
                                    onScrubFinished()
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val currentRatio = (trimRange.endMs.toFloat() / totalDurationMs.toFloat())
                                    val deltaRatio = dragAmount.x / containerWidthPx
                                    val newRatio = (currentRatio + deltaRatio).coerceIn(0f, 1f)
                                    val newEndMs = (newRatio * totalDurationMs).toLong()
                                    val clampedEnd = newEndMs.coerceIn(trimRange.startMs + 500L, totalDurationMs)
                                    onTrimRangeChange(trimRange.startMs, clampedEnd)
                                    onScrub(clampedEnd)
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Tactile Handle Bar
                    Surface(
                        modifier = Modifier
                            .width(handleVisualWidthDp)
                            .height(timelineHeightDp + 10.dp),
                        shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 8.dp, bottomEnd = 8.dp),
                        color = Color(0xFF06B6D4),
                        shadowElevation = 6.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                repeat(3) {
                                    Box(
                                        modifier = Modifier
                                            .width(2.dp)
                                            .height(8.dp)
                                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Precision Fine-Tuning Controls Row
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131C31)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Start time micro-adjustments
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = "START: ${trimRange.formattedStart}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF38BDF8),
                        fontFamily = FontFamily.Monospace
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        FineTuneButton(text = "-1s", onClick = { onAdjustStart(-1000L) }, testTag = "start_minus_1s")
                        FineTuneButton(text = "-100ms", onClick = { onAdjustStart(-100L) }, testTag = "start_minus_100ms")
                        FineTuneButton(text = "+100ms", onClick = { onAdjustStart(100L) }, testTag = "start_plus_100ms")
                        FineTuneButton(text = "+1s", onClick = { onAdjustStart(1000L) }, testTag = "start_plus_1s")
                    }
                }

                // Reset trim button
                IconButton(
                    onClick = onResetTrim,
                    modifier = Modifier
                        .testTag("reset_trim_button")
                        .size(36.dp)
                        .background(Color(0xFF1E293B), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reset Trim Selection",
                        tint = Color.LightGray,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // End time micro-adjustments
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "END: ${trimRange.formattedEnd}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF38BDF8),
                        fontFamily = FontFamily.Monospace
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        FineTuneButton(text = "-1s", onClick = { onAdjustEnd(-1000L) }, testTag = "end_minus_1s")
                        FineTuneButton(text = "-100ms", onClick = { onAdjustEnd(-100L) }, testTag = "end_minus_100ms")
                        FineTuneButton(text = "+100ms", onClick = { onAdjustEnd(100L) }, testTag = "end_plus_100ms")
                        FineTuneButton(text = "+1s", onClick = { onAdjustEnd(1000L) }, testTag = "end_plus_1s")
                    }
                }
            }
        }
    }
}

@Composable
private fun FineTuneButton(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFF1E293B),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
        modifier = modifier.testTag(testTag)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}
