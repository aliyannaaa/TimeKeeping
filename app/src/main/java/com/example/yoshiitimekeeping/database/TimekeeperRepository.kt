package com.example.yoshiitimekeeping.database

import retrofit2.HttpException

class TimekeeperRepository(
    private val api: TimekeeperApi
) {

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

    suspend fun clockIn(
        userId: Int,
        timeIn: Long = System.currentTimeMillis(),
        locationTimeIn: String? = null
    ): Result<TimeInOut> = runCatching {
        require(userId > 0) { "Invalid user id" }
        require(timeIn > 0) { "Invalid time_in" }
        val cleanedLocation = locationTimeIn?.trim()?.takeIf { it.isNotEmpty() }
        api.clockIn(ClockInRequest(user_id = userId, time_in = timeIn, location_time_in = cleanedLocation))
    }.mapFailure()

    suspend fun getActiveClockInRecord(userId: Int): Result<TimeInOut?> = runCatching {
        require(userId > 0) { "Invalid user id" }
        api.getActiveClockInRecord(userId)
    }.mapFailure()

    suspend fun clockOut(
        userId: Int,
        timeOut: Long = System.currentTimeMillis(),
        locationTimeOut: String? = null
    ): Result<TimeInOut> = runCatching {
        require(userId > 0) { "Invalid user id" }
        val active = api.getActiveClockInRecord(userId) ?: error("No active clock-in record")
        active.time_in_ms?.let { activeTimeInMs ->
            require(timeOut > activeTimeInMs) { "time_out must be greater than time_in" }
        }
        val cleanedLocation = locationTimeOut?.trim()?.takeIf { it.isNotEmpty() }
        api.clockOut(
            active.id ?: error("Active record id missing"),
            ClockOutRequest(time_out = timeOut, location_time_out = cleanedLocation)
        )
    }.mapFailure()

    suspend fun getAllTimeRecords(userId: Int? = null): Result<List<TimeInOut>> = runCatching {
        api.getAllTimeRecords(userId)
    }.mapFailure()

    suspend fun getTimeRecords(userId: Int, startDate: Long, endDate: Long): Result<List<TimeInOut>> = runCatching {
        require(userId > 0) { "Invalid user id" }
        require(startDate <= endDate) { "Invalid date range" }
        api.getAllTimeRecords(userId).filter { record ->
            val timeInMs = record.time_in_ms ?: return@filter false
            timeInMs in startDate..endDate
        }
    }.mapFailure()

    suspend fun isUserClockedIn(userId: Int): Result<Boolean> = runCatching {
        require(userId > 0) { "Invalid user id" }
        api.getActiveClockInRecord(userId) != null
    }.mapFailure()

    suspend fun getPendingClockOutCount(userId: Int): Result<Int> = runCatching {
        require(userId > 0) { "Invalid user id" }
        val records = api.getAllTimeRecords(userId)
        records.count { it.time_out_ms == null }
    }.mapFailure()

    suspend fun calculateTotalHours(userId: Int, startDate: Long, endDate: Long): Result<Double> = runCatching {
        require(userId > 0) { "Invalid user id" }
        require(startDate <= endDate) { "Invalid date range" }
        val records = api.getAllTimeRecords(userId).filter { record ->
            val timeInMs = record.time_in_ms ?: return@filter false
            timeInMs in startDate..endDate
        }
        records.sumOf { record ->
            val timeInMs = record.time_in_ms ?: return@sumOf 0.0
            DbHelpers.calculateHours(timeInMs, record.time_out_ms)
        }
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
