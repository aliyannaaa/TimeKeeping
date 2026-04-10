package com.example.yoshiitimekeeping.database

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// Data models for API (match your backend JSON)
data class Credentials(
    val id: Int? = null,
    val username: String,
    val password: String,
    val full_name: String? = null,
    val employee_position: String? = null,
    val email: String? = null
)

data class TimeInOut(
    val id: String? = null,
    val user_id: Int,
    val entry_time: String? = null,
    val entry_time_ms: Long? = null,
    val entry_type: Int = 0,
    val created_date: String? = null,
    val modified_date: String? = null,
    val created_date_ms: Long? = null,
    val modified_date_ms: Long? = null,
    val location_time_in: String? = null
)

data class ClockStateResponse(
    val user_id: Int,
    val clocked_in: Boolean,
    val last_record: TimeInOut? = null
)

data class LogTimeRequest(
    val user_id: Int,
    val event_time: Long,
    val action: String,
    val location_time_in: String? = null
)

data class LogTimeResponse(
    val record: TimeInOut,
    val overridden: Boolean = false,
    val notice: String? = null,
    val clocked_in: Boolean = false
)

// Retrofit API interface for backend interaction
interface TimekeeperApi {
    // --- Credentials endpoints ---
    @GET("/credentials")
    suspend fun getAllCredentials(): List<Credentials>

    @POST("/credentials")
    suspend fun registerUser(@Body credentials: Credentials): Credentials

    @GET("/credentials/{id}")
    suspend fun getCredentialById(@Path("id") id: Int): Credentials

    // --- TimeInOut endpoints ---
    @GET("/timeinout")
    suspend fun getAllTimeRecords(@Query("user_id") userId: Int? = null): List<TimeInOut>

    @GET("/timeinout/active/{userId}")
    suspend fun getClockState(@Path("userId") userId: Int): ClockStateResponse

    @POST("/timeinout/log")
    suspend fun logTime(@Body body: LogTimeRequest): LogTimeResponse
}

// Constraint and index handling must be enforced in backend API + MySQL schema.
