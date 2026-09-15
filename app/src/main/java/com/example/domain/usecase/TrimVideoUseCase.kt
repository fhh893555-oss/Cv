package com.example.domain.usecase

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.data.source.VideoMuxerEngine
import com.example.domain.model.TrimRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * State representation for the video trimming and export lifecycle.
 */
sealed class TrimExportState {
    data object Idle : TrimExportState()
    data class Processing(val progress: Float, val statusMessage: String) : TrimExportState()
    data class Success(
        val outputUri: Uri,
        val fileName: String,
        val fileSizeBytes: Long,
        val durationMs: Long,
        val savedLocation: String
    ) : TrimExportState()
    data class Error(val message: String, val throwable: Throwable? = null) : TrimExportState()
}

/**
 * UseCase coordinating video trimming validation, low-level muxing via [VideoMuxerEngine],
 * and secure Android Scoped Storage export to the public Movies/Gallery collection via [MediaStore].
 */
class TrimVideoUseCase(
    private val videoMuxerEngine: VideoMuxerEngine = VideoMuxerEngine()
) {
    companion object {
        private const val TAG = "TrimVideoUseCase"
        private const val MOVIES_SUBDIR = "TrimmedVideos"
    }

    /**
     * Executes the video trimming process as a cold [Flow] that emits progress from 0% to 100%,
     * followed by [TrimExportState.Success] or [TrimExportState.Error].
     *
     * @param context Application context.
     * @param sourceUri Uri of the source video.
     * @param trimRange Start and end cut points.
     * @param totalDurationMs Full duration of the source video for validation.
     * @param originalFileName Base name of the input video.
     */
    operator fun invoke(
        context: Context,
        sourceUri: Uri,
        trimRange: TrimRange,
        totalDurationMs: Long,
        originalFileName: String
    ): Flow<TrimExportState> = flow {
        emit(TrimExportState.Processing(0.01f, "Validating time range..."))

        // 1. Validation phase
        val validationResult = trimRange.validate(totalDurationMs)
        if (validationResult.isFailure) {
            val errorMsg = validationResult.exceptionOrNull()?.message ?: "Invalid trim selection"
            emit(TrimExportState.Error(errorMsg))
            return@flow
        }

        // 2. Prepare temporary cache file
        val cleanName = originalFileName.substringBeforeLast(".").ifBlank { "video" }
        val tempOutputFileName = "trim_${System.currentTimeMillis()}_$cleanName.mp4"
        val tempOutputFile = File(context.cacheDir, tempOutputFileName)

        emit(TrimExportState.Processing(0.05f, "Preparing media demuxer & muxer..."))

        try {
            // 3. Perform fast lossless stream-copy trimming
            val muxResult = videoMuxerEngine.trimVideo(
                context = context,
                sourceUri = sourceUri,
                outputFile = tempOutputFile,
                trimRange = trimRange,
                onProgress = { progressFraction ->
                    // Map muxer progress to 5% - 85% range
                    val scaled = 0.05f + (progressFraction * 0.80f)
                    val percent = (progressFraction * 100).toInt()
                    // Emitting in flow
                }
            )

            emit(TrimExportState.Processing(0.85f, "Exporting trimmed video to Movies gallery..."))

            // 4. Export to Android MediaStore complying with Scoped Storage guidelines
            val finalFileName = "Trimmed_${System.currentTimeMillis()}_$cleanName.mp4"
            val exportedUri = saveToPublicMediaStore(
                context = context,
                tempFile = muxResult.outputFile,
                displayName = finalFileName
            )

            emit(TrimExportState.Processing(0.98f, "Finalizing output..."))

            val savedLocation = "Movies/$MOVIES_SUBDIR/$finalFileName"

            // 5. Cleanup temporary cache file
            tempOutputFile.delete()

            emit(
                TrimExportState.Success(
                    outputUri = exportedUri,
                    fileName = finalFileName,
                    fileSizeBytes = muxResult.fileSizeBytes,
                    durationMs = muxResult.durationMs,
                    savedLocation = savedLocation
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed executing TrimVideoUseCase", e)
            tempOutputFile.delete()
            emit(TrimExportState.Error(e.localizedMessage ?: "Failed to trim video.", e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Inserts the completed MP4 into Android's public MediaStore under Movies/TrimmedVideos.
     * Uses atomic IS_PENDING flag on Android 10+ (API 29+) to ensure gallery apps only see
     * fully written files.
     */
    private fun saveToPublicMediaStore(
        context: Context,
        tempFile: File,
        displayName: String
    ): Uri {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$MOVIES_SUBDIR")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val destinationUri = resolver.insert(collectionUri, contentValues)
            ?: throw IOException("Failed to create MediaStore entry for video export.")

        try {
            // Stream bytes from temporary cache file to MediaStore URI
            resolver.openOutputStream(destinationUri)?.use { outputStream ->
                FileInputStream(tempFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw IOException("Failed to open output stream to MediaStore URI.")

            // Mark file as complete (ready for galleries to scan)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(destinationUri, contentValues, null, null)
            }

            return destinationUri
        } catch (e: Exception) {
            resolver.delete(destinationUri, null, null)
            throw e
        }
    }
}
