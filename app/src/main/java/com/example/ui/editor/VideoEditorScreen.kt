package com.example.ui.editor

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.domain.model.TrimRange
import com.example.domain.usecase.TrimExportState
import com.example.ui.editor.components.TrimBar
import com.example.ui.editor.components.VideoPlayerView

/**
 * Main Screen for the Video Editor App.
 *
 * Implements:
 * - Scoped Storage photo/video picker import (`ActivityResultContracts.PickVisualMedia`).
 * - Media3 ExoPlayer playback and looping within trim boundaries.
 * - Interactive dual-handle timeline trimming bar with live scrub preview.
 * - Lossless, ultra-fast stream-copy video trimming engine with MediaExtractor & MediaMuxer.
 * - Public MediaStore export to Movies/TrimmedVideos directory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoEditorScreen(
    viewModel: VideoEditorViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Safe Scoped Storage Media Picker (No dangerous storage permissions needed!)
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.loadVideo(uri)
        }
    }

    // React to error and info messages
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearErrorMessage()
        }
    }

    LaunchedEffect(uiState.infoMessage) {
        uiState.infoMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearInfoMessage()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF090D16)),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCut,
                            contentDescription = null,
                            tint = Color(0xFF06B6D4),
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "Video Editor",
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp,
                            color = Color.White
                        )
                    }
                },
                navigationIcon = {
                    if (uiState.videoClip != null) {
                        IconButton(
                            onClick = {
                                mediaPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            },
                            modifier = Modifier.testTag("change_video_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.VideoLibrary,
                                contentDescription = "Pick Different Video",
                                tint = Color.LightGray
                            )
                        }
                    }
                },
                actions = {
                    if (uiState.videoClip != null) {
                        Button(
                            onClick = { viewModel.startTrimExport() },
                            enabled = uiState.exportState !is TrimExportState.Processing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF06B6D4),
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .testTag("export_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCut,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Export",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF0F172A),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF090D16)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .navigationBarsPadding()
        ) {
            val clip = uiState.videoClip
            if (clip == null) {
                // Empty state landing screen
                EmptyLandingView(
                    isGeneratingDemo = uiState.isGeneratingDemo,
                    onPickVideo = {
                        mediaPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                    onTryDemo = {
                        viewModel.generateAndLoadDemoVideo()
                    }
                )
            } else {
                // Main Video Editor Workspace
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Video Player Preview Area
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .background(Color.Black)
                    ) {
                        VideoPlayerView(
                            videoUri = clip.uri,
                            isPlaying = uiState.isPlaying,
                            trimRange = uiState.trimRange,
                            isLooping = uiState.isLooping,
                            targetSeekPositionMs = if (uiState.isScrubbing) uiState.playbackPositionMs else null,
                            onPlaybackPositionUpdate = { pos ->
                                viewModel.onPlaybackPositionChanged(pos)
                            },
                            onPlayPauseToggle = {
                                viewModel.togglePlayPause()
                            }
                        )
                    }

                    // Video Metadata Summary Chips
                    VideoMetadataBar(
                        clip = clip,
                        trimRange = uiState.trimRange,
                        isLooping = uiState.isLooping,
                        onToggleLoop = { viewModel.toggleLooping() }
                    )

                    // Trimming Timeline & Controls Section
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1626)),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Movie,
                                        contentDescription = null,
                                        tint = Color(0xFF06B6D4),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Timeline & Trim Range",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = Color.White
                                    )
                                }

                                Surface(
                                    color = Color(0xFF06B6D4).copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(6.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = "Selected: ${uiState.trimRange.formattedDuration}",
                                        color = Color(0xFF38BDF8),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            // The Dual-Handle Visual Trim Bar
                            TrimBar(
                                totalDurationMs = clip.durationMs,
                                trimRange = uiState.trimRange,
                                playbackPositionMs = uiState.playbackPositionMs,
                                thumbnails = uiState.timelineThumbnails,
                                isExtractingThumbnails = uiState.isExtractingThumbnails,
                                scrubPreviewFrame = uiState.scrubPreviewFrame,
                                isScrubbing = uiState.isScrubbing,
                                onTrimRangeChange = { start, end ->
                                    viewModel.updateTrimRange(start, end)
                                },
                                onScrub = { timeMs ->
                                    viewModel.onScrubbing(timeMs)
                                },
                                onScrubFinished = {
                                    viewModel.onScrubbingFinished()
                                },
                                onAdjustStart = { delta ->
                                    viewModel.adjustStartTime(delta)
                                },
                                onAdjustEnd = { delta ->
                                    viewModel.adjustEndTime(delta)
                                },
                                onResetTrim = {
                                    viewModel.updateTrimRange(0L, clip.durationMs)
                                }
                            )
                        }
                    }

                    // Technical Info & Architecture Note
                    TechnicalInfoCard(clip = clip)

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // Export In-Progress Dialog
            val exportState = uiState.exportState
            if (exportState is TrimExportState.Processing) {
                ExportProgressDialog(
                    progress = exportState.progress,
                    status = exportState.statusMessage,
                    onCancel = { viewModel.cancelExport() }
                )
            }

            // Export Success Dialog
            if (exportState is TrimExportState.Success) {
                ExportSuccessDialog(
                    success = exportState,
                    onOpenVideo = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(exportState.outputUri, "video/mp4")
                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        }
                        context.startActivity(Intent.createChooser(intent, "Play trimmed video"))
                    },
                    onShareVideo = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, exportState.outputUri)
                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share trimmed video"))
                    },
                    onDismiss = { viewModel.dismissExportResult() }
                )
            }

            // Export Error Dialog
            if (exportState is TrimExportState.Error) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissExportResult() },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(36.dp)
                        )
                    },
                    title = { Text("Export Failed", color = Color.White) },
                    text = { Text(exportState.message, color = Color.LightGray) },
                    confirmButton = {
                        Button(onClick = { viewModel.dismissExportResult() }) {
                            Text("OK")
                        }
                    },
                    containerColor = Color(0xFF1E293B)
                )
            }
        }
    }
}

/**
 * Empty state shown before a video is loaded.
 */
@Composable
private fun EmptyLandingView(
    isGeneratingDemo: Boolean,
    onPickVideo: () -> Unit,
    onTryDemo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Hero visual badge
        Box(
            modifier = Modifier
                .size(110.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF06B6D4), Color(0xFF4F46E5).copy(alpha = 0.4f), Color.Transparent)
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xFF0F172A),
                border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF06B6D4)),
                modifier = Modifier.size(76.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.ContentCut,
                        contentDescription = null,
                        tint = Color(0xFF06B6D4),
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Fast & Lossless Video Trimmer",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Select any video from your gallery or try our hardware-encoded sample to test frame-accurate trimming and instant MediaStore export.",
            fontSize = 14.sp,
            color = Color(0xFF94A3B8),
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Pick video button (Primary Action)
        Button(
            onClick = onPickVideo,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("pick_video_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF06B6D4),
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(imageVector = Icons.Default.VideoLibrary, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Select Video from Gallery",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Demo video button (Secondary Action for zero-file environments)
        OutlinedButton(
            onClick = onTryDemo,
            enabled = !isGeneratingDemo,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("generate_demo_button"),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color(0xFF38BDF8)
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.6f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (isGeneratingDemo) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF38BDF8)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Synthesizing Sample Video...")
            } else {
                Icon(imageVector = Icons.Default.PlayCircleOutline, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Generate & Try Demo Video",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Feature cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FeaturePill(
                icon = Icons.Default.Speed,
                title = "Lossless Stream-Copy",
                subtitle = "Sub-second trimming without re-encoding",
                modifier = Modifier.weight(1f)
            )
            FeaturePill(
                icon = Icons.Default.FolderOpen,
                title = "Scoped Storage",
                subtitle = "Saves directly into Movies gallery",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FeaturePill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF131C31),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF06B6D4),
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = Color(0xFF94A3B8),
                lineHeight = 15.sp
            )
        }
    }
}

/**
 * Metadata chips bar showing resolution, file size, fps, and loop toggle.
 */
@Composable
private fun VideoMetadataBar(
    clip: com.example.domain.model.VideoClip,
    trimRange: TrimRange,
    isLooping: Boolean,
    onToggleLoop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        InfoChip(text = clip.resolutionLabel)
        InfoChip(text = clip.formattedSize)
        InfoChip(text = "${clip.frameRate.toInt()} fps")

        Spacer(modifier = Modifier.weight(1f))

        // Loop Mode Toggle Chip
        Surface(
            onClick = onToggleLoop,
            shape = RoundedCornerShape(8.dp),
            color = if (isLooping) Color(0xFF06B6D4).copy(alpha = 0.2f) else Color(0xFF1E293B),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isLooping) Color(0xFF06B6D4) else Color(0xFF334155)
            ),
            modifier = Modifier.testTag("loop_toggle_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Replay,
                    contentDescription = null,
                    tint = if (isLooping) Color(0xFF06B6D4) else Color.LightGray,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = if (isLooping) "Loop: ON" else "Loop: OFF",
                    fontSize = 11.sp,
                    color = if (isLooping) Color(0xFF06B6D4) else Color.LightGray,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(
        color = Color(0xFF131C31),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = Color(0xFF94A3B8),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * Information card displaying technical architectural details.
 */
@Composable
private fun TechnicalInfoCard(
    clip: com.example.domain.model.VideoClip,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220)),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "ENGINE SPECIFICATIONS",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF06B6D4),
                letterSpacing = 1.sp
            )
            Text(
                text = "• Container: MPEG-4 Demux & Remux via MediaExtractor + MediaMuxer",
                fontSize = 11.sp,
                color = Color(0xFF94A3B8)
            )
            Text(
                text = "• Audio/Video Sync: Monotonic PTS rebasing with Keyframe alignment",
                fontSize = 11.sp,
                color = Color(0xFF94A3B8)
            )
            Text(
                text = "• Storage Policy: Android Scoped Storage to MediaStore (Movies/TrimmedVideos)",
                fontSize = 11.sp,
                color = Color(0xFF94A3B8)
            )
        }
    }
}

/**
 * Dialog displaying video trimming progress from 0% to 100%.
 */
@Composable
private fun ExportProgressDialog(
    progress: Float,
    status: String,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = { /* Prevent dismiss on outside tap */ }) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF0F172A),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.5f)),
            shadowElevation = 16.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Processing Video",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    text = status,
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center
                )

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = Color(0xFF06B6D4),
                    trackColor = Color(0xFF1E293B)
                )

                Text(
                    text = "${(progress * 100).toInt()}%",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF38BDF8)
                )

                OutlinedButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Cancel Export")
                }
            }
        }
    }
}

/**
 * Dialog celebrating completed export with open/share options.
 */
@Composable
private fun ExportSuccessDialog(
    success: TrimExportState.Success,
    onOpenVideo: () -> Unit,
    onShareVideo: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF0F172A),
            border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF10B981)),
            shadowElevation = 20.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(54.dp)
                )

                Text(
                    text = "Export Completed!",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Surface(
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = success.fileName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Saved to: ${success.savedLocation}",
                            fontSize = 11.sp,
                            color = Color(0xFF38BDF8),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Duration: ${com.example.domain.model.VideoClip.formatMillisecondsWithFractions(success.durationMs)}",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onOpenVideo,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Open")
                    }

                    OutlinedButton(
                        onClick = onShareVideo,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share")
                    }
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Done")
                }
            }
        }
    }
}
