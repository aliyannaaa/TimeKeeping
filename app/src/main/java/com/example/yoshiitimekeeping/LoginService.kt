package com.example.yoshiitimekeeping

import kotlinx.coroutines.delay

data class User(
    val name: String,
    val email: String,
    val jobTitle: String
)

sealed class LoginResult {
    data class Success(val user: User) : LoginResult()
    data object Failure : LoginResult()
}

interface LoginService {
    suspend fun login(email: String, password: String): LoginResult
}

class MockLoginService : LoginService {
    private val mockUsers = listOf(
        User("Gerard Mamon", "gerard@yoshii.com", "UI/UX Designer"),
        User("Kyla Alianna", "aliyanna@yoshii.com", "Wishzen"),
        User("Admin User", "admin@yoshii.com", "System Administrator")
    )

    override suspend fun login(email: String, password: String): LoginResult {
        // Simulate network delay
        delay(1500)
        
        // Mock successful login for specific credentials
        return if ((email == "gerard@yoshii.com" || email == "admin@yoshii.com") && password == "password123") {
            val user = mockUsers.find { it.email == email }!!
            LoginResult.Success(user)
        } else {
            LoginResult.Failure
        }
    }
}
