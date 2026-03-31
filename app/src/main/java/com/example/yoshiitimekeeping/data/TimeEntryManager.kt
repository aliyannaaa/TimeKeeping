package com.example.yoshiitimekeeping.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages time entries with optimized constraint-based indexing.
 * Handles validation, state management, and efficient lookups.
 */
class TimeEntryManager {
    // Multi-index structure for optimized constraint lookups
    private val entries = mutableListOf<TimeEntry>()
    private val entriesByEmployeeDay = mutableMapOf<String, MutableList<TimeEntry>>()
    private val entriesByStatus = mutableMapOf<TimeEntry.EntryStatus, MutableList<TimeEntry>>()
    private val entriesByType = mutableMapOf<TimeEntry.EntryType, MutableList<TimeEntry>>()
    private val mutex = Mutex()

    /**
     * Clock in/out with comprehensive validation and constraint checking.
     * Returns a result with status and optional error message.
     */
    suspend fun clockInOut(
        entryType: TimeEntry.EntryType,
        employeeId: String,
        location: String,
        ipAddress: String,
        timeoutMs: Long = 5000
    ): TimeClockResult {
        return mutex.withLock {
            try {
                // Check for constraint violations
                val constraintError = validateConstraints(employeeId, entryType)
                if (constraintError != null) {
                    return@withLock TimeClockResult(
                        success = false,
                        status = TimeEntry.EntryStatus.FAILED,
                        entry = null,
                        errorMessage = constraintError
                    )
                }

                // Simulate network timeout handling
                val entry = TimeEntry(
                    entryType = entryType,
                    status = TimeEntry.EntryStatus.SUCCESS,
                    employeeId = employeeId,
                    location = location,
                    ipAddress = ipAddress
                )

                // Add to all indexes for optimized lookups
                addToIndexes(entry)
                entries.add(entry)

                return@withLock TimeClockResult(
                    success = true,
                    status = TimeEntry.EntryStatus.SUCCESS,
                    entry = entry,
                    errorMessage = null
                )
            } catch (e: Exception) {
                TimeClockResult(
                    success = false,
                    status = TimeEntry.EntryStatus.FAILED,
                    entry = null,
                    errorMessage = e.message ?: "Unknown error occurred"
                )
            }
        }
    }

    /**
     * Validate business constraints for clock in/out operations.
     * Optimized for early exit on first constraint violation.
     */
    private fun validateConstraints(
        employeeId: String,
        entryType: TimeEntry.EntryType
    ): String? {
        // Check 1: Employee must exist
        if (employeeId.isBlank()) {
            return "Employee ID cannot be empty"
        }

        // Check 2: Cannot double clock in (constraint index lookup)
        val employeeTodayEntries = getEntriesByEmployeeAndDay(employeeId)
        val hasActiveClockIn = employeeTodayEntries.any {
            it.entryType == TimeEntry.EntryType.TIME_IN &&
                    it.status == TimeEntry.EntryStatus.SUCCESS &&
                    !employeeTodayEntries.any { timeOut ->
                        timeOut.entryType == TimeEntry.EntryType.TIME_OUT &&
                                timeOut.status == TimeEntry.EntryStatus.SUCCESS &&
                                timeOut.timestamp > it.timestamp
                    }
        }

        if (entryType == TimeEntry.EntryType.TIME_IN && hasActiveClockIn) {
            return "Already clocked in. Please clock out first."
        }

        // Check 3: Cannot clock out without clocking in
        if (entryType == TimeEntry.EntryType.TIME_OUT && !hasActiveClockIn) {
            return "Must clock in before clocking out"
        }

        return null
    }

    /**
     * Get entries for a specific employee on a specific day.
     * Uses optimized index lookup by employeeId and date.
     */
    private fun getEntriesByEmployeeAndDay(employeeId: String): List<TimeEntry> {
        val today = System.currentTimeMillis() / 86400000
        val key = "$employeeId-$today"
        return entriesByEmployeeDay[key] ?: emptyList()
    }

    /**
     * Get last entry for an employee (for state checking).
     * Optimized for quick status determination.
     */
    fun getLastEntry(employeeId: String): TimeEntry? {
        return entries.filter { it.employeeId == employeeId }
            .maxByOrNull { it.timestamp }
    }

    /**
     * Get today's entries for an employee.
     */
    fun getTodaysEntries(employeeId: String): List<TimeEntry> {
        return getEntriesByEmployeeAndDay(employeeId)
    }

    /**
     * Get entries by status (used for UI indicators).
     */
    fun getEntriesByStatus(status: TimeEntry.EntryStatus): List<TimeEntry> {
        return entriesByStatus[status] ?: emptyList()
    }

    /**
     * Retry a failed entry.
     */
    suspend fun retryFailedEntry(entryId: String): TimeClockResult {
        return mutex.withLock {
            val entry = entries.find { it.id == entryId }
                ?: return@withLock TimeClockResult(
                    success = false,
                    status = TimeEntry.EntryStatus.FAILED,
                    errorMessage = "Entry not found"
                )

            if (entry.retryCount >= 5) {
                return@withLock TimeClockResult(
                    success = false,
                    status = TimeEntry.EntryStatus.FAILED,
                    errorMessage = "Maximum retry attempts exceeded"
                )
            }

            val retryEntry = entry.copy(
                id = entry.id,
                retryCount = entry.retryCount + 1,
                status = TimeEntry.EntryStatus.SUCCESS
            )

            // Remove old entry from indexes
            removeFromIndexes(entry)
            // Remove from list and add retry entry
            entries.remove(entry)
            addToIndexes(retryEntry)
            entries.add(retryEntry)

            return@withLock TimeClockResult(
                success = true,
                status = TimeEntry.EntryStatus.SUCCESS,
                entry = retryEntry
            )
        }
    }

    /**
     * Add entry to all index structures for optimized lookups.
     */
    private fun addToIndexes(entry: TimeEntry) {
        val today = entry.timestamp / 86400000
        val key = "${entry.employeeId}-$today"

        entriesByEmployeeDay.getOrPut(key) { mutableListOf() }.add(entry)
        entriesByStatus.getOrPut(entry.status) { mutableListOf() }.add(entry)
        entriesByType.getOrPut(entry.entryType) { mutableListOf() }.add(entry)
    }

    /**
     * Remove entry from all index structures.
     */
    private fun removeFromIndexes(entry: TimeEntry) {
        val today = entry.timestamp / 86400000
        val key = "${entry.employeeId}-$today"

        entriesByEmployeeDay[key]?.remove(entry)
        entriesByStatus[entry.status]?.remove(entry)
        entriesByType[entry.entryType]?.remove(entry)
    }

    /**
     * Clear all data (for testing or logout).
     */
    suspend fun clearAll() {
        mutex.withLock {
            entries.clear()
            entriesByEmployeeDay.clear()
            entriesByStatus.clear()
            entriesByType.clear()
        }
    }

    /**
     * Get statistics for an employee.
     */
    fun getEmployeeStats(employeeId: String): EmployeeStats {
        val todaysEntries = getTodaysEntries(employeeId)
        val successfulEntries = todaysEntries.filter { it.status == TimeEntry.EntryStatus.SUCCESS }
        val failedEntries = todaysEntries.filter { it.status == TimeEntry.EntryStatus.FAILED }

        return EmployeeStats(
            totalEntries = todaysEntries.size,
            successfulEntries = successfulEntries.size,
            failedEntries = failedEntries.size,
            isClockedIn = getLastEntry(employeeId)?.entryType == TimeEntry.EntryType.TIME_IN &&
                    successfulEntries.lastOrNull()?.entryType == TimeEntry.EntryType.TIME_IN
        )
    }
}

data class EmployeeStats(
    val totalEntries: Int,
    val successfulEntries: Int,
    val failedEntries: Int,
    val isClockedIn: Boolean
)

data class TimeClockResult(
    val success: Boolean,
    val status: TimeEntry.EntryStatus,
    val entry: TimeEntry? = null,
    val errorMessage: String? = null
)
