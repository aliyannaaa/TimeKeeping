package com.example.yoshiitimekeeping.database

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
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
    val time_in: String? = null,
    val time_out: String? = null,
    val time_in_ms: Long? = null,
    val time_out_ms: Long? = null,
    val created_date: String? = null,
    val modified_date: String? = null,
    val created_date_ms: Long? = null,
    val modified_date_ms: Long? = null,
    val location_time_in: String? = null,
    val location_time_out: String? = null
)

data class ClockInRequest(
    val user_id: Int,
    val time_in: Long,
    val location_time_in: String? = null
)

data class ClockOutRequest(
    val time_out: Long,
    val location_time_out: String? = null
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
    suspend fun getActiveClockInRecord(@Path("userId") userId: Int): TimeInOut?

    @POST("/timeinout")
    suspend fun clockIn(@Body body: ClockInRequest): TimeInOut

    @PUT("/timeinout/{id}/clockout")
    suspend fun clockOut(@Path("id") id: String, @Body body: ClockOutRequest): TimeInOut
}

// Constraint and index handling must be enforced in backend API + MySQL schema.
