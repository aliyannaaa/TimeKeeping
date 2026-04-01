package com.example.yoshiitimekeeping

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.yoshiitimekeeping.data.TimeEntry
//import com.example.yoshiitimekeeping.data.TimeEntryManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import com.example.yoshiitimekeeping.data.MockDatabase // ADD THIS IMPORT
import com.example.yoshiitimekeeping.data.User         // ADD THIS IMPORT
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

class MainActivity : ComponentActivity() {
    //private val loginService: LoginService = MockLoginService()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                var currentScreen by remember { mutableStateOf("login") }
                var loggedInUser by remember { mutableStateOf<User?>(null) }

                when (currentScreen) {
                    "login" -> LoginScreen(
                        //loginService = loginService,
                        onLoginSuccess = { user ->
                            loggedInUser = user
                            currentScreen = "loading"
                        }
                    )
                    "loading" -> LoadingScreen(onFinished = { currentScreen = "clockin" })
                    "clockin" -> loggedInUser?.let { ClockInScreen(it) }
                }
            }
        }
    }
}

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
fun LoginScreen(onLoginSuccess: (User) -> Unit) { // Removed loginService parameter
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF8F9FF)).padding(24.dp)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(stringResource(id = R.string.app_title), fontSize = 36.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A46B8))
            Spacer(modifier = Modifier.height(48.dp))

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
                        // --- START OF MOCK DATABASE CALL ---
                        delay(1500) // Simulate the "MySQL Query" time

                        val user = MockDatabase.validateLogin(email, password)

                        isLoading = false

                        if (user != null) {
                            // If user is found (not null), proceed to loading screen
                            onLoginSuccess(user)
                        } else {
                            // If null, show a Toast message
                            Toast.makeText(
                                context,
                                context.getString(R.string.login_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        // --- END OF MOCK DATABASE CALL ---
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A46B8)),
                shape = RoundedCornerShape(12.dp),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Text(stringResource(id = R.string.login_button), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
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
fun ClockInScreen(user: User) {
    var currentTime by remember { mutableStateOf(Calendar.getInstance().time) }
    val timeEntryManager = MockDatabase.timeEntryManager
    var isClockedIn by remember{
        mutableStateOf(timeEntryManager.getLastEntry(user.email)?.entryType == TimeEntry.EntryType.TIME_IN) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var showNotification by remember { mutableStateOf(false) }
    var isSuccess by remember { mutableStateOf(false) }
    var notificationDetails by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    //val context = LocalContext.current

// --- HELPER FUNCTION FOR MYSQL SYNC ---
    suspend fun syncToMySQL(entry: TimeEntry, email: String, type: String) {
        withContext(Dispatchers.IO) {
            try {
                val url = URL("http://10.0.2.2/yoshii/save_attendance.php")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true

                // Formatting data for the PHP $_POST variables
                val postData = "employee_id=${URLEncoder.encode(email, "UTF-8")}" +
                        "&timestamp=${entry.timestamp}" +
                        "&entry_type=${type}"
                        //"&location=${URLEncoder.encode("Cebu City, Cebu", "UTF-8")}" +
                        //"&ip_address=10.0.2.2"

                conn.outputStream.use { it.write(postData.toByteArray()) }

                val response = conn.inputStream.bufferedReader().use { it.readText() }
                println("MYSQL_SYNC_RESPONSE: $response")
            } catch (e: Exception) {
                println("MYSQL_SYNC_ERROR: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val dateFormatter = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault())

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = Calendar.getInstance().time
            delay(1000)
        }
    }

    val mainThemeColor = if (isClockedIn) Color(0xFF7E19D4) else Color(0xFF1A46B8)
    val statusBarBgColor = Color(0xFF9B69FF)

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
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = stringResource(id = R.string.menu_description),
                    tint = Color.Black,
                    modifier = Modifier.size(28.dp)
                )
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
                text = stringResource(id = R.string.location_info),
                fontSize = 14.sp,
                color = Color.Black.copy(alpha = 0.8f),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        val entryType = if (isClockedIn) TimeEntry.EntryType.TIME_OUT else TimeEntry.EntryType.TIME_IN

                        val result = timeEntryManager.clockInOut(
                            entryType = entryType,
                            employeeId = user.email,
                            //location = "Cebu City, Cebu",
                            //ipAddress = "13131.832.06357"
                        )
                        if (result.success) {
                            //println("DEBUG: Log saved for ${user.email} at ${result.entry?.timestamp}")
                            result.entry?.let { entry ->
                                syncToMySQL(entry, user.email, entryType.name)
                            }
                            isClockedIn = !isClockedIn
                            isSuccess = true
                            statusMessage = "${entryType.name.replace("_", " ")} Successful"
                            notificationDetails = buildEntryStatusString(result)
                        } else {
                            isSuccess = false
                            statusMessage = result.errorMessage ?: "Operation failed"
                            notificationDetails = ""
                        }
                        
                        isLoading = false
                        showNotification = true
                        
                        // Auto-hide after 5 seconds
                        delay(5000)
                        showNotification = false
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
                    Text(if (isClockedIn) "Time Out" else "Time In", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status bar with Row added to include the icon on the right
            Surface(
                color = statusBarBgColor,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isClockedIn) stringResource(id = R.string.status_timed_in) else stringResource(id = R.string.status_clock_in_area),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = if (isClockedIn) stringResource(id = R.string.instruction_clock_out) else stringResource(id = R.string.instruction_clock_in),
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp
                        )
                    }

                    Image(
                        painter = painterResource(id = R.drawable.stopwatch),
                        contentDescription = stringResource(id = R.string.status_icon_description),
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }
        
        // Notification overlay at bottom
        if (showNotification) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Surface(
                    color = if (isSuccess) Color(0xFF4CAF50) else Color(0xFFE53935),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
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
                            
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        color = Color.White,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}



private fun buildEntryStatusString(result: com.example.yoshiitimekeeping.data.TimeClockResult): String {
    return if (result.entry != null) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(result.entry.timestamp)
        "Entry ID: ${result.entry.id.take(8)}... at $time"
    } else {
        "Error: ${result.errorMessage ?: "Unknown error occurred"}"
    }
}