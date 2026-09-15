package com.example.data.source

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.domain.model.TrimRange
import com.example.domain.model.VideoClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Result returned by [VideoMuxerEngine] on successful trim execution.
 */
data class MuxerTrimResult(
    val outputFile: File,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val actualStartMs: Long,
    val actualEndMs: Long
)

/**
 * High-performance Video Trimming and Muxing Engine.
 *
 * Utilizes Android's low-level media architecture:
 * - [MediaExtractor]: Parses container formats (MP4, MKV, etc.), demuxes elementary streams,
 *   and seeks accurately to sync keyframes (I-frames).
 * - [MediaMuxer]: Remuxes raw encoded elementary streams into an MPEG-4 container without
 *   lossy re-encoding, preserving original bitrate and visual fidelity while achieving near-instant
 *   stream-copy speeds.
 * - [MediaMetadataRetriever]: Extracts video properties, frame rates, and visual thumbnails.
 * - Coroutine-aware: Supports non-blocking background execution, cooperative cancellation, and
 *   fine-grained progress callbacks.
 */
class VideoMuxerEngine {

    companion object {
        private const val TAG = "VideoMuxerEngine"
        private const val DEFAULT_BUFFER_SIZE = 2 * 1024 * 1024 // 2MB fallback buffer
    }

    /**
     * Trims the source video clip within [trimRange] and outputs to [outputFile].
     *
     * Execution Steps:
     * 1. Safely opens the content [sourceUri] via [android.os.ParcelFileDescriptor].
     * 2. Configures [MediaExtractor] and locates video & audio elementary tracks.
     * 3. Seeks to the nearest keyframe preceding [TrimRange.startMs] using [MediaExtractor.SEEK_TO_PREVIOUS_SYNC].
     * 4. Calculates the actual video start timestamp (to maintain A/V sync and continuous PTS).
     * 5. Configures [MediaMuxer] with track formats and original video rotation metadata.
     * 6. Iterates over demuxed packets up to [TrimRange.endMs], rebases Presentation Time Stamps (PTS)
     *    to begin at 0, writes to muxer, and emits progress.
     * 7. Gracefully releases all hardware decoders, extractors, and file handles.
     *
     * @param context Application or activity context.
     * @param sourceUri Uri of the source video clip.
     * @param outputFile Local file destination where the trimmed MP4 will be muxed.
     * @param trimRange Desired start and end timestamps in milliseconds.
     * @param onProgress Callback receiving progress value between 0.0f and 1.0f.
     */
    suspend fun trimVideo(
        context: Context,
        sourceUri: Uri,
        outputFile: File,
        trimRange: TrimRange,
        onProgress: (Float) -> Unit
    ): MuxerTrimResult = withContext(Dispatchers.IO) {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var pfd: android.os.ParcelFileDescriptor? = null

        try {
            // Open the file descriptor securely with read-only permissions
            pfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                ?: throw IOException("Unable to open source video file descriptor for URI: $sourceUri")

            extractor = MediaExtractor()
            extractor.setDataSource(pfd.fileDescriptor)

            val trackCount = extractor.trackCount
            if (trackCount <= 0) {
                throw IllegalStateException("The selected media file does not contain any readable tracks.")
            }

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            // Detect video and audio tracks
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrackIndex == -1) {
                    videoTrackIndex = i
                    videoFormat = format
                } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                    audioTrackIndex = i
                    audioFormat = format
                }
            }

            if (videoTrackIndex == -1 || videoFormat == null) {
                throw IllegalStateException("No supported video track found in the source media file.")
            }

            // Retrieve video rotation to ensure correct orientation metadata is written into MP4
            val rotation = getVideoRotation(context, sourceUri, videoFormat)

            // Calculate start and end times in microseconds (MediaExtractor uses microseconds)
            val requestedStartUs = trimRange.startMs * 1000L
            val requestedEndUs = trimRange.endMs * 1000L
            val targetDurationUs = requestedEndUs - requestedStartUs

            // Seek video track to previous keyframe (sync frame)
            // H.264/HEVC stream-copy requires starting on an I-frame (keyframe) to prevent visual artifacts
            extractor.selectTrack(videoTrackIndex)
            extractor.seekTo(requestedStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val actualVideoStartUs = extractor.sampleTime.coerceAtLeast(0L)
            extractor.unselectTrack(videoTrackIndex)

            // Re-select all active tracks for interleaved extraction
            extractor.selectTrack(videoTrackIndex)
            val hasAudio = audioTrackIndex >= 0 && audioFormat != null
            if (hasAudio) {
                extractor.selectTrack(audioTrackIndex)
            }

            // Seek again now that both tracks are selected
            extractor.seekTo(actualVideoStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            // Ensure destination file parent directory exists
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) {
                outputFile.delete()
            }

            // Initialize MediaMuxer for standard MPEG-4 container
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotation > 0) {
                muxer.setOrientationHint(rotation)
            }

            // Register tracks with the muxer
            val muxerVideoTrackIndex = muxer.addTrack(videoFormat)
            val muxerAudioTrackIndex = if (hasAudio) muxer.addTrack(audioFormat!!) else -1

            muxer.start()

            // Calculate buffer sizes based on track format specifications with safe fallback
            val videoMaxInput = try {
                videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } catch (e: Exception) {
                DEFAULT_BUFFER_SIZE
            }.coerceAtLeast(DEFAULT_BUFFER_SIZE)

            val audioMaxInput = if (hasAudio) {
                try {
                    audioFormat!!.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                } catch (e: Exception) {
                    256 * 1024
                }.coerceAtLeast(256 * 1024)
            } else {
                256 * 1024
            }

            val bufferSize = maxOf(videoMaxInput, audioMaxInput)
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            // Keep track of presentation timestamps per track to maintain strictly increasing PTS
            var lastVideoPtsUs = -1L
            var lastAudioPtsUs = -1L
            var videoWrittenSamples = 0
            var audioWrittenSamples = 0
            var lastReportedProgress = 0f

            // Start demuxing and remuxing loop
            while (true) {
                // Check for coroutine cancellation
                currentCoroutineContext().ensureActive()

                val sampleTrackIndex = extractor.sampleTrackIndex
                if (sampleTrackIndex < 0) {
                    // End of stream reached
                    break
                }

                val sampleTimeUs = extractor.sampleTime
                val sampleFlags = extractor.sampleFlags

                // Check if we passed the user's requested trim end time for the video track
                if (sampleTrackIndex == videoTrackIndex && sampleTimeUs > requestedEndUs) {
                    // Video has completed the selected trim range
                    break
                }

                // Discard samples occurring before the actual keyframe start point
                if (sampleTimeUs < actualVideoStartUs) {
                    extractor.advance()
                    continue
                }

                // Discard audio samples beyond trim range
                if (sampleTrackIndex == audioTrackIndex && sampleTimeUs > requestedEndUs) {
                    extractor.advance()
                    continue
                }

                // Read sample payload into memory buffer
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    break
                }

                // Calculate re-based presentation time stamp starting at 0
                val rebasedPtsUs = (sampleTimeUs - actualVideoStartUs).coerceAtLeast(0L)

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.flags = sampleFlags

                // Write packet to muxer based on track type
                if (sampleTrackIndex == videoTrackIndex) {
                    // Ensure monotonic PTS to comply with strict MP4 container specs
                    val pts = if (rebasedPtsUs > lastVideoPtsUs) rebasedPtsUs else lastVideoPtsUs + 1000L
                    bufferInfo.presentationTimeUs = pts
                    lastVideoPtsUs = pts

                    muxer.writeSampleData(muxerVideoTrackIndex, buffer, bufferInfo)
                    videoWrittenSamples++

                    // Calculate and report progress
                    if (targetDurationUs > 0) {
                        val progress = ((sampleTimeUs - actualVideoStartUs).toFloat() / targetDurationUs.toFloat())
                            .coerceIn(0f, 1f)
                        if (progress - lastReportedProgress >= 0.01f || progress >= 0.99f) {
                            lastReportedProgress = progress
                            onProgress(progress)
                        }
                    }
                } else if (sampleTrackIndex == audioTrackIndex && muxerAudioTrackIndex >= 0) {
                    val pts = if (rebasedPtsUs > lastAudioPtsUs) rebasedPtsUs else lastAudioPtsUs + 500L
                    bufferInfo.presentationTimeUs = pts
                    lastAudioPtsUs = pts

                    muxer.writeSampleData(muxerAudioTrackIndex, buffer, bufferInfo)
                    audioWrittenSamples++
                }

                extractor.advance()
            }

            Log.d(TAG, "Muxing complete: wrote $videoWrittenSamples video frames, $audioWrittenSamples audio frames")

            // Complete progress notification
            onProgress(1.0f)

            // Validate that we actually wrote video samples
            if (videoWrittenSamples == 0) {
                throw IllegalStateException("No video frames were written during trimming. Please verify the selected time range.")
            }

            val finalDurationMs = (lastVideoPtsUs / 1000L).coerceAtLeast(trimRange.durationMs)
            val fileSize = outputFile.length()

            MuxerTrimResult(
                outputFile = outputFile,
                durationMs = finalDurationMs,
                fileSizeBytes = fileSize,
                actualStartMs = actualVideoStartUs / 1000L,
                actualEndMs = (actualVideoStartUs + lastVideoPtsUs) / 1000L
            )
        } catch (e: MediaCodec.CodecException) {
            Log.e(TAG, "Hardware codec exception during trimming", e)
            outputFile.delete()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error trimming video", e)
            outputFile.delete()
            throw e
        } finally {
            try {
                muxer?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop MediaMuxer gracefully", e)
            }
            try {
                muxer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release MediaMuxer", e)
            }
            try {
                extractor?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release MediaExtractor", e)
            }
            try {
                pfd?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close ParcelFileDescriptor", e)
            }
        }
    }

    /**
     * Extracts technical metadata about a video from its URI.
     */
    suspend fun extractMetadata(context: Context, videoUri: Uri): VideoClip = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L

            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val width = widthStr?.toIntOrNull() ?: 1280

            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val height = heightStr?.toIntOrNull() ?: 720

            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val rotation = rotationStr?.toIntOrNull() ?: 0

            val bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val bitrate = bitrateStr?.toLongOrNull() ?: 0L

            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/mp4"

            // Estimate or read frame rate
            val frameRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val captureRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                captureRate?.toFloatOrNull() ?: 30f
            } else {
                30f
            }

            // Determine file size and display name from ContentResolver
            var name = "video_${System.currentTimeMillis()}.mp4"
            var sizeBytes = 0L

            context.contentResolver.query(videoUri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex != -1) sizeBytes = cursor.getLong(sizeIndex)
                }
            }

            if (sizeBytes == 0L) {
                try {
                    context.contentResolver.openFileDescriptor(videoUri, "r")?.use { pfd ->
                        sizeBytes = pfd.statSize
                    }
                } catch (ignored: Exception) { }
            }

            VideoClip(
                uri = videoUri,
                name = name,
                durationMs = durationMs,
                width = width,
                height = height,
                sizeBytes = sizeBytes,
                mimeType = mimeType,
                frameRate = frameRate,
                bitrate = bitrate,
                rotation = rotation
            )
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) { }
        }
    }

    /**
     * Extracts a series of thumbnail bitmaps distributed uniformly across the video duration
     * to render a visual filmstrip timeline.
     */
    suspend fun extractTimelineThumbnails(
        context: Context,
        videoUri: Uri,
        durationMs: Long,
        thumbnailCount: Int = 10
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        if (durationMs <= 0) return@withContext emptyList()
        val retriever = MediaMetadataRetriever()
        val thumbnails = mutableListOf<Bitmap>()

        try {
            retriever.setDataSource(context, videoUri)
            val intervalMs = durationMs / thumbnailCount.coerceAtLeast(1)

            for (i in 0 until thumbnailCount) {
                currentCoroutineContext().ensureActive()
                val targetTimeUs = (i * intervalMs * 1000L).coerceAtMost(durationMs * 1000L)
                val frame = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            targetTimeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            160,
                            90
                        )
                    } else {
                        retriever.getFrameAtTime(targetTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { original ->
                            Bitmap.createScaledBitmap(original, 160, 90, true)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed extracting thumbnail at $targetTimeUs us", e)
                    null
                }

                if (frame != null) {
                    thumbnails.add(frame)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating timeline thumbnails", e)
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) { }
        }

        thumbnails
    }

    /**
     * Extracts a single frame at the given timestamp for real-time scrub previews.
     */
    suspend fun extractFrameAtTime(
        context: Context,
        videoUri: Uri,
        timeMs: Long
    ): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            val timeUs = timeMs * 1000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    320,
                    180
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)?.let { original ->
                    Bitmap.createScaledBitmap(original, 320, 180, true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed extracting preview frame at $timeMs ms", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) { }
        }
    }

    /**
     * Programmatically generates an elegant animated demo video file using MediaCodec and MediaMuxer.
     * Useful for instant testing on fresh emulators or devices without pre-loaded video files.
     */
    suspend fun createSampleDemoVideo(context: Context, outputFile: File): File = withContext(Dispatchers.IO) {
        val width = 720
        val height = 1280
        val frameRate = 30
        val durationSeconds = 6
        val totalFrames = durationSeconds * frameRate
        val bitRate = 2_500_000

        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val mimeType = "video/avc"
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second between keyframes
        }

        val encoder = MediaCodec.createEncoderByType(mimeType)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 52f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 32f
            textAlign = Paint.Align.CENTER
        }
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

        try {
            for (i in 0 until totalFrames) {
                // Drain encoder
                while (true) {
                    val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, 0)
                    if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (encoderStatus >= 0) {
                        val encodedData = encoder.getOutputBuffer(encoderStatus)
                        if (encodedData != null && muxerStarted) {
                            if (bufferInfo.size > 0) {
                                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                        }
                        encoder.releaseOutputBuffer(encoderStatus, false)
                    } else {
                        break
                    }
                }

                // Render animated frame onto surface canvas
                val canvas: Canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    inputSurface.lockHardwareCanvas()
                } else {
                    inputSurface.lockCanvas(null)
                }

                // Dynamic background gradient simulation
                val progress = i.toFloat() / totalFrames
                val bgHue = (progress * 360f) % 360f
                canvas.drawColor(Color.HSVToColor(floatArrayOf(bgHue, 0.45f, 0.18f)))

                // Animated glowing circle
                val radius = 120f + 40f * Math.sin(i * 0.15).toFloat()
                circlePaint.color = Color.HSVToColor(floatArrayOf((bgHue + 180f) % 360f, 0.8f, 0.95f))
                canvas.drawCircle(width / 2f, height / 2f - 80f, radius, circlePaint)

                // Render frame text
                val currentSeconds = i / frameRate
                val currentFraction = (i % frameRate) * (1000 / frameRate)
                canvas.drawText("Video Editor Demo", width / 2f, height / 2f + 140f, textPaint)
                canvas.drawText(
                    String.format("Time: %02d:%02d.%03d (Frame %d/%d)", currentSeconds / 60, currentSeconds % 60, currentFraction, i + 1, totalFrames),
                    width / 2f,
                    height / 2f + 200f,
                    subPaint
                )
                canvas.drawText("Ready for Precision Trimming", width / 2f, height / 2f + 260f, subPaint)

                inputSurface.unlockCanvasAndPost(canvas)
                Thread.sleep(8)
            }

            // Signal end of stream
            encoder.signalEndOfInputStream()

            // Drain remaining buffers
            var eos = false
            while (!eos) {
                val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                if (encoderStatus >= 0) {
                    val encodedData = encoder.getOutputBuffer(encoderStatus)
                    if (encodedData != null && muxerStarted && bufferInfo.size > 0) {
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        eos = true
                    }
                    encoder.releaseOutputBuffer(encoderStatus, false)
                } else if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                }
            }
        } finally {
            try { encoder.stop() } catch (ignored: Exception) { }
            try { encoder.release() } catch (ignored: Exception) { }
            try { inputSurface.release() } catch (ignored: Exception) { }
            try { if (muxerStarted) muxer.stop() } catch (ignored: Exception) { }
            try { muxer.release() } catch (ignored: Exception) { }
        }

        outputFile
    }

    private fun getVideoRotation(context: Context, videoUri: Uri, videoFormat: MediaFormat): Int {
        var rotation = 0
        if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
            rotation = videoFormat.getInteger(MediaFormat.KEY_ROTATION)
        }
        if (rotation == 0) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, videoUri)
                val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                rotation = rotationStr?.toIntOrNull() ?: 0
            } catch (ignored: Exception) { }
            finally {
                try { retriever.release() } catch (ignored: Exception) { }
            }
        }
        return rotation
    }
}
