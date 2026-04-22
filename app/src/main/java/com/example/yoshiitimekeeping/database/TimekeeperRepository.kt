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

    suspend fun enrollBiometric(
        userId: Int,
        biometricKey: String,
        platform: String = "android"
    ): Result<Credentials> = runCatching {
        require(userId > 0) { "Invalid user id" }
        val cleanedKey = biometricKey.trim()
        require(cleanedKey.length in 16..255) { "Biometric key must be 16 to 255 characters" }
        val cleanedPlatform = platform.trim().lowercase().ifEmpty { "android" }
        require(cleanedPlatform == "android" || cleanedPlatform == "ios") {
            "Platform must be android or ios"
        }

        api.enrollBiometric(
            id = userId,
            body = BiometricEnrollRequest(
                biometric_key = cleanedKey,
                platform = cleanedPlatform
            )
        )
    }.mapFailure()

    suspend fun loginWithBiometric(
        biometricKey: String,
        platform: String = "android"
    ): Result<Credentials> = runCatching {
        val cleanedKey = biometricKey.trim()
        require(cleanedKey.length in 16..255) { "Biometric key must be 16 to 255 characters" }
        val cleanedPlatform = platform.trim().lowercase().ifEmpty { "android" }
        require(cleanedPlatform == "android" || cleanedPlatform == "ios") {
            "Platform must be android or ios"
        }

        api.biometricLogin(
            BiometricLoginRequest(
                biometric_key = cleanedKey,
                platform = cleanedPlatform
            )
        )
    }.mapFailure()

    suspend fun disableBiometric(userId: Int): Result<Credentials> = runCatching {
        require(userId > 0) { "Invalid user id" }
        api.disableBiometric(userId)
    }.mapFailure()

    suspend fun logTime(
        userId: Int,
        action: ClockAction,
        eventTime: Long = System.currentTimeMillis(),
        locationTimeIn: String? = null,
        actorUserId: Int? = null,
        authMethod: String? = null,
        deviceName: String? = null,
        latitude: Double? = null,
        longitude: Double? = null
    ): Result<LogTimeResponse> = runCatching {
        require(userId > 0) { "Invalid user id" }
        require(eventTime > 0) { "Invalid event_time" }
        actorUserId?.let { require(it > 0) { "Invalid actor_user_id" } }
        val cleanedLocation = locationTimeIn?.trim()?.takeIf { it.isNotEmpty() }
        val cleanedAuthMethod = authMethod?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (cleanedAuthMethod != null) {
            require(cleanedAuthMethod in setOf("pin", "password", "biometric_key")) {
                "auth_method must be pin, password, or biometric_key"
            }
        }
        val cleanedDeviceName = deviceName?.trim()?.takeIf { it.isNotEmpty() }
        api.logTime(
            LogTimeRequest(
                user_id = userId,
                actor_user_id = actorUserId,
                event_time = eventTime,
                action = action.wireValue,
                location_time_in = cleanedLocation,
                auth_method = cleanedAuthMethod,
                device_name = cleanedDeviceName,
                latitude = latitude,
                longitude = longitude
            )
        )
    }.mapFailure()

    suspend fun clockIn(
        userId: Int,
        timeIn: Long = System.currentTimeMillis(),
        locationTimeIn: String? = null,
        actorUserId: Int? = null,
        authMethod: String? = null,
        deviceName: String? = null,
        latitude: Double? = null,
        longitude: Double? = null
    ): Result<LogTimeResponse> {
        return logTime(
            userId = userId,
            action = ClockAction.IN,
            eventTime = timeIn,
            locationTimeIn = locationTimeIn,
            actorUserId = actorUserId,
            authMethod = authMethod,
            deviceName = deviceName,
            latitude = latitude,
            longitude = longitude
        )
    }

    suspend fun clockOut(
        userId: Int,
        timeOut: Long = System.currentTimeMillis(),
        locationTimeIn: String? = null,
        actorUserId: Int? = null,
        authMethod: String? = null,
        deviceName: String? = null,
        latitude: Double? = null,
        longitude: Double? = null
    ): Result<LogTimeResponse> {
        return logTime(
            userId = userId,
            action = ClockAction.OUT,
            eventTime = timeOut,
            locationTimeIn = locationTimeIn,
            actorUserId = actorUserId,
            authMethod = authMethod,
            deviceName = deviceName,
            latitude = latitude,
            longitude = longitude
        )
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
