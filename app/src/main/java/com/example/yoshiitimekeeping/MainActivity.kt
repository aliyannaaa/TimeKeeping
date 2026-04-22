package com.example.yoshiitimekeeping

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.yoshiitimekeeping.database.ApiClient
import com.example.yoshiitimekeeping.database.TimeInOut
import com.example.yoshiitimekeeping.notifications.AttendanceReminderScheduler
import com.example.yoshiitimekeeping.security.LocalAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appContext = this@MainActivity.applicationContext
            var activeBaseUrl by remember {
                mutableStateOf(ApiClient.getPreferredBaseUrl(appContext))
            }
            var backendConnected by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                while (true) {
                    val resolved = ApiClient.resolveAndPersistBestBaseUrl(appContext)
                    if (resolved != null) {
                        activeBaseUrl = resolved
                        backendConnected = true
                        break
                    }

                    backendConnected = false
                    delay(2500)
                }
            }

            val loginService: LoginService = remember(activeBaseUrl) {
                BackendLoginService(ApiClient.repository(activeBaseUrl))
            }

            Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                var currentScreen by remember { mutableStateOf("login") }
                var loggedInUser by remember { mutableStateOf<User?>(null) }
                var lastLoginAuthMethod by remember { mutableStateOf("password") }

                when (currentScreen) {
                    "login" -> LoginScreen(
                        loginService = loginService,
                        activeBaseUrl = activeBaseUrl,
                        backendConnected = backendConnected,
                        onLoginSuccess = { user, authMethod ->
                            loggedInUser = user
                            lastLoginAuthMethod = authMethod
                            currentScreen = "loading"
                        }
                    )
                    "loading" -> LoadingScreen(onFinished = { currentScreen = "clockin" })
                    "clockin" -> loggedInUser?.let {
                        ClockInScreen(
                            user = it,
                            activeBaseUrl = activeBaseUrl,
                            authMethod = lastLoginAuthMethod,
                            onLogout = {
                                loggedInUser = null
                                currentScreen = "login"
                            }
                        )
                    }
                }
            }
        }
    }
}

private const val CLOCK_PREFS_NAME = "clock_display_prefs"
private const val KEY_USE_24_HOUR_FORMAT = "use_24_hour_format"

@Composable
fun LoadingScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(2000)
        onFinished()
    }
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF8F9FF)).padding(24.dp)) {
        Text(
            text = stringResource(id = R.string.app_title),
            fontSize = 36.sp,
            fontWeight = FontWeight.Black,
            color = Color(0xFF1A46B8),
            modifier = Modifier.align(Alignment.Center)
        )
        Text(
            text = stringResource(id = R.string.copyright),
            fontSize = 10.sp,
            color = Color.Gray,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
        )
    }
}

@Composable
fun LoginScreen(
    loginService: LoginService,
    activeBaseUrl: String,
    backendConnected: Boolean,
    onLoginSuccess: (User, String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var jobTitle by remember { mutableStateOf("") }
    var isRegisterMode by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var showPinSetupDialog by remember { mutableStateOf(false) }
    var pinSetupValue by remember { mutableStateOf("") }
    var pinConfirmValue by remember { mutableStateOf("") }
    var askEnableBiometrics by remember { mutableStateOf(false) }
    var pendingQuickUnlockUser by remember { mutableStateOf<User?>(null) }
    var quickUnlockDismissed by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val localAuthManager = remember(context.applicationContext) {
        LocalAuthManager(context.applicationContext)
    }
    val repository = remember(activeBaseUrl) { ApiClient.repository(activeBaseUrl) }
    var savedUser by remember { mutableStateOf(localAuthManager.getSavedUser()) }
    var biometricEnabled by remember { mutableStateOf(localAuthManager.isBiometricEnabled()) }
    var deviceCredentialEnabled by remember { mutableStateOf(localAuthManager.isDeviceCredentialEnabled()) }

    val canUseBiometrics = remember(context) {
        canUseBiometricPrompt(context)
    }

    val canUsePhoneSecurityOrBiometric = remember(context) {
        canUseBiometricOrDeviceCredential(context)
    }

    val hasQuickUnlock = !isRegisterMode && savedUser != null && localAuthManager.isQuickUnlockReady()
    val allowDeviceCredentialFallback =
        deviceCredentialEnabled && canUsePhoneSecurityOrBiometric
    val showQuickUnlockSection = hasQuickUnlock && !quickUnlockDismissed

    fun completeLoginAfterSetup(user: User) {
        pendingQuickUnlockUser = null
        pinSetupValue = ""
        pinConfirmValue = ""
        showPinSetupDialog = false
        askEnableBiometrics = false
        onLoginSuccess(user, "password")
    }

    fun promptSecureUnlock(user: User) {
        val activity = context.findActivity()
        if (activity == null) {
            Toast.makeText(context, "Unable to open biometric prompt on this screen", Toast.LENGTH_SHORT).show()
            return
        }

        val useDeviceCredential = allowDeviceCredentialFallback
        val promptBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Secure login")

        val promptInfo = if (useDeviceCredential) {
            promptBuilder
                .setSubtitle("Use your device biometrics or phone PIN/password/pattern")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
        } else {
            promptBuilder
                .setSubtitle("Use your enrolled biometric to continue")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .setNegativeButtonText("Cancel")
                .build()
        }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    pin = ""
                    val secureAuthMethod = if (
                        result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL
                    ) {
                        "password"
                    } else {
                        "biometric_key"
                    }
                    onLoginSuccess(user, secureAuthMethod)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Toast.makeText(context, errString, Toast.LENGTH_SHORT).show()
                }

                override fun onAuthenticationFailed() {
                    Toast.makeText(context, "Authentication not recognized. Try again.", Toast.LENGTH_SHORT).show()
                }
            }
        )

        prompt.authenticate(promptInfo)
    }

    suspend fun syncBiometricState(user: User, enable: Boolean): Boolean {
        val userId = user.employeeId
        if (userId == null) {
            Toast.makeText(context, "Cannot sync biometrics: user is not linked to backend", Toast.LENGTH_SHORT).show()
            return false
        }

        return if (enable) {
            val keyResult = localAuthManager.getOrCreateBiometricLoginKey()
            if (keyResult.isFailure) {
                Toast.makeText(
                    context,
                    keyResult.exceptionOrNull()?.message ?: "Failed to prepare biometric key",
                    Toast.LENGTH_SHORT
                ).show()
                false
            } else {
                repository.enrollBiometric(userId, keyResult.getOrThrow(), "android")
                    .onSuccess {
                        localAuthManager.setBiometricEnabled(true)
                        biometricEnabled = true
                    }
                    .onFailure {
                        Toast.makeText(context, "Biometric sync failed: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                    .isSuccess
            }
        } else {
            repository.disableBiometric(userId)
                .onSuccess {
                    localAuthManager.setBiometricEnabled(false)
                    localAuthManager.clearBiometricLoginKey()
                    biometricEnabled = false
                }
                .onFailure {
                    Toast.makeText(context, "Failed to disable biometric on server: ${it.message}", Toast.LENGTH_SHORT).show()
                }
                .isSuccess
        }
    }

    LaunchedEffect(savedUser?.employeeId, biometricEnabled, backendConnected, activeBaseUrl) {
        val user = savedUser ?: return@LaunchedEffect
        if (!backendConnected || !biometricEnabled || user.employeeId == null) {
            return@LaunchedEffect
        }

        val keyResult = localAuthManager.getOrCreateBiometricLoginKey()
        if (keyResult.isSuccess) {
            repository.enrollBiometric(user.employeeId, keyResult.getOrThrow(), "android")
        }
    }

    if (showPinSetupDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Set up quick unlock PIN") },
            text = {
                Column {
                    Text("Create a 4-8 digit PIN. You can use this before biometrics for faster login.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinSetupValue,
                        onValueChange = { value ->
                            pinSetupValue = value.filter { it.isDigit() }.take(8)
                        },
                        label = { Text("New PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pinConfirmValue,
                        onValueChange = { value ->
                            pinConfirmValue = value.filter { it.isDigit() }.take(8)
                        },
                        label = { Text("Confirm PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!LocalAuthManager.isPinValid(pinSetupValue)) {
                            Toast.makeText(context, "PIN must be 4 to 8 digits", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        if (pinSetupValue != pinConfirmValue) {
                            Toast.makeText(context, "PINs do not match", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }

                        val saveResult = localAuthManager.savePin(pinSetupValue)
                        if (saveResult.isFailure) {
                            Toast.makeText(
                                context,
                                saveResult.exceptionOrNull()?.message ?: "Failed to save PIN",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@TextButton
                        }

                        showPinSetupDialog = false
                        pinSetupValue = ""
                        pinConfirmValue = ""
                        if (canUseBiometrics) {
                            askEnableBiometrics = true
                        } else {
                            pendingQuickUnlockUser?.let { completeLoginAfterSetup(it) }
                        }
                    }
                ) {
                    Text("Save PIN")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val user = pendingQuickUnlockUser
                        if (user != null) {
                            completeLoginAfterSetup(user)
                        } else {
                            showPinSetupDialog = false
                        }
                    }
                ) {
                    Text("Skip")
                }
            }
        )
    }

    if (askEnableBiometrics) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Enable biometrics") },
            text = { Text("Use fingerprint or face unlock after PIN setup for faster sign in.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val user = pendingQuickUnlockUser
                        if (user == null) {
                            askEnableBiometrics = false
                            return@TextButton
                        }

                        scope.launch {
                            val synced = syncBiometricState(user, enable = true)
                            if (synced) {
                                completeLoginAfterSetup(user)
                            }
                        }
                    }
                ) {
                    Text("Enable")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        localAuthManager.setBiometricEnabled(false)
                        localAuthManager.clearBiometricLoginKey()
                        biometricEnabled = false
                        val user = pendingQuickUnlockUser
                        if (user != null) {
                            completeLoginAfterSetup(user)
                        } else {
                            askEnableBiometrics = false
                        }
                    }
                ) {
                    Text("Not now")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF8F9FF)).padding(24.dp)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(stringResource(id = R.string.app_title), fontSize = 36.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A46B8))
            Spacer(modifier = Modifier.height(48.dp))

            if (showQuickUnlockSection) {
                Text(
                    text = "Quick unlock for ${savedUser?.name}",
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1A46B8)
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value ->
                        pin = value.filter { it.isDigit() }.take(8)
                    },
                    label = { Text("PIN") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !isLoading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val user = savedUser
                        if (user == null) {
                            Toast.makeText(context, "No saved account for quick unlock", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (!localAuthManager.verifyPin(pin)) {
                            Toast.makeText(context, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        pin = ""
                        onLoginSuccess(user, "pin")
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B67F6)),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isLoading && backendConnected
                ) {
                    Text("Unlock with PIN", fontWeight = FontWeight.Bold)
                }

                val canUseSecureUnlock = biometricEnabled && (
                    canUseBiometrics || allowDeviceCredentialFallback
                    )

                if (canUseSecureUnlock) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val user = savedUser
                            if (user != null) {
                                promptSecureUnlock(user)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled = !isLoading && backendConnected
                    ) {
                        Text(buildSecureUnlockLabel(allowDeviceCredentialFallback))
                    }
                }

                if (biometricEnabled && !canUseSecureUnlock) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Secure unlock is enabled, but this device has no enrolled biometrics or lock-screen credential.",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        isRegisterMode = false
                        quickUnlockDismissed = true
                    },
                    enabled = !isLoading
                ) {
                    Text("Use account password instead", color = Color(0xFF1A46B8))
                }

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider(color = Color(0xFFCBD5F0), thickness = 1.dp)
                Spacer(modifier = Modifier.height(18.dp))
            }

            if (!isRegisterMode && hasQuickUnlock && !showQuickUnlockSection) {
                TextButton(
                    onClick = { quickUnlockDismissed = false },
                    enabled = !isLoading
                ) {
                    Text("Use quick unlock instead", color = Color(0xFF1A46B8), fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (isRegisterMode) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    enabled = !isLoading
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = jobTitle,
                    onValueChange = { jobTitle = it },
                    label = { Text("Job Title") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    enabled = !isLoading
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            if (isRegisterMode || !showQuickUnlockSection) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(id = R.string.email_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    enabled = !isLoading
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(id = R.string.password_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !isLoading
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        isLoading = true
                        scope.launch {
                            val result = if (isRegisterMode) {
                                loginService.register(email, password, name, jobTitle)
                            } else {
                                loginService.login(email, password)
                            }
                            isLoading = false
                            when (result) {
                                is LoginResult.Success -> {
                                    val priorUser = localAuthManager.getSavedUser()
                                    if (priorUser?.loginId?.equals(result.user.loginId, ignoreCase = true) == false) {
                                        localAuthManager.clearQuickUnlock()
                                    }
                                    localAuthManager.saveUser(result.user)
                                    savedUser = result.user
                                    quickUnlockDismissed = false

                                    if (!localAuthManager.hasPin()) {
                                        pendingQuickUnlockUser = result.user
                                        showPinSetupDialog = true
                                    } else {
                                        onLoginSuccess(result.user, "password")
                                    }
                                }
                                is LoginResult.Failure -> {
                                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A46B8)),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isLoading && backendConnected
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else if (!backendConnected) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Text(
                            text = if (isRegisterMode) "Create Account" else stringResource(id = R.string.login_button),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            TextButton(
                onClick = {
                    isRegisterMode = !isRegisterMode
                    if (!isRegisterMode) {
                        name = ""
                        jobTitle = ""
                    }
                    if (isRegisterMode) {
                        quickUnlockDismissed = true
                    }
                },
                enabled = !isLoading
            ) {
                Text(
                    text = if (isRegisterMode) "Back to Login" else "No account yet? Create one",
                    color = Color(0xFF1A46B8),
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
        Text(
            text = stringResource(id = R.string.copyright),
            fontSize = 10.sp,
            color = Color.Gray,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
        )
    }
}

@Composable
fun ClockInScreen(
    user: User,
    activeBaseUrl: String,
    authMethod: String,
    onLogout: () -> Unit
) {
    var currentTime by remember { mutableStateOf(Calendar.getInstance().time) }
    val repository = remember(activeBaseUrl) { ApiClient.repository(activeBaseUrl) }
    val context = LocalContext.current
    val clockPrefs = remember(context.applicationContext) {
        context.applicationContext.getSharedPreferences(CLOCK_PREFS_NAME, Context.MODE_PRIVATE)
    }
    val initialUse24HourFormat = remember(clockPrefs) {
        clockPrefs.getBoolean(KEY_USE_24_HOUR_FORMAT, false)
    }
    var use24HourFormat by rememberSaveable { mutableStateOf(initialUse24HourFormat) }
    var isClockedIn by remember { mutableStateOf(false) }
    var attendanceStateCode by remember { mutableStateOf("NO_RECORD") }
    var lastAttendanceRecord by remember { mutableStateOf<TimeInOut?>(null) }
    var currentUserId by remember { mutableStateOf(user.employeeId) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var showNotification by remember { mutableStateOf(false) }
    var isSuccess by remember { mutableStateOf(false) }
    var notificationDetails by remember { mutableStateOf("") }
    var notificationDragOffsetX by remember { mutableStateOf(0f) }
    var showMenu by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val localAuthManager = remember(context.applicationContext) {
        LocalAuthManager(context.applicationContext)
    }
    var biometricEnabled by remember { mutableStateOf(localAuthManager.isBiometricEnabled()) }
    var deviceCredentialEnabled by remember { mutableStateOf(localAuthManager.isDeviceCredentialEnabled()) }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var hasAppPin by remember { mutableStateOf(localAuthManager.hasPin()) }
    var isSettingsBusy by remember { mutableStateOf(false) }
    var actingEmployeeIdInput by rememberSaveable { mutableStateOf("") }
    var showClockActionConfirmDialog by remember { mutableStateOf(false) }
    var lastReminderDay by remember { mutableStateOf<String?>(null) }
    var lastTimedOutReminderRecordId by remember { mutableStateOf<String?>(null) }
    var lastTimedOutReminderAtMs by remember { mutableStateOf(0L) }

    val timeFormatter = remember(use24HourFormat) {
        val pattern = if (use24HourFormat) "HH:mm:ss" else "hh:mm:ss a"
        SimpleDateFormat(pattern, Locale.getDefault())
    }
    val dateFormatter = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault())
    val fallbackLocationLabel = stringResource(id = R.string.location_info)
    var gpsLocationLabel by remember { mutableStateOf(fallbackLocationLabel) }
    val deviceName = remember { buildDeviceName() }
    val canUsePhoneSecurityOrBiometric = remember(context) {
        canUseBiometricOrDeviceCredential(context)
    }
    val canActForOtherEmployee = remember(user.jobTitle, user.loginId) {
        canManageAttendanceForOthers(user)
    }
    var lastResolvedLocationKey by remember { mutableStateOf<String?>(null) }
    var lastLocationUiUpdateAtMs by remember { mutableStateOf(0L) }

    suspend fun updateLocationLabel(snapshot: LocationSnapshot) {
        val roundedKey = "%.3f,%.3f".format(Locale.US, snapshot.latitude, snapshot.longitude)
        if (roundedKey == lastResolvedLocationKey) {
            return
        }

        val nowMs = System.currentTimeMillis()
        val hasAddressAlready = gpsLocationLabel != fallbackLocationLabel && gpsLocationLabel != "Address unavailable"
        if (hasAddressAlready && nowMs - lastLocationUiUpdateAtMs < 30_000L) {
            return
        }

        lastResolvedLocationKey = roundedKey
        val humanLabel = resolveHumanReadableLocation(
            context = context,
            latitude = snapshot.latitude,
            longitude = snapshot.longitude
        )

        if (!humanLabel.isNullOrBlank()) {
            val normalized = humanLabel.replace("\\s+".toRegex(), " ").trim()
            if (normalized.equals(gpsLocationLabel, ignoreCase = true)) {
                return
            }
            gpsLocationLabel = normalized
            lastLocationUiUpdateAtMs = nowMs
            return
        }

        if (gpsLocationLabel == fallbackLocationLabel) {
            gpsLocationLabel = "Address unavailable"
            lastLocationUiUpdateAtMs = nowMs
        }
    }

    suspend fun refreshClockStateForUser(targetUserId: Int) {
        repository.getClockState(targetUserId)
            .onSuccess { state ->
                isClockedIn = state.clocked_in
                attendanceStateCode = state.attendance_state
                    ?: inferAttendanceStateFromRecord(state.last_record, state.clocked_in)
                lastAttendanceRecord = state.last_record
            }
    }

    fun maybeShowIdleAttendanceReminder() {
        val nowMs = System.currentTimeMillis()
        val nowCal = Calendar.getInstance()
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
        val currentState = attendanceStateCode.uppercase(Locale.US)

        if (currentState == "NO_RECORD") {
            val isLateMorningOrAfter = nowCal.get(Calendar.HOUR_OF_DAY) >= 10
            if (isLateMorningOrAfter && lastReminderDay != todayKey) {
                lastReminderDay = todayKey
                isSuccess = true
                statusMessage = "Reminder"
                notificationDetails = "You have not timed in yet today. If you're already working, please tap Time In."
                showNotification = true
            }
            return
        }

        if (currentState != "NORMAL_IN" && currentState != "OVERTIME_IN") {
            return
        }

        val inTimeMs = lastAttendanceRecord?.entry_time_ms ?: return
        val elapsedMs = nowMs - inTimeMs
        if (elapsedMs <= 0L) {
            return
        }

        val thresholdMs = if (currentState == "OVERTIME_IN") {
            2L * 60L * 60L * 1000L
        } else {
            9L * 60L * 60L * 1000L
        }

        if (elapsedMs < thresholdMs) {
            return
        }

        val recordKey = lastAttendanceRecord?.id ?: "${currentState}-$inTimeMs"
        val canRepeatReminder = nowMs - lastTimedOutReminderAtMs >= (60L * 60L * 1000L)
        if (recordKey != lastTimedOutReminderRecordId || canRepeatReminder) {
            lastTimedOutReminderRecordId = recordKey
            lastTimedOutReminderAtMs = nowMs
            val elapsedLabel = formatDurationForPeople(elapsedMs)
            isSuccess = true
            statusMessage = "Reminder"
            notificationDetails = if (currentState == "OVERTIME_IN") {
                "You've been on overtime for $elapsedLabel. Don't forget to Time Out when you finish."
            } else {
                "You've been timed in for $elapsedLabel. Don't forget to Time Out."
            }
            showNotification = true
        }
    }

    suspend fun submitClockAction() {
        isLoading = true
        try {
            if (isAttendanceCompleteForToday(attendanceStateCode)) {
                isSuccess = true
                statusMessage = "Already completed"
                notificationDetails = "Today's attendance is already complete. You can Time In again tomorrow."
                showNotification = true
                return
            }

            val actorUserId = currentUserId
            if (actorUserId == null) {
                isSuccess = false
                statusMessage = "Account not found"
                notificationDetails = "We couldn't find your account record right now. Please try again or contact your admin."
                showNotification = true
                return
            }

            val actingForOther = canActForOtherEmployee && actingEmployeeIdInput.isNotBlank()
            val targetUserId = if (!actingForOther) {
                actorUserId
            } else {
                val parsed = actingEmployeeIdInput.toIntOrNull()
                if (parsed == null || parsed <= 0) {
                    isSuccess = false
                    statusMessage = "Invalid employee ID"
                    notificationDetails = "Enter a valid numeric employee ID to act on behalf of another employee."
                    showNotification = true
                    return
                }
                parsed
            }
            val auditActorUserId = actorUserId
            val statusSuffix = if (targetUserId != actorUserId) {
                " (for employee #$targetUserId)"
            } else {
                ""
            }

            val targetLookup = repository.getUserById(targetUserId)
            if (targetLookup.isFailure) {
                isSuccess = false
                statusMessage = "Employee not found"
                notificationDetails = "We couldn't find employee #$targetUserId. Please check the ID and try again."
                showNotification = true
                return
            }

            if (!hasLocationPermission(context)) {
                isSuccess = false
                statusMessage = "Location permission required"
                notificationDetails = "Open app settings and allow Location permission, then tap Time In/Out again."
                openAppDetailsSettings(context)
                showNotification = true
                return
            }

            if (!isLocationServiceEnabled(context)) {
                isSuccess = false
                statusMessage = "GPS is turned off"
                notificationDetails = "Turn on Location/GPS in your phone settings, then try again."
                showNotification = true
                return
            }

            val latestLocation = getBestLastKnownLocation(context)
            val locationForStorage: String? = if (latestLocation != null) {
                val exactAddress = resolveHumanReadableLocation(
                    context = context,
                    latitude = latestLocation.latitude,
                    longitude = latestLocation.longitude
                )

                if (!exactAddress.isNullOrBlank()) {
                    gpsLocationLabel = exactAddress
                    sanitizeLocationForStorage(exactAddress)
                } else {
                    updateLocationLabel(latestLocation)
                    null
                }
            } else {
                null
            }

            val shouldTimeOutNow = isStateClockedIn(attendanceStateCode, isClockedIn)
            val clockActionResult = if (!shouldTimeOutNow) {
                repository.clockIn(
                    userId = targetUserId,
                    locationTimeIn = locationForStorage,
                    actorUserId = auditActorUserId,
                    authMethod = authMethod,
                    deviceName = deviceName,
                    latitude = latestLocation?.latitude,
                    longitude = latestLocation?.longitude
                )
            } else {
                repository.clockOut(
                    userId = targetUserId,
                    locationTimeIn = locationForStorage,
                    actorUserId = auditActorUserId,
                    authMethod = authMethod,
                    deviceName = deviceName,
                    latitude = latestLocation?.latitude,
                    longitude = latestLocation?.longitude
                )
            }

            clockActionResult
                .onSuccess { response ->
                    isClockedIn = response.clocked_in
                    attendanceStateCode = response.attendance_state
                        ?: inferAttendanceStateFromRecord(response.record, response.clocked_in)
                    lastAttendanceRecord = response.record
                    isSuccess = true
                    val actionLabel = if (shouldTimeOutNow) "Time Out" else "Time In"
                    statusMessage = buildClockActionHeadline(actionLabel, response, statusSuffix)
                    notificationDetails = buildClockActionDetails(response)
                }
                .onFailure {
                    isSuccess = false
                    statusMessage = if (shouldTimeOutNow) "Could not Time Out" else "Could not Time In"
                    notificationDetails = toFriendlyErrorMessage(it.message)
                }

            showNotification = true
        } catch (e: Exception) {
            isSuccess = false
            statusMessage = "We couldn't save your attendance"
            notificationDetails = toFriendlyErrorMessage(e.message)
            showNotification = true
        } finally {
            isLoading = false
        }
    }

    suspend fun syncBiometricState(enable: Boolean): Boolean {
        val userId = currentUserId
        if (userId == null) {
            Toast.makeText(context, "Cannot sync biometrics: user is not linked to backend", Toast.LENGTH_SHORT).show()
            return false
        }

        return if (enable) {
            val keyResult = localAuthManager.getOrCreateBiometricLoginKey()
            if (keyResult.isFailure) {
                Toast.makeText(
                    context,
                    keyResult.exceptionOrNull()?.message ?: "Failed to prepare biometric key",
                    Toast.LENGTH_SHORT
                ).show()
                false
            } else {
                repository.enrollBiometric(userId, keyResult.getOrThrow(), "android")
                    .onSuccess {
                        localAuthManager.setBiometricEnabled(true)
                        biometricEnabled = true
                    }
                    .onFailure {
                        Toast.makeText(context, "Biometric sync failed: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                    .isSuccess
            }
        } else {
            repository.disableBiometric(userId)
                .onSuccess {
                    localAuthManager.setBiometricEnabled(false)
                    localAuthManager.clearBiometricLoginKey()
                    biometricEnabled = false
                }
                .onFailure {
                    Toast.makeText(context, "Failed to disable biometric on server: ${it.message}", Toast.LENGTH_SHORT).show()
                }
                .isSuccess
        }
    }

    LaunchedEffect(currentUserId, biometricEnabled, activeBaseUrl) {
        val userId = currentUserId ?: return@LaunchedEffect
        if (!biometricEnabled) {
            return@LaunchedEffect
        }

        val keyResult = localAuthManager.getOrCreateBiometricLoginKey()
        if (keyResult.isSuccess) {
            repository.enrollBiometric(userId, keyResult.getOrThrow(), "android")
        }
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Settings") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Security")
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Display")
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Use 24-hour time")
                        Switch(
                            checked = use24HourFormat,
                            enabled = !isSettingsBusy,
                            onCheckedChange = { enabled ->
                                use24HourFormat = enabled
                                clockPrefs.edit().putBoolean(KEY_USE_24_HOUR_FORMAT, enabled).apply()
                            }
                        )
                    }

                    Text(
                        text = if (use24HourFormat) "Example: 14:30:00" else "Example: 02:30:00 PM",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Security")
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Enable biometric login")
                        Switch(
                            checked = biometricEnabled,
                            enabled = !isSettingsBusy,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    isSettingsBusy = true
                                    if (enabled) {
                                        val synced = syncBiometricState(true)
                                        if (!synced) {
                                            biometricEnabled = false
                                        }
                                    } else {
                                        val synced = syncBiometricState(false)
                                        if (!synced) {
                                            biometricEnabled = true
                                        }
                                    }
                                    isSettingsBusy = false
                                }
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Use phone PIN/password")
                        Switch(
                            checked = deviceCredentialEnabled,
                            enabled = !isSettingsBusy,
                            onCheckedChange = { enabled ->
                                deviceCredentialEnabled = enabled
                                localAuthManager.setDeviceCredentialEnabled(enabled)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Biometric unlock uses your device default biometric method.",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )

                    if (!canUsePhoneSecurityOrBiometric) {
                        Text(
                            text = "No phone lock-screen credential or biometrics detected. App PIN can still be used.",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(if (hasAppPin) "Change app PIN" else "Create app PIN")
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { value -> newPin = value.filter { it.isDigit() }.take(8) },
                        label = { Text("New PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { value -> confirmPin = value.filter { it.isDigit() }.take(8) },
                        label = { Text("Confirm PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            enabled = !isSettingsBusy,
                            onClick = {
                                if (!LocalAuthManager.isPinValid(newPin)) {
                                    Toast.makeText(context, "PIN must be 4 to 8 digits", Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                                if (newPin != confirmPin) {
                                    Toast.makeText(context, "PINs do not match", Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }

                                val savePinResult = localAuthManager.savePin(newPin)
                                if (savePinResult.isSuccess) {
                                    hasAppPin = true
                                    newPin = ""
                                    confirmPin = ""
                                    Toast.makeText(context, "App PIN saved", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        savePinResult.exceptionOrNull()?.message ?: "Failed to save PIN",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        ) {
                            Text("Save PIN")
                        }

                        if (hasAppPin) {
                            TextButton(
                                enabled = !isSettingsBusy,
                                onClick = {
                                    localAuthManager.removePin()
                                    hasAppPin = false
                                    newPin = ""
                                    confirmPin = ""
                                    Toast.makeText(context, "App PIN removed", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("Remove")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        enabled = !isSettingsBusy,
                        onClick = {
                            showSettingsDialog = false
                            AttendanceReminderScheduler.cancel(context.applicationContext)
                            onLogout()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE53935))
                    ) {
                        Text("Log Out")
                    }

                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isSettingsBusy,
                    onClick = {
                        showSettingsDialog = false
                        Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = Calendar.getInstance().time
            delay(1000)
        }
    }

    LaunchedEffect(showNotification) {
        if (showNotification) {
            notificationDragOffsetX = 0f
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                requestNotificationPermissionIfNeeded(context)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (hasLocationPermission(context) && isLocationServiceEnabled(context)) {
            val snapshot = getBestLastKnownLocation(context)
            if (snapshot != null) {
                updateLocationLabel(snapshot)
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (hasLocationPermission(context) && isLocationServiceEnabled(context)) {
                val snapshot = getBestLastKnownLocation(context)
                if (snapshot != null) {
                    updateLocationLabel(snapshot)
                }
            }
            delay(5000)
        }
    }

    DisposableEffect(context) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null || !hasLocationPermission(context)) {
            return@DisposableEffect onDispose {}
        }

        val listener = LocationListener { location ->
            val snapshot = toLocationSnapshot(location)
            scope.launch {
                updateLocationLabel(snapshot)
            }
        }

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).filter { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }

        providers.forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(provider, 3000L, 1f, listener)
            }
        }

        onDispose {
            runCatching { manager.removeUpdates(listener) }
        }
    }

    LaunchedEffect(user.employeeId, user.loginId) {
        val knownUserId = user.employeeId
        if (knownUserId != null) {
            currentUserId = knownUserId
            refreshClockStateForUser(knownUserId)
            return@LaunchedEffect
        }

        val usersResult = repository.getAllUsers()
        usersResult.onSuccess { users ->
            val normalizedIdentifier = user.loginId.trim()
            val dbUser = users.firstOrNull {
                it.username.trim().equals(normalizedIdentifier, ignoreCase = true)
                    || it.email?.trim()?.equals(normalizedIdentifier, ignoreCase = true) == true
                    || it.id?.toString() == normalizedIdentifier
            }
            if (dbUser?.id != null) {
                currentUserId = dbUser.id
                refreshClockStateForUser(dbUser.id)
            } else {
                isSuccess = false
                statusMessage = "Account not found"
                notificationDetails = "We couldn't find your account. Please sign in again or create an account first."
                showNotification = true
            }
        }.onFailure {
            isSuccess = false
            statusMessage = "Couldn't load your account"
            notificationDetails = toFriendlyErrorMessage(it.message)
            showNotification = true
        }
    }

    LaunchedEffect(currentUserId, actingEmployeeIdInput, canActForOtherEmployee) {
        val actorId = currentUserId ?: return@LaunchedEffect
        val targetId = if (canActForOtherEmployee) {
            actingEmployeeIdInput.toIntOrNull()?.takeIf { it > 0 } ?: actorId
        } else {
            actorId
        }
        refreshClockStateForUser(targetId)
    }

    LaunchedEffect(currentUserId, activeBaseUrl) {
        if (currentUserId != null) {
            AttendanceReminderScheduler.schedule(context.applicationContext)
        }
    }

    LaunchedEffect(currentUserId, actingEmployeeIdInput, canActForOtherEmployee, attendanceStateCode, lastAttendanceRecord?.id) {
        while (true) {
            maybeShowIdleAttendanceReminder()
            delay(60_000)
        }
    }

    val currentAttendanceStateLabel = mapAttendanceStateLabel(attendanceStateCode, isClockedIn)
    val shouldTimeOutAction = isStateClockedIn(attendanceStateCode, isClockedIn)
    val attendanceDoneForToday = isAttendanceCompleteForToday(attendanceStateCode)
    val clockActionLabel = when {
        attendanceDoneForToday -> "Done for Today"
        shouldTimeOutAction -> "Time Out"
        else -> "Time In"
    }
    val mainThemeColor = if (isClockedIn) Color(0xFF7E19D4) else Color(0xFF1A46B8)
    val statusCardBackground = if (isClockedIn) Color(0xFFF0F3FF) else Color(0xFFF6F8FF)
    val statusCardAccent = if (isClockedIn) Color(0xFF5D3FD3) else Color(0xFF1A46B8)
    val topInsetPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(modifier = Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
        Column(horizontalAlignment = Alignment.Start) {
            Spacer(modifier = Modifier.height(40.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(id = R.string.user_greeting, user.name),
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = mainThemeColor
                    )
                    Text(user.jobTitle, fontSize = 14.sp, color = Color.Gray)
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = stringResource(id = R.string.menu_description),
                            tint = Color.Black,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = {
                                showMenu = false
                                showSettingsDialog = true
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(60.dp))

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(280.dp)) {
                        drawCircle(color = Color(0xFFE8EFFF), style = Stroke(width = 45f))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(id = R.string.current_time_label), fontSize = 16.sp, color = Color.Gray)
                        Text(timeFormatter.format(currentTime), fontSize = 48.sp, fontWeight = FontWeight.Bold, color = mainThemeColor)
                        Text(dateFormatter.format(currentTime), fontSize = 14.sp, color = Color.Gray)
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = gpsLocationLabel,
                fontSize = 14.sp,
                color = Color.Black.copy(alpha = 0.8f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 42.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            if (canActForOtherEmployee) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = actingEmployeeIdInput,
                    onValueChange = { value ->
                        actingEmployeeIdInput = value.filter { it.isDigit() }.take(10)
                    },
                    label = { Text("Act for employee ID (optional)") },
                    supportingText = {
                        Text(
                            "Leave blank to log your own attendance. Fill this to log for another employee as HR/Admin.",
                            fontSize = 11.sp
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    if (attendanceDoneForToday) {
                        isSuccess = true
                        statusMessage = "Already completed"
                        notificationDetails = "Today's attendance is already complete. You can Time In again tomorrow."
                        showNotification = true
                    } else {
                        showClockActionConfirmDialog = true
                    }
                },
                modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = mainThemeColor),
                shape = RoundedCornerShape(12.dp),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(clockActionLabel, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
            }

            if (showClockActionConfirmDialog) {
                val actionText = if (shouldTimeOutAction) "Time Out" else "Time In"
                AlertDialog(
                    onDismissRequest = {
                        if (!isLoading) {
                            showClockActionConfirmDialog = false
                        }
                    },
                    title = { Text("Confirm $actionText") },
                    text = {
                        Text("Are you sure you want to $actionText now? This extra step helps prevent accidental taps.")
                    },
                    confirmButton = {
                        TextButton(
                            enabled = !isLoading,
                            onClick = {
                                showClockActionConfirmDialog = false
                                scope.launch {
                                    submitClockAction()
                                }
                            }
                        ) {
                            Text(actionText)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            enabled = !isLoading,
                            onClick = {
                                showClockActionConfirmDialog = false
                            }
                        ) {
                            Text("Cancel")
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                color = statusCardBackground,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, statusCardAccent.copy(alpha = 0.3f)),
                shadowElevation = 0.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isClockedIn) "Clock Active" else "Clock-In Zone",
                            color = statusCardAccent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Current State: $currentAttendanceStateLabel",
                            color = Color(0xFF23345B),
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isClockedIn) stringResource(id = R.string.status_timed_in) else stringResource(id = R.string.status_clock_in_area),
                            color = Color(0xFF23345B),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = if (isClockedIn) stringResource(id = R.string.instruction_clock_out) else stringResource(id = R.string.instruction_clock_in),
                            color = Color(0xFF3F4C6B),
                            fontSize = 12.sp
                        )
                    }

                    Image(
                        painter = painterResource(id = R.drawable.stopwatch),
                        contentDescription = stringResource(id = R.string.status_icon_description),
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }

        if (showNotification) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = topInsetPadding + 12.dp)
            ) {
                Surface(
                    color = if (isSuccess) Color(0xFF2E7D32) else Color(0xFFC62828),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(notificationDragOffsetX.roundToInt(), 0) }
                        .pointerInput(showNotification, statusMessage, notificationDetails, isSuccess) {
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { _, dragAmount ->
                                    notificationDragOffsetX += dragAmount
                                },
                                onDragEnd = {
                                    if (abs(notificationDragOffsetX) > 220f) {
                                        showNotification = false
                                    }
                                    notificationDragOffsetX = 0f
                                },
                                onDragCancel = {
                                    notificationDragOffsetX = 0f
                                }
                            )
                        },
                    shadowElevation = 8.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = statusMessage,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                if (notificationDetails.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = notificationDetails,
                                        color = Color.White.copy(alpha = 0.9f),
                                        fontSize = 13.sp
                                    )
                                }
                            }

                            IconButton(onClick = { showNotification = false }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss notification",
                                    tint = Color.White
                                )
                            }
                        }

                        Text(
                            text = "Swipe left or right to dismiss",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}



private fun buildEntryStatusString(entry: TimeInOut): String {
    val readableTime = entry.entry_time
        ?: entry.entry_time_ms?.let { millis ->
            SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.getDefault()).format(Date(millis))
        }

    val location = entry.location_time_in
    val entryTypeLabel = when (entry.entry_type) {
        1 -> "Timed in"
        2 -> "Timed out"
        3 -> "Started overtime"
        4 -> "Finished overtime"
        else -> "Attendance updated"
    }

    val parts = mutableListOf<String>()
    parts.add(entryTypeLabel)
    readableTime?.let { parts.add("at $it") }
    location?.takeIf { it.isNotBlank() }?.let { parts.add("near ${shortenLocationForPeople(it)}") }

    return if (parts.isNotEmpty()) parts.joinToString(" ") else "Attendance updated"
}

private fun inferAttendanceStateFromRecord(record: TimeInOut?, clockedIn: Boolean): String {
    val type = record?.entry_type
    return when (type) {
        1 -> "NORMAL_IN"
        2 -> "NORMAL_OUT"
        3 -> "OVERTIME_IN"
        4 -> "OVERTIME_OUT"
        else -> if (clockedIn) "NORMAL_IN" else "NO_RECORD"
    }
}

private fun mapAttendanceStateLabel(attendanceState: String?, clockedIn: Boolean): String {
    return when (attendanceState?.uppercase(Locale.US)) {
        "NORMAL_IN" -> "Normal Time In"
        "NORMAL_OUT" -> "Normal Time Out"
        "OVERTIME_IN" -> "Overtime In"
        "OVERTIME_OUT" -> "Overtime Out"
        "LUNCH_IN" -> "Lunch Break"
        "LUNCH_OUT" -> "Back From Lunch"
        "NO_RECORD" -> "No Entry Yet"
        else -> if (clockedIn) "Clocked In" else "Clocked Out"
    }
}

private fun isStateClockedIn(attendanceState: String?, fallbackClockedIn: Boolean): Boolean {
    return when (attendanceState?.uppercase(Locale.US)) {
        "NORMAL_IN", "OVERTIME_IN", "LUNCH_IN" -> true
        "NORMAL_OUT", "OVERTIME_OUT", "LUNCH_OUT", "NO_RECORD" -> false
        else -> fallbackClockedIn
    }
}

private fun isAttendanceCompleteForToday(attendanceState: String?): Boolean {
    return attendanceState?.uppercase(Locale.US) == "OVERTIME_OUT"
}

private fun buildClockActionHeadline(actionLabel: String, response: com.example.yoshiitimekeeping.database.LogTimeResponse, statusSuffix: String): String {
    return when {
        response.entry_created -> "$actionLabel saved$statusSuffix"
        response.clocked_in -> "No changes needed$statusSuffix"
        else -> "Already completed$statusSuffix"
    }
}

private fun buildClockActionDetails(response: com.example.yoshiitimekeeping.database.LogTimeResponse): String {
    val stateLabel = mapAttendanceStateLabel(response.attendance_state, response.clocked_in)
    val noChangeMessage = if (!response.entry_created && !response.overridden && !response.notice.isNullOrBlank()) {
        toFriendlyNotice(response.notice)
    } else {
        null
    }

    return listOfNotNull(
        "Current status: $stateLabel",
        noChangeMessage,
        buildEntryStatusString(response.record)
    ).joinToString(" | ")
}

private fun shortenLocationForPeople(location: String, maxLength: Int = 80): String {
    val clean = location.trim().replace("\\s+".toRegex(), " ")
    if (clean.length <= maxLength) {
        return clean
    }
    return clean.take(maxLength - 3).trimEnd() + "..."
}

private fun toFriendlyNotice(notice: String?): String? {
    val cleaned = notice?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lowered = cleaned.lowercase(Locale.US)
    return when {
        "already completed" in lowered -> "Your attendance is already complete for today"
        "already logged in" in lowered -> "You are already timed in"
        "already logged out" in lowered -> "You are already timed out"
        else -> cleaned.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }
}

private fun toFriendlyErrorMessage(message: String?): String {
    val cleaned = message?.trim().orEmpty()
    if (cleaned.isEmpty()) {
        return "Something went wrong. Please try again."
    }

    val lowered = cleaned.lowercase(Locale.US)
    return when {
        "unable to resolve host" in lowered || "failed to connect" in lowered || "timeout" in lowered ->
            "We couldn't reach the server. Please check your connection and try again."
        "must log in before log out" in lowered ->
            "Please Time In first before you Time Out."
        "not found" in lowered ->
            "We couldn't find that account. Please check and try again."
        else -> cleaned
    }
}

private fun formatDurationForPeople(durationMs: Long): String {
    val totalMinutes = (durationMs / 60000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        else -> "${minutes}m"
    }
}

private fun sanitizeLocationForStorage(rawLocation: String): String {
    val withoutIp = rawLocation.substringBefore(" IP", rawLocation).trim()
    return withoutIp.ifEmpty { rawLocation.trim() }
}

private fun canUseBiometricPrompt(context: Context): Boolean {
    return BiometricManager.from(context)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
}

private fun canUseBiometricOrDeviceCredential(context: Context): Boolean {
    return BiometricManager.from(context).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    ) == BiometricManager.BIOMETRIC_SUCCESS
}

private fun buildSecureUnlockLabel(
    deviceCredentialEnabled: Boolean
): String {
    if (deviceCredentialEnabled) {
        return "Unlock with Phone Security"
    }

    return "Unlock with Biometrics"
}

private fun canManageAttendanceForOthers(user: User): Boolean {
    val title = user.jobTitle.lowercase(Locale.US)
    val login = user.loginId.lowercase(Locale.US)
    val hasHrWord = Regex("\\bhr\\b").containsMatchIn(title)

    return title.contains("admin")
        || title.contains("administrator")
        || title.contains("human resource")
        || hasHrWord
        || title.contains("manager")
        || login.startsWith("admin@")
        || login.startsWith("hr@")
}

private fun openAppDetailsSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = android.net.Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Unable to open app settings on this device", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun requestNotificationPermissionIfNeeded(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return
    }

    val hasPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
    if (hasPermission) {
        return
    }

    val activity = context.findActivity() ?: return
    ActivityCompat.requestPermissions(
        activity,
        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
        1001
    )
}

private fun hasLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

private fun isLocationServiceEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
}

private data class LocationSnapshot(
    val label: String,
    val latitude: Double,
    val longitude: Double
)

private fun toLocationSnapshot(location: Location): LocationSnapshot {
    val latitude = location.latitude
    val longitude = location.longitude
    val label = "Location captured"
    return LocationSnapshot(label = label, latitude = latitude, longitude = longitude)
}

private suspend fun resolveHumanReadableLocation(
    context: Context,
    latitude: Double,
    longitude: Double
): String? = withContext(Dispatchers.IO) {
    if (!Geocoder.isPresent()) {
        return@withContext null
    }

    @Suppress("DEPRECATION")
    val addresses = runCatching {
        Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)
    }.getOrNull()

    val address = addresses?.firstOrNull() ?: return@withContext null

    val fullLine = address.getAddressLine(0)?.trim()?.takeIf { it.isNotBlank() }
    if (!fullLine.isNullOrBlank()) {
        return@withContext fullLine
    }

    val parts = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    listOf(
        address.subThoroughfare,
        address.thoroughfare,
        address.subLocality,
        address.locality,
        address.subAdminArea,
        address.adminArea,
        address.postalCode,
        address.countryName
    ).forEach { value ->
        val cleaned = value?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
        val key = cleaned.lowercase(Locale.US)
        if (seen.add(key)) {
            parts.add(cleaned)
        }
    }

    parts.joinToString(", ").takeIf { it.isNotBlank() }
}

private fun getBestLastKnownLocation(context: Context): LocationSnapshot? {
    if (!hasLocationPermission(context)) {
        return null
    }

    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    ) + manager.allProviders

    val bestLocation: Location = providers
        .asSequence()
        .distinct()
        .mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }
        .maxByOrNull { it.time }
        ?: return null

    return toLocationSnapshot(bestLocation)
}

private fun buildDeviceName(): String {
    val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
    val model = Build.MODEL?.trim().orEmpty()
    val raw = listOf(manufacturer, model)
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .ifBlank { "Android Device" }
    return raw.take(255)
}

private tailrec fun Context.findActivity(): FragmentActivity? {
    return when (this) {
        is FragmentActivity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}