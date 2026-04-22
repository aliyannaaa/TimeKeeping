package com.example.yoshiitimekeeping.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.yoshiitimekeeping.MainActivity
import com.example.yoshiitimekeeping.R
import com.example.yoshiitimekeeping.database.ApiClient
import com.example.yoshiitimekeeping.database.TimeInOut
import com.example.yoshiitimekeeping.security.LocalAuthManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object AttendanceReminderScheduler {
    private const val WORK_NAME = "attendance_reminder_work"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<AttendanceReminderWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

class AttendanceReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val localAuthManager = LocalAuthManager(applicationContext)
        val savedUser = localAuthManager.getSavedUser() ?: return Result.success()
        val userId = savedUser.employeeId ?: return Result.success()

        val stateResult = ApiClient.repository(applicationContext).getClockState(userId)
        val state = stateResult.getOrElse { return Result.success() }

        val stateCode = (state.attendance_state
            ?: inferAttendanceStateFromRecord(state.last_record, state.clocked_in))
            .uppercase(Locale.US)

        val reminder = buildReminderForState(
            context = applicationContext,
            stateCode = stateCode,
            lastRecord = state.last_record,
            nowMs = System.currentTimeMillis()
        ) ?: return Result.success()

        showNotification(applicationContext, reminder)
        return Result.success()
    }

    private fun buildReminderForState(
        context: Context,
        stateCode: String,
        lastRecord: TimeInOut?,
        nowMs: Long
    ): ReminderPayload? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
        val now = java.util.Calendar.getInstance()

        if (stateCode == "NO_RECORD") {
            val lateMorningOrAfter = now.get(java.util.Calendar.HOUR_OF_DAY) >= 10
            val alreadyRemindedToday = prefs.getString(KEY_LAST_TIME_IN_REMINDER_DAY, null) == todayKey
            if (lateMorningOrAfter && !alreadyRemindedToday) {
                prefs.edit().putString(KEY_LAST_TIME_IN_REMINDER_DAY, todayKey).apply()
                return ReminderPayload(
                    id = 1001,
                    title = "Time In Reminder",
                    message = "You have not timed in today yet. If you are already working, please Time In."
                )
            }
            return null
        }

        if (stateCode != "NORMAL_IN" && stateCode != "OVERTIME_IN") {
            return null
        }

        val inTimeMs = lastRecord?.entry_time_ms ?: return null
        val elapsedMs = nowMs - inTimeMs
        if (elapsedMs <= 0L) {
            return null
        }

        val thresholdMs = if (stateCode == "OVERTIME_IN") {
            2L * 60L * 60L * 1000L
        } else {
            9L * 60L * 60L * 1000L
        }

        if (elapsedMs < thresholdMs) {
            return null
        }

        val recordKey = lastRecord.id ?: "$stateCode-$inTimeMs"
        val previousRecordKey = prefs.getString(KEY_LAST_TIME_OUT_REMINDER_RECORD, null)
        val lastReminderAtMs = prefs.getLong(KEY_LAST_TIME_OUT_REMINDER_AT_MS, 0L)
        val canRepeat = nowMs - lastReminderAtMs >= (60L * 60L * 1000L)

        if (recordKey != previousRecordKey || canRepeat) {
            prefs.edit()
                .putString(KEY_LAST_TIME_OUT_REMINDER_RECORD, recordKey)
                .putLong(KEY_LAST_TIME_OUT_REMINDER_AT_MS, nowMs)
                .apply()

            val durationLabel = formatDuration(elapsedMs)
            val body = if (stateCode == "OVERTIME_IN") {
                "You've been on overtime for $durationLabel. Don't forget to Time Out when you finish."
            } else {
                "You've been timed in for $durationLabel. Don't forget to Time Out."
            }

            return ReminderPayload(
                id = 1002,
                title = "Time Out Reminder",
                message = body
            )
        }

        return null
    }

    private fun showNotification(context: Context, payload: ReminderPayload) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return
            }
        }

        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            payload.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(payload.title)
            .setContentText(payload.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(payload.id, notification)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Attendance Reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Reminders for missed Time In/Time Out actions"
        }
        manager.createNotificationChannel(channel)
    }

    private fun inferAttendanceStateFromRecord(record: TimeInOut?, clockedIn: Boolean): String {
        return when (record?.entry_type) {
            1 -> "NORMAL_IN"
            2 -> "NORMAL_OUT"
            3 -> "OVERTIME_IN"
            4 -> "OVERTIME_OUT"
            else -> if (clockedIn) "NORMAL_IN" else "NO_RECORD"
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalMinutes = (durationMs / 60000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
            hours > 0L -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    private data class ReminderPayload(
        val id: Int,
        val title: String,
        val message: String
    )

    companion object {
        private const val PREFS_NAME = "attendance_reminder_prefs"
        private const val KEY_LAST_TIME_IN_REMINDER_DAY = "last_time_in_reminder_day"
        private const val KEY_LAST_TIME_OUT_REMINDER_RECORD = "last_time_out_reminder_record"
        private const val KEY_LAST_TIME_OUT_REMINDER_AT_MS = "last_time_out_reminder_at_ms"
        private const val CHANNEL_ID = "attendance_reminders"
    }
}
