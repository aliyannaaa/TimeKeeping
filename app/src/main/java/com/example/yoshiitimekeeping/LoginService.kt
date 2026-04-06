package com.example.yoshiitimekeeping

import com.example.yoshiitimekeeping.database.TimekeeperRepository
import kotlinx.coroutines.delay

data class User(
    val name: String,
    val email: String,
    val jobTitle: String
)

sealed class LoginResult {
    data class Success(val user: User) : LoginResult()
    data class Failure(val message: String) : LoginResult()
}

interface LoginService {
    suspend fun login(email: String, password: String): LoginResult
    suspend fun register(email: String, password: String, name: String, jobTitle: String): LoginResult
}

class MockLoginService : LoginService {
    private val mockUsers = mutableListOf(
        User("Gerard Mamon", "gerard@yoshii.com", "UI/UX Designer"),
        User("Kyla Alianna", "aliyanna@yoshii.com", "Wishzen"),
        User("Admin User", "admin@yoshii.com", "System Administrator")
    )

    private val passwordsByEmail = mutableMapOf(
        "gerard@yoshii.com" to "password123",
        "admin@yoshii.com" to "password123"
    )

    override suspend fun login(email: String, password: String): LoginResult {
        // Simulate network delay
        delay(1500)

        val normalizedEmail = email.trim().lowercase()
        val user = mockUsers.find { it.email.equals(normalizedEmail, ignoreCase = true) }
        val knownPassword = passwordsByEmail[normalizedEmail]

        return if (user != null && knownPassword == password) {
            LoginResult.Success(user)
        } else {
            LoginResult.Failure("Invalid email or password")
        }
    }

    override suspend fun register(email: String, password: String, name: String, jobTitle: String): LoginResult {
        delay(1000)

        val normalizedEmail = email.trim().lowercase()
        if (!normalizedEmail.contains('@')) {
            return LoginResult.Failure("Please provide a valid email address")
        }
        if (password.length < 6) {
            return LoginResult.Failure("Password must be at least 6 characters")
        }
        if (passwordsByEmail.containsKey(normalizedEmail)) {
            return LoginResult.Failure("Account already exists")
        }

        val safeName = name.trim().ifEmpty { normalizedEmail.substringBefore('@') }
        val safeJobTitle = jobTitle.trim().ifEmpty { "No position set" }
        val user = User(name = safeName, email = normalizedEmail, jobTitle = safeJobTitle)

        mockUsers.add(user)
        passwordsByEmail[normalizedEmail] = password

        return LoginResult.Success(user)
    }
}

class BackendLoginService(
    private val repository: TimekeeperRepository
) : LoginService {

    override suspend fun login(email: String, password: String): LoginResult {
        val normalizedEmail = email.trim().lowercase()
        if (!normalizedEmail.contains('@')) {
            return LoginResult.Failure("Please provide a valid email address")
        }
        if (password.isBlank()) {
            return LoginResult.Failure("Password is required")
        }

        val usersResult = repository.getAllUsers()
        val users = usersResult.getOrElse {
            return LoginResult.Failure(it.message ?: "Unable to connect to backend")
        }

        val matched = users.firstOrNull { dbUser ->
            dbUser.username.trim().equals(normalizedEmail, ignoreCase = true)
        } ?: return LoginResult.Failure("Account not found. Please create one first.")

        if (matched.password != password) {
            return LoginResult.Failure("Incorrect password")
        }

        val displayName = matched.full_name?.trim()?.takeIf { it.isNotEmpty() }
            ?: normalizedEmail.substringBefore('@').replaceFirstChar { c -> c.uppercase() }
        val displayJobTitle = matched.employee_position?.trim()?.takeIf { it.isNotEmpty() }
            ?: "No position set"
        val storedEmail = matched.email?.trim()?.takeIf { it.isNotEmpty() } ?: normalizedEmail

        return LoginResult.Success(
            User(
                name = displayName,
                email = storedEmail,
                jobTitle = displayJobTitle
            )
        )
    }

    override suspend fun register(email: String, password: String, name: String, jobTitle: String): LoginResult {
        val normalizedEmail = email.trim().lowercase()
        if (!normalizedEmail.contains('@')) {
            return LoginResult.Failure("Please provide a valid email address")
        }
        if (password.length < 6) {
            return LoginResult.Failure("Password must be at least 6 characters")
        }
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) {
            return LoginResult.Failure("Please provide your full name")
        }
        val normalizedJobTitle = jobTitle.trim()

        val result = repository.registerUser(
            username = normalizedEmail,
            password = password,
            fullName = normalizedName,
            jobTitle = normalizedJobTitle.ifEmpty { null }
        )
        val created = result.getOrElse {
            return LoginResult.Failure(it.message ?: "Unable to create account")
        }

        val safeName = created.full_name?.trim()?.takeIf { it.isNotEmpty() }
            ?: normalizedName
        val safeJobTitle = created.employee_position?.trim()?.takeIf { it.isNotEmpty() }
            ?: normalizedJobTitle.ifEmpty { "No position set" }
        val safeEmail = created.email?.trim()?.takeIf { it.isNotEmpty() }
            ?: normalizedEmail

        return LoginResult.Success(
            User(
                name = safeName,
                email = safeEmail,
                jobTitle = safeJobTitle
            )
        )
    }
}
