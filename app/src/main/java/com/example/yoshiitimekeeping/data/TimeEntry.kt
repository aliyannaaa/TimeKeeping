package com.example.yoshiitimekeeping.data

import java.util.*

/**
 * Data class representing a time clock entry with status tracking.
 * Optimized for constraint-based indexing and efficient querying.
 */
data class TimeEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val entryType: EntryType,
    val status: EntryStatus,
    val employeeId: String = "",
    //val location: String = "",
    //val ipAddress: String = "",
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val synced: Boolean = false,
    val syncedAt: Long? = null
) {
    enum class EntryType {
        TIME_IN, TIME_OUT
    }

    enum class EntryStatus {
        SUCCESS, FAILED, TIMEOUT
    }

    /**
     * Get constraints for indexing and validation
     */
    fun getConstraints(): Map<String, Any> = mapOf(
        "employeeId" to employeeId,
        "date" to (timestamp / 86400000), // Day index
        "type" to entryType.name,
        "status" to status.name
    )

    /**
     * Validate this entry against business rules
     */
    fun validate(): ValidationResult {
        return when {
            employeeId.isBlank() -> ValidationResult(false, "Employee ID is required")
            //location.isBlank() -> ValidationResult(false, "Location is required")
            retryCount > 5 -> ValidationResult(false, "Maximum retry attempts exceeded")
            status == EntryStatus.TIMEOUT -> ValidationResult(false, "Entry timed out")
            else -> ValidationResult(true, "Valid entry")
        }
    }
}

data class ValidationResult(
    val isValid: Boolean,
    val message: String
)
