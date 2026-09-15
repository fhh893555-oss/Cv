package com.example.domain.model

import android.net.Uri

/**
 * Domain model representing an imported video clip and its technical metadata.
 */
data class VideoClip(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val mimeType: String,
    val frameRate: Float = 30f,
    val bitrate: Long = 0L,
    val rotation: Int = 0
) {
    /**
     * Formats the duration into mm:ss or hh:mm:ss.
     */
    val formattedDuration: String
        get() = formatMilliseconds(durationMs)

    /**
     * Human-readable file size string (e.g., "15.4 MB").
     */
    val formattedSize: String
        get() {
            if (sizeBytes <= 0) return "Unknown size"
            val kb = sizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                else -> String.format("%.1f KB", kb)
            }
        }

    /**
     * Display label for resolution (e.g. "1080p (1920x1080)").
     */
    val resolutionLabel: String
        get() {
            if (width <= 0 || height <= 0) return "Unknown resolution"
            val effectiveWidth = if (rotation == 90 || rotation == 270) height else width
            val effectiveHeight = if (rotation == 90 || rotation == 270) width else height
            val label = when (effectiveHeight) {
                2160 -> "4K UHD"
                1440 -> "2K QHD"
                1080 -> "1080p FHD"
                720 -> "720p HD"
                480 -> "480p SD"
                else -> "${effectiveHeight}p"
            }
            return "$label (${effectiveWidth}×${effectiveHeight})"
        }

    companion object {
        fun formatMilliseconds(ms: Long): String {
            val totalSeconds = (ms / 1000).coerceAtLeast(0)
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }

        fun formatMillisecondsWithFractions(ms: Long): String {
            val totalSeconds = (ms / 1000).coerceAtLeast(0)
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            val millis = (ms % 1000).coerceAtLeast(0)
            return String.format("%02d:%02d.%03d", minutes, seconds, millis)
        }
    }
}
