package com.example.domain.model

/**
 * Domain model representing the start and end timestamps (in milliseconds)
 * selected by the user for trimming a video clip.
 */
data class TrimRange(
    val startMs: Long,
    val endMs: Long
) {
    /**
     * Duration of the selected trimmed segment in milliseconds.
     */
    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(0L)

    /**
     * Checks whether the trim boundaries are logically ordered and non-negative.
     */
    val isValid: Boolean
        get() = startMs >= 0 && endMs > startMs

    /**
     * Validates the trim range against the total video duration and a minimum threshold.
     */
    fun validate(totalDurationMs: Long, minDurationMs: Long = 500L): Result<Unit> {
        if (startMs < 0) {
            return Result.failure(IllegalArgumentException("Start time cannot be negative ($startMs ms)"))
        }
        if (endMs <= startMs) {
            return Result.failure(IllegalArgumentException("End time ($endMs ms) must be greater than start time ($startMs ms)"))
        }
        if (totalDurationMs > 0 && endMs > totalDurationMs + 100) { // allow small margin
            return Result.failure(IllegalArgumentException("End time ($endMs ms) exceeds video duration ($totalDurationMs ms)"))
        }
        if (durationMs < minDurationMs) {
            return Result.failure(IllegalArgumentException("Trimmed duration ($durationMs ms) must be at least ${minDurationMs}ms"))
        }
        return Result.success(Unit)
    }

    val formattedStart: String
        get() = VideoClip.formatMillisecondsWithFractions(startMs)

    val formattedEnd: String
        get() = VideoClip.formatMillisecondsWithFractions(endMs)

    val formattedDuration: String
        get() = VideoClip.formatMillisecondsWithFractions(durationMs)
}
