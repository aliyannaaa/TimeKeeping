package com.example.yoshiitimekeeping.database

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val PREFS_NAME = "timekeeper_api_settings"
    private const val KEY_WORKING_BASE_URL = "working_base_url"
    private const val DEFAULT_PORT = 3000

    // Default for Android Studio emulator.
    private const val EMULATOR_BASE_URL = "http://10.0.2.2:3000/"
    // Works for physical devices when adb reverse is active.
    private const val LOOPBACK_BASE_URL = "http://127.0.0.1:3000/"
    // Preferred LAN hosts to probe first on physical phones.
    private val PREFERRED_LAN_HOSTS = listOf("192.168.1.190")

    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(900, TimeUnit.MILLISECONDS)
        .readTimeout(900, TimeUnit.MILLISECONDS)
        .writeTimeout(900, TimeUnit.MILLISECONDS)
        .build()

    private fun createApi(baseUrl: String): TimekeeperApi {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TimekeeperApi::class.java)
    }

    fun repository(baseUrl: String): TimekeeperRepository {
        return TimekeeperRepository(createApi(baseUrl))
    }

    fun repository(context: Context): TimekeeperRepository {
        return repository(getPreferredBaseUrl(context))
    }

    fun getPreferredBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = normalizeBaseUrl(prefs.getString(KEY_WORKING_BASE_URL, null))
        return when {
            saved != null -> saved
            isEmulator() -> EMULATOR_BASE_URL
            else -> "http://${PREFERRED_LAN_HOSTS.first()}:$DEFAULT_PORT/"
        }
    }

    suspend fun resolveAndPersistBestBaseUrl(context: Context): String? {
        val cached = getPreferredBaseUrl(context)
        if (probeBaseUrl(cached)) {
            return cached
        }

        val discovered = discoverBestBaseUrl()
        if (discovered != null) {
            saveWorkingBaseUrl(context, discovered)
            return discovered
        }

        return null
    }

    private suspend fun discoverBestBaseUrl(): String? {
        val initialCandidates = linkedSetOf<String>()
        initialCandidates.add(if (isEmulator()) EMULATOR_BASE_URL else LOOPBACK_BASE_URL)
        initialCandidates.add(EMULATOR_BASE_URL)
        initialCandidates.add(LOOPBACK_BASE_URL)
        PREFERRED_LAN_HOSTS.forEach { host ->
            initialCandidates.add("http://$host:$DEFAULT_PORT/")
        }

        val localPrefixes = getLocalIpv4Prefixes()
        localPrefixes.forEach { localPrefix ->
            listOf(2, 10, 20, 50, 100, 101, 102, 150, 190, 200, 254).forEach { host ->
                initialCandidates.add("http://$localPrefix.$host:$DEFAULT_PORT/")
            }
        }

        // Common local network gateway ranges to improve phone-side auto-discovery.
        listOf("192.168.1", "192.168.0", "10.0.0", "10.0.1").forEach { prefix ->
            initialCandidates.add("http://$prefix.1:$DEFAULT_PORT/")
            initialCandidates.add("http://$prefix.100:$DEFAULT_PORT/")
            initialCandidates.add("http://$prefix.190:$DEFAULT_PORT/")
        }

        for (candidate in initialCandidates) {
            if (probeBaseUrl(candidate)) {
                return candidate
            }
        }

        // Final pass: scan whole subnet for backend if on a physical phone and same Wi-Fi.
        if (!isEmulator() && localPrefixes.isNotEmpty()) {
            for (prefix in localPrefixes) {
                val discovered = scanSubnetForBackend(prefix)
                if (discovered != null) {
                    return discovered
                }
            }
        }

        return null
    }

    private suspend fun scanSubnetForBackend(prefix: String): String? {
        val hosts = (1..254).toList().chunked(24)
        for (chunk in hosts) {
            val match = coroutineScope {
                chunk.map { host ->
                    async {
                        val candidate = "http://$prefix.$host:$DEFAULT_PORT/"
                        if (probeBaseUrl(candidate)) candidate else null
                    }
                }.awaitAll().firstOrNull { it != null }
            }

            if (match != null) {
                return match
            }
        }

        return null
    }

    private suspend fun probeBaseUrl(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl) ?: return@withContext false
        val probePaths = listOf("health", "credentials")

        probePaths.any { path ->
            val request = Request.Builder()
                .url(normalized + path)
                .get()
                .build()

            try {
                probeClient.newCall(request).execute().use { response ->
                    response.isSuccessful
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun saveWorkingBaseUrl(context: Context, baseUrl: String) {
        val normalized = normalizeBaseUrl(baseUrl) ?: return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_WORKING_BASE_URL, normalized).apply()
    }

    private fun getLocalIpv4Prefixes(): Set<String> {
        val prefixes = linkedSetOf<String>()

        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.forEach { addr ->
                if (addr !is Inet4Address || addr.isLoopbackAddress) {
                    return@forEach
                }

                val hostAddress = addr.hostAddress ?: return@forEach
                val pieces = hostAddress.split('.')
                if (pieces.size != 4) {
                    return@forEach
                }

                val candidatePrefix = "${pieces[0]}.${pieces[1]}.${pieces[2]}"
                // Keep private network ranges only; carrier/mobile data ranges are often unreachable to local PC.
                val isPrivateRange = candidatePrefix.startsWith("192.168.")
                    || candidatePrefix.startsWith("10.")
                    || Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\.").containsMatchIn(candidatePrefix)

                if (isPrivateRange) {
                    prefixes.add(candidatePrefix)
                }
            }

        return prefixes
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.lowercase().contains("emulator")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for x86")
    }

    private fun normalizeBaseUrl(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val prefixed = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "http://$trimmed"
        }

        val hasHost = Regex("^https?://[^/]+", RegexOption.IGNORE_CASE).containsMatchIn(prefixed)
        if (!hasHost) {
            return null
        }

        return if (prefixed.endsWith('/')) prefixed else "$prefixed/"
    }
}
