package com.example.yoshiitimekeeping.database

/**
 * API-based usage examples for backend integration via mock-backend.
 */

suspend fun exampleRegisterUser(repo: TimekeeperRepository) {
    val result = repo.registerUser("gerard", "password123")
    result.onSuccess { user ->
        println("Registered user id=${user.id}, username=${user.username}")
    }.onFailure {
        println("Register failed: ${it.message}")
    }
}

suspend fun exampleClockIn(repo: TimekeeperRepository, userId: Int) {
    val result = repo.clockIn(userId)
    result.onSuccess { record ->
        println("Clock-in record created with id=${record.id}")
    }.onFailure {
        println("Clock-in failed: ${it.message}")
    }
}

suspend fun exampleClockOut(repo: TimekeeperRepository, userId: Int) {
    val result = repo.clockOut(userId)
    result.onSuccess { record ->
        println("Clock-out saved for id=${record.id} at ${record.time_out}")
    }.onFailure {
        println("Clock-out failed: ${it.message}")
    }
}

suspend fun exampleGetRecordsForToday(repo: TimekeeperRepository, userId: Int) {
    val start = TimeUtils.getStartOfDay()
    val end = TimeUtils.getEndOfDay()
    val result = repo.getTimeRecords(userId, start, end)
    result.onSuccess { records ->
        records.forEach { rec ->
            println("id=${rec.id}, in=${rec.time_in}, out=${rec.time_out}, duration=${rec.getFormattedDuration()}")
        }
    }.onFailure {
        println("Fetch failed: ${it.message}")
    }
}

suspend fun exampleWeeklyHours(repo: TimekeeperRepository, userId: Int) {
    val start = TimeUtils.getStartOfWeek()
    val end = TimeUtils.getEndOfWeek()
    val result = repo.calculateTotalHours(userId, start, end)
    result.onSuccess { hours ->
        println("Weekly hours: ${TimeUtils.formatHours(hours)}")
    }.onFailure {
        println("Hours calculation failed: ${it.message}")
    }
}
