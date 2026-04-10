package com.example.yoshiitimekeeping.database

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Database operation result wrapper
 * Provides convenient methods for handling success/failure cases
 */
sealed class DbResult<out T> {
    data class Success<T>(val data: T) : DbResult<T>()
    data class Error(val exception: Throwable) : DbResult<Nothing>()
    
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }
    
    fun exceptionOrNull(): Throwable? = when (this) {
        is Success -> null
        is Error -> exception
    }
}

/**
 * Extension function to convert Kotlin Result to DbResult
 */
fun <T> Result<T>.asDbResult(): DbResult<T> {
    return if (isSuccess) {
        DbResult.Success(getOrNull()!!)
    } else {
        DbResult.Error(exceptionOrNull()!!)
    }
}

/**
 * Time utility functions for database operations
 */
object TimeUtils {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }
    
    /**
     * Get start of today (00:00:00)
     */
    fun getStartOfDay(timestamp: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
    
    /**
     * Get end of day (23:59:59)
     */
    fun getEndOfDay(timestamp: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        return calendar.timeInMillis
    }
    
    /**
     * Format timestamp to readable date string
     */
    fun formatTimestamp(timestamp: Long): String {
        return dateFormat.format(Date(timestamp))
    }
    
    /**
     * Format duration in milliseconds to HH:MM:SS
     */
    fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
    
    /**
     * Convert hours (as Double) to HH:MM format
     */
    fun formatHours(hours: Double): String {
        val h = hours.toInt()
        val m = ((hours - h) * 60).toInt()
        return String.format("%02d:%02d", h, m)
    }
    
    /**
     * Get start of week (Monday)
     */
    fun getStartOfWeek(timestamp: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
    
    /**
     * Get end of week (Sunday)
     */
    fun getEndOfWeek(timestamp: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        return calendar.timeInMillis
    }
}

/**
 * Helper functions for common database queries
 */
object DbHelpers {
    
    /**
     * Calculate working hours between two timestamps.
     */
    fun calculateHours(startMs: Long, endMs: Long?): Double {
        if (endMs == null) return 0.0
        val durationMs = endMs - startMs
        return durationMs / (1000.0 * 60.0 * 60.0) // Convert to hours
    }
    
    /**
     * Check if two time records overlap (conflict detection)
     */
    fun recordsOverlap(
        record1Start: Long, record1End: Long?,
        record2Start: Long, record2End: Long?
    ): Boolean {
        val end1 = record1End ?: Long.MAX_VALUE
        val end2 = record2End ?: Long.MAX_VALUE
        
        return !(end1 < record2Start || end2 < record1Start)
    }
    
    /**
     * Validate time record constraints
     */
    fun validateTimeRecord(
        userId: Int,
        timeIn: Long,
        timeOut: Long? = null
    ): Pair<Boolean, String> {
        return when {
            userId <= 0 -> Pair(false, "Invalid user ID")
            timeIn <= 0 -> Pair(false, "Time in must be positive")
            timeOut != null && timeOut <= timeIn -> Pair(false, "Time out must be after time in")
            timeOut != null && (timeOut - timeIn) < 60000 -> Pair(false, "Minimum session duration is 1 minute")
            else -> Pair(true, "Valid")
        }
    }
}

/**
 * Extension function for TimeInOut to get elapsed time
 */
fun TimeInOut.getElapsedTime(): Long {
    return if (entry_type == 1 || entry_type == 3) {
        val startTimeMs = entry_time_ms ?: return 0L
        System.currentTimeMillis() - startTimeMs
    } else {
        0L
    }
}

/**
 * Extension function for TimeInOut to get elapsed hours
 */
fun TimeInOut.getElapsedHours(): Double {
    return getElapsedTime() / (1000.0 * 60.0 * 60.0)
}

/**
 * Extension function for TimeInOut to get formatted duration
 */
fun TimeInOut.getFormattedDuration(): String {
    return TimeUtils.formatDuration(getElapsedTime())
}

/**
 * Extension function for TimeInOut to check if it's an active record
 */
fun TimeInOut.isActive(): Boolean {
    return entry_type == 1 || entry_type == 3
}

/**
 * Build a lightweight constraint map for request-level validation.
 * Database constraints are still enforced in MariaDB/MySQL.
 */
fun TimeInOut.getConstraintMap(): Map<String, Any> {
    return mapOf<String, Any>(
        "id" to (id ?: ""),
        "user_id" to user_id,
        "entry_time_ms" to (entry_time_ms ?: 0L),
        "entry_type" to entry_type
    )
}
