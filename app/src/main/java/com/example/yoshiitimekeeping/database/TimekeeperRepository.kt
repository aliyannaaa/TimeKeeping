package com.example.yoshiitimekeeping.database

import retrofit2.HttpException

class TimekeeperRepository(
    private val api: TimekeeperApi
) {

    enum class ClockAction(val wireValue: String) {
        IN("in"),
        OUT("out")
    }

    suspend fun registerUser(
        username: String,
        password: String,
        fullName: String? = null,
        jobTitle: String? = null
    ): Result<Credentials> = runCatching {
        require(username.isNotBlank()) { "Username is required" }
        require(password.length >= 6) { "Password must be at least 6 characters" }

        val cleanedName = fullName?.trim()?.takeIf { it.isNotEmpty() }
        val cleanedJobTitle = jobTitle?.trim()?.takeIf { it.isNotEmpty() }

        api.registerUser(
            Credentials(
                username = username,
                password = password,
                full_name = cleanedName,
                employee_position = cleanedJobTitle
            )
        )
    }.mapFailure()

    suspend fun getUserById(userId: Int): Result<Credentials> = runCatching {
        require(userId > 0) { "Invalid user id" }
        api.getCredentialById(userId)
    }.mapFailure()

    suspend fun getAllUsers(): Result<List<Credentials>> = runCatching {
        api.getAllCredentials()
    }.mapFailure()

    suspend fun logTime(
        userId: Int,
        action: ClockAction,
        eventTime: Long = System.currentTimeMillis(),
        locationTimeIn: String? = null
    ): Result<LogTimeResponse> = runCatching {
        require(userId > 0) { "Invalid user id" }
        require(eventTime > 0) { "Invalid event_time" }
        val cleanedLocation = locationTimeIn?.trim()?.takeIf { it.isNotEmpty() }
        api.logTime(
            LogTimeRequest(
                user_id = userId,
                event_time = eventTime,
                action = action.wireValue,
                location_time_in = cleanedLocation
            )
        )
    }.mapFailure()

    suspend fun clockIn(
        userId: Int,
        timeIn: Long = System.currentTimeMillis(),
        locationTimeIn: String? = null
    ): Result<LogTimeResponse> {
        return logTime(userId, ClockAction.IN, timeIn, locationTimeIn)
    }

    suspend fun clockOut(
        userId: Int,
        timeOut: Long = System.currentTimeMillis(),
        locationTimeIn: String? = null
    ): Result<LogTimeResponse> {
        return logTime(userId, ClockAction.OUT, timeOut, locationTimeIn)
    }

    suspend fun getClockState(userId: Int): Result<ClockStateResponse> = runCatching {
        require(userId > 0) { "Invalid user id" }
        api.getClockState(userId)
    }.mapFailure()

    suspend fun getAllTimeRecords(userId: Int? = null): Result<List<TimeInOut>> = runCatching {
        api.getAllTimeRecords(userId)
    }.mapFailure()

    suspend fun getTimeRecords(userId: Int, startDate: Long, endDate: Long): Result<List<TimeInOut>> = runCatching {
        require(userId > 0) { "Invalid user id" }
        require(startDate <= endDate) { "Invalid date range" }
        api.getAllTimeRecords(userId).filter { record ->
            val eventTimeMs = record.entry_time_ms ?: return@filter false
            eventTimeMs in startDate..endDate
        }
    }.mapFailure()

    suspend fun isUserClockedIn(userId: Int): Result<Boolean> = runCatching {
        require(userId > 0) { "Invalid user id" }
        api.getClockState(userId).clocked_in
    }.mapFailure()

    suspend fun getPendingClockOutCount(userId: Int): Result<Int> = runCatching {
        require(userId > 0) { "Invalid user id" }
        val state = api.getClockState(userId)
        if (state.clocked_in) 1 else 0
    }.mapFailure()

    suspend fun calculateTotalHours(userId: Int, startDate: Long, endDate: Long): Result<Double> = runCatching {
        require(userId > 0) { "Invalid user id" }
        require(startDate <= endDate) { "Invalid date range" }
        val records = api.getAllTimeRecords(userId).filter { record ->
            val eventTimeMs = record.entry_time_ms ?: return@filter false
            eventTimeMs in startDate..endDate
        }

        var regularInMs: Long? = null
        var overtimeInMs: Long? = null
        var totalMillis = 0L

        records
            .sortedBy { it.entry_time_ms ?: Long.MAX_VALUE }
            .forEach { record ->
                val eventTimeMs = record.entry_time_ms ?: return@forEach
                when (record.entry_type) {
                    1 -> regularInMs = eventTimeMs
                    2 -> {
                        val start = regularInMs
                        if (start != null && eventTimeMs > start) {
                            totalMillis += (eventTimeMs - start)
                        }
                        regularInMs = null
                    }
                    3 -> overtimeInMs = eventTimeMs
                    4 -> {
                        val start = overtimeInMs
                        if (start != null && eventTimeMs > start) {
                            totalMillis += (eventTimeMs - start)
                        }
                        overtimeInMs = null
                    }
                }
            }

        totalMillis / (1000.0 * 60.0 * 60.0)
    }.mapFailure()
}

private fun <T> Result<T>.mapFailure(): Result<T> {
    return this.fold(
        onSuccess = { Result.success(it) },
        onFailure = { throwable ->
            if (throwable is HttpException) {
                Result.failure(Exception("API error ${throwable.code()}: ${throwable.message()}"))
            } else {
                Result.failure(Exception(throwable.message ?: "Unknown error"))
            }
        }
    )
}
