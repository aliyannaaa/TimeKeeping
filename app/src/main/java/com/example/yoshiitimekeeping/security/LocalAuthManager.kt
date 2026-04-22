package com.example.yoshiitimekeeping.security

import android.content.Context
import androidx.core.content.edit
import com.example.yoshiitimekeeping.User
import java.security.MessageDigest
import java.util.UUID

enum class MobilePlatform {
    ANDROID,
    IOS,
    UNKNOWN
}

enum class BiometricUnlockMode(val storedValue: String) {
    AUTO("auto"),
    FACE("face"),
    FINGERPRINT("fingerprint");

    companion object {
        fun fromStoredValue(value: String?): BiometricUnlockMode {
            return entries.firstOrNull { it.storedValue == value } ?: AUTO
        }
    }
}

fun detectMobilePlatform(): MobilePlatform = MobilePlatform.ANDROID

class LocalAuthManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveUser(user: User) {
        prefs.edit {
            putInt(KEY_USER_ID, user.employeeId ?: NO_USER_ID)
            putString(KEY_USER_NAME, user.name)
            putString(KEY_LOGIN_ID, user.loginId)
            putString(KEY_JOB_TITLE, user.jobTitle)
        }
    }

    fun getSavedUser(): User? {
        val name = prefs.getString(KEY_USER_NAME, null) ?: return null
        val loginId = prefs.getString(KEY_LOGIN_ID, null) ?: return null
        val jobTitle = prefs.getString(KEY_JOB_TITLE, null) ?: return null
        val userId = prefs.getInt(KEY_USER_ID, NO_USER_ID).takeIf { it != NO_USER_ID }
        return User(
            employeeId = userId,
            name = name,
            loginId = loginId,
            jobTitle = jobTitle
        )
    }

    fun hasPin(): Boolean = !prefs.getString(KEY_PIN_HASH, null).isNullOrBlank()

    fun isQuickUnlockReady(): Boolean = getSavedUser() != null && hasPin()

    fun savePin(pin: String): Result<Unit> {
        if (!isPinValid(pin)) {
            return Result.failure(IllegalArgumentException("PIN must be 4 to 8 digits"))
        }

        val loginId = getSavedUser()?.loginId
            ?: return Result.failure(IllegalStateException("No saved user available for PIN setup"))

        prefs.edit {
            putString(KEY_PIN_HASH, hashPin(pin, loginId))
            putString(KEY_PIN_LOGIN_ID, loginId)
        }
        return Result.success(Unit)
    }

    fun verifyPin(pin: String): Boolean {
        val savedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val loginId = getSavedUser()?.loginId ?: return false
        val pinOwner = prefs.getString(KEY_PIN_LOGIN_ID, null) ?: return false
        if (!pinOwner.equals(loginId, ignoreCase = true)) {
            return false
        }
        return savedHash == hashPin(pin, loginId)
    }

    fun isBiometricEnabled(): Boolean = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_BIOMETRIC_ENABLED, enabled)
        }
    }

    fun isDeviceCredentialEnabled(): Boolean = prefs.getBoolean(KEY_DEVICE_CREDENTIAL_ENABLED, true)

    fun setDeviceCredentialEnabled(enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_DEVICE_CREDENTIAL_ENABLED, enabled)
        }
    }

    fun getBiometricUnlockMode(): BiometricUnlockMode {
        val raw = prefs.getString(KEY_BIOMETRIC_UNLOCK_MODE, null)
        return BiometricUnlockMode.fromStoredValue(raw)
    }

    fun setBiometricUnlockMode(mode: BiometricUnlockMode) {
        prefs.edit {
            putString(KEY_BIOMETRIC_UNLOCK_MODE, mode.storedValue)
        }
    }

    fun removePin() {
        prefs.edit {
            remove(KEY_PIN_HASH)
            remove(KEY_PIN_LOGIN_ID)
        }
    }

    fun getBiometricLoginKey(): String? {
        val owner = prefs.getString(KEY_BIOMETRIC_LOGIN_OWNER, null)
        val loginId = getSavedUser()?.loginId
        if (owner.isNullOrBlank() || loginId.isNullOrBlank()) {
            return null
        }
        if (!owner.equals(loginId, ignoreCase = true)) {
            return null
        }
        return prefs.getString(KEY_BIOMETRIC_LOGIN_KEY, null)
    }

    fun getOrCreateBiometricLoginKey(): Result<String> {
        val loginId = getSavedUser()?.loginId
            ?: return Result.failure(IllegalStateException("No saved user available for biometric setup"))

        val existing = getBiometricLoginKey()
        if (!existing.isNullOrBlank()) {
            return Result.success(existing)
        }

        val generated = "bio_${UUID.randomUUID().toString().replace("-", "")}_${System.currentTimeMillis()}"
        prefs.edit {
            putString(KEY_BIOMETRIC_LOGIN_OWNER, loginId)
            putString(KEY_BIOMETRIC_LOGIN_KEY, generated)
        }
        return Result.success(generated)
    }

    fun clearBiometricLoginKey() {
        prefs.edit {
            remove(KEY_BIOMETRIC_LOGIN_OWNER)
            remove(KEY_BIOMETRIC_LOGIN_KEY)
        }
    }

    fun clearQuickUnlock() {
        prefs.edit {
            remove(KEY_PIN_HASH)
            remove(KEY_PIN_LOGIN_ID)
            remove(KEY_BIOMETRIC_ENABLED)
            remove(KEY_DEVICE_CREDENTIAL_ENABLED)
            remove(KEY_BIOMETRIC_UNLOCK_MODE)
            remove(KEY_BIOMETRIC_LOGIN_OWNER)
            remove(KEY_BIOMETRIC_LOGIN_KEY)
        }
    }

    private fun hashPin(pin: String, loginId: String): String {
        val source = "${loginId.lowercase()}:$pin"
        val bytes = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val PREFS_NAME = "local_auth_prefs"
        private const val NO_USER_ID = -1

        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_LOGIN_ID = "login_id"
        private const val KEY_JOB_TITLE = "job_title"

        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_LOGIN_ID = "pin_login_id"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_DEVICE_CREDENTIAL_ENABLED = "device_credential_enabled"
        private const val KEY_BIOMETRIC_UNLOCK_MODE = "biometric_unlock_mode"
        private const val KEY_BIOMETRIC_LOGIN_OWNER = "biometric_login_owner"
        private const val KEY_BIOMETRIC_LOGIN_KEY = "biometric_login_key"

        fun isPinValid(pin: String): Boolean {
            val isDigitsOnly = pin.all { it.isDigit() }
            return isDigitsOnly && pin.length in 4..8
        }
    }
}
