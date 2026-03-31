package com.example.yoshiitimekeeping

import android.os.Bundle
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.yoshiitimekeeping.data.TimeEntry
import com.example.yoshiitimekeeping.data.TimeEntryManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                var currentScreen by remember { mutableStateOf("login") }

                when (currentScreen) {
                    "login" -> LoginScreen(onLoginClick = { currentScreen = "loading" })
                    "loading" -> LoadingScreen(onFinished = { currentScreen = "clockin" })
                    "clockin" -> ClockInScreen()
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
            text = "YOSHIIDESK",
            fontSize = 36.sp,
            fontWeight = FontWeight.Black,
            color = Color(0xFF1A46B8),
            modifier = Modifier.align(Alignment.Center)
        )
        Text(
            text = "Copyright © Yoshii Software Solution Philippines",
            fontSize = 10.sp,
            color = Color.Gray,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
        )
    }
}

@Composable
fun LoginScreen(onLoginClick: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF8F9FF)).padding(24.dp)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("YOSHIIDESK", fontSize = 36.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A46B8))
            Spacer(modifier = Modifier.height(48.dp))
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("Email") }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp), singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password") }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                visualTransformation = PasswordVisualTransformation(), singleLine = true
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { onLoginClick() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A46B8)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Log In", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            text = "Copyright © Yoshii Software Solution Philippines",
            fontSize = 10.sp,
            color = Color.Gray,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
        )
    }
}

@Composable
fun ClockInScreen() {
    var currentTime by remember { mutableStateOf(Calendar.getInstance().time) }
    val timeEntryManager = remember { TimeEntryManager() }
    var isClockedIn by remember { mutableStateOf(false) }
    var lastEntryStatus by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var showStatusMessage by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var isSuccess by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
                    Text("Hi, Gerard Mamon", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = mainThemeColor)
                    Text("UI/UX Designer", fontSize = 14.sp, color = Color.Gray)
                }
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Menu",
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
                        Text("Current Time", fontSize = 16.sp, color = Color.Gray)
                        Text(timeFormatter.format(currentTime), fontSize = 48.sp, fontWeight = FontWeight.Bold, color = mainThemeColor)
                        Text(dateFormatter.format(currentTime), fontSize = 14.sp, color = Color.Gray)
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Cebu City, Cebu IP 13131.832.06357",
                fontSize = 14.sp,
                color = Color.Black.copy(alpha = 0.8f),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(40.dp))

            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        val entryType = if (isClockedIn) TimeEntry.EntryType.TIME_OUT else TimeEntry.EntryType.TIME_IN
                        val result = timeEntryManager.clockInOut(
                            entryType = entryType,
                            employeeId = "EMP001",
                            location = "Cebu City, Cebu",
                            ipAddress = "13131.832.06357"
                        )

                        statusMessage = if (result.success) {
                            isClockedIn = !isClockedIn
                            isSuccess = true
                            "${entryType.name.replace("_", " ")} Successful"
                        } else {
                            isSuccess = false
                            result.errorMessage ?: "Operation failed"
                        }
                        lastEntryStatus = buildEntryStatusString(result)
                        showStatusMessage = true
                        isLoading = false

                        // Auto-hide message after 3 seconds
                        delay(5000)
                        showStatusMessage = false
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

            // Status message feedback
            if (showStatusMessage) {
                Surface(
                    color = if (isSuccess) Color(0xFF4CAF50) else Color(0xFFE53935),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = statusMessage,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        if (lastEntryStatus.isNotEmpty()) {
                            Text(
                                text = lastEntryStatus,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Status bar with Row for current state indicator
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
                            text = if (isClockedIn) "You are currently timed in!" else "You are in the clock-in area!",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = if (isClockedIn) "Now you can press clock out in this area" else "Now you can press clock in in this area",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp
                        )
                    }

                    Image(
                        painter = painterResource(id = R.drawable.stopwatch),
                        contentDescription = "Status Icon",
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }
    }
}

private fun buildEntryStatusString(result: com.example.yoshiitimekeeping.data.TimeClockResult): String {
    return if (result.entry != null) {
        "Entry ID: ${result.entry.id.take(8)}... at ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(result.entry.timestamp)}"
    } else {
        ""
    }
}