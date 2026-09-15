package com.example.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.source.VideoMuxerEngine
import com.example.domain.model.TrimRange
import com.example.domain.model.VideoClip
import com.example.domain.usecase.TrimExportState
import com.example.domain.usecase.TrimVideoUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Complete UI state for the Video Editor screen.
 */
data class VideoEditorUiState(
    val videoClip: VideoClip? = null,
    val trimRange: TrimRange = TrimRange(0, 0),
    val isPlaying: Boolean = false,
    val playbackPositionMs: Long = 0L,
    val isLooping: Boolean = true,
    val timelineThumbnails: List<Bitmap> = emptyList(),
    val isExtractingThumbnails: Boolean = false,
    val scrubPreviewFrame: Bitmap? = null,
    val isScrubbing: Boolean = false,
    val exportState: TrimExportState = TrimExportState.Idle,
    val isGeneratingDemo: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

/**
 * ViewModel managing the state of video loading, player coordination,
 * dual-handle timeline trimming, thumbnail generation, and background export.
 */
class VideoEditorViewModel(
    application: Application
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "VideoEditorViewModel"
        private const val MIN_TRIM_DURATION_MS = 500L
    }

    private val videoMuxerEngine = VideoMuxerEngine()
    private val trimVideoUseCase = TrimVideoUseCase(videoMuxerEngine)

    private val _uiState = MutableStateFlow(VideoEditorUiState())
    val uiState: StateFlow<VideoEditorUiState> = _uiState.asStateFlow()

    private var thumbnailJob: Job? = null
    private var scrubJob: Job? = null
    private var exportJob: Job? = null

    /**
     * Loads a video selected by the user via Android Photo/Media picker.
     */
    fun loadVideo(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isPlaying = false, errorMessage = null) }
                val context = getApplication<Application>()
                val clip = videoMuxerEngine.extractMetadata(context, uri)

                if (clip.durationMs <= 0) {
                    _uiState.update {
                        it.copy(errorMessage = "Could not determine video duration. The format may be unsupported.")
                    }
                    return@launch
                }

                val initialRange = TrimRange(startMs = 0L, endMs = clip.durationMs)

                _uiState.update {
                    it.copy(
                        videoClip = clip,
                        trimRange = initialRange,
                        playbackPositionMs = 0L,
                        scrubPreviewFrame = null,
                        timelineThumbnails = emptyList(),
                        isExtractingThumbnails = true,
                        infoMessage = "Imported ${clip.name} (${clip.formattedDuration})"
                    )
                }

                // Load timeline thumbnails asynchronously
                thumbnailJob?.cancel()
                thumbnailJob = launch {
                    val thumbnails = videoMuxerEngine.extractTimelineThumbnails(
                        context = context,
                        videoUri = uri,
                        durationMs = clip.durationMs,
                        thumbnailCount = 12
                    )
                    _uiState.update {
                        it.copy(
                            timelineThumbnails = thumbnails,
                            isExtractingThumbnails = false
                        )
                    }
                }

                // Fetch initial preview frame at 0ms
                fetchPreviewFrame(0L)
            } catch (e: Exception) {
                Log.e(TAG, "Failed loading video: $uri", e)
                _uiState.update {
                    it.copy(
                        errorMessage = "Failed to load video: ${e.localizedMessage}",
                        isExtractingThumbnails = false
                    )
                }
            }
        }
    }

    /**
     * Creates and loads an animated sample demo video directly on the device.
     * Guarantees that users and automated tests can test trimming without requiring existing gallery files.
     */
    fun generateAndLoadDemoVideo() {
        viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingDemo = true, errorMessage = null) }
            try {
                val context = getApplication<Application>()
                val demoFile = File(context.cacheDir, "demo_source_video.mp4")
                videoMuxerEngine.createSampleDemoVideo(context, demoFile)
                loadVideo(Uri.fromFile(demoFile))
                _uiState.update {
                    it.copy(
                        isGeneratingDemo = false,
                        infoMessage = "Demo video generated with MediaCodec! Ready to trim."
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed generating demo video", e)
                _uiState.update {
                    it.copy(
                        isGeneratingDemo = false,
                        errorMessage = "Could not generate demo video: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * Updates the trim range with validation and boundary enforcement.
     */
    fun updateTrimRange(startMs: Long, endMs: Long) {
        val clip = _uiState.value.videoClip ?: return
        val maxDuration = clip.durationMs

        val safeStart = startMs.coerceIn(0L, maxDuration - MIN_TRIM_DURATION_MS)
        val safeEnd = endMs.coerceIn(safeStart + MIN_TRIM_DURATION_MS, maxDuration)

        val newRange = TrimRange(safeStart, safeEnd)
        _uiState.update { it.copy(trimRange = newRange) }
    }

    /**
     * Incrementally adjusts the start timestamp (e.g. +/- 100ms or +/- 1s).
     */
    fun adjustStartTime(deltaMs: Long) {
        val currentRange = _uiState.value.trimRange
        val clip = _uiState.value.videoClip ?: return
        val newStart = (currentRange.startMs + deltaMs).coerceIn(0L, currentRange.endMs - MIN_TRIM_DURATION_MS)
        updateTrimRange(newStart, currentRange.endMs)
        onScrubbing(newStart)
        onScrubbingFinished()
    }

    /**
     * Incrementally adjusts the end timestamp (e.g. +/- 100ms or +/- 1s).
     */
    fun adjustEndTime(deltaMs: Long) {
        val currentRange = _uiState.value.trimRange
        val clip = _uiState.value.videoClip ?: return
        val newEnd = (currentRange.endMs + deltaMs).coerceIn(currentRange.startMs + MIN_TRIM_DURATION_MS, clip.durationMs)
        updateTrimRange(currentRange.startMs, newEnd)
        onScrubbing(newEnd)
        onScrubbingFinished()
    }

    /**
     * Real-time scrub callback triggered when dragging a handle or the playhead needle.
     */
    fun onScrubbing(timeMs: Long) {
        _uiState.update {
            it.copy(
                isScrubbing = true,
                playbackPositionMs = timeMs
            )
        }
        fetchPreviewFrame(timeMs)
    }

    fun onScrubbingFinished() {
        _uiState.update { it.copy(isScrubbing = false) }
    }

    private fun fetchPreviewFrame(timeMs: Long) {
        val uri = _uiState.value.videoClip?.uri ?: return
        scrubJob?.cancel()
        scrubJob = viewModelScope.launch {
            delay(40) // debounce rapid gestures
            val frame = videoMuxerEngine.extractFrameAtTime(getApplication(), uri, timeMs)
            if (frame != null) {
                _uiState.update { it.copy(scrubPreviewFrame = frame) }
            }
        }
    }

    /**
     * Playback progress update from ExoPlayer.
     * Manages auto-looping within the user's active trim boundaries.
     */
    fun onPlaybackPositionChanged(posMs: Long) {
        val state = _uiState.value
        val trimRange = state.trimRange

        // If looping is enabled and position reached or exceeded the end trim marker:
        if (state.isLooping && posMs >= trimRange.endMs && trimRange.endMs > 0) {
            _uiState.update { it.copy(playbackPositionMs = trimRange.startMs) }
        } else {
            _uiState.update { it.copy(playbackPositionMs = posMs) }
        }
    }

    fun setPlaying(playing: Boolean) {
        _uiState.update { it.copy(isPlaying = playing) }
    }

    fun togglePlayPause() {
        _uiState.update { it.copy(isPlaying = !it.isPlaying) }
    }

    fun toggleLooping() {
        _uiState.update { it.copy(isLooping = !it.isLooping) }
    }

    /**
     * Triggers the background video trimming and MediaStore export pipeline.
     */
    fun startTrimExport() {
        val state = _uiState.value
        val clip = state.videoClip ?: return
        val range = state.trimRange

        // Pause playback before beginning export to conserve hardware decoder resources
        _uiState.update { it.copy(isPlaying = false) }

        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            val context = getApplication<Application>()
            trimVideoUseCase(
                context = context,
                sourceUri = clip.uri,
                trimRange = range,
                totalDurationMs = clip.durationMs,
                originalFileName = clip.name
            ).collect { exportState ->
                _uiState.update { it.copy(exportState = exportState) }
            }
        }
    }

    /**
     * Cancels an ongoing export job.
     */
    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _uiState.update { it.copy(exportState = TrimExportState.Idle) }
    }

    fun dismissExportResult() {
        _uiState.update { it.copy(exportState = TrimExportState.Idle) }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearInfoMessage() {
        _uiState.update { it.copy(infoMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        thumbnailJob?.cancel()
        scrubJob?.cancel()
        exportJob?.cancel()
    }
}
