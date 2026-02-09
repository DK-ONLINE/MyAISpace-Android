package com.openclaw.assistant.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL

class AppUpdateChecker {

    // NOTE: This URL should point to a text file containing ONLY the latest version string (e.g., "1.0.1")
    private val LATEST_VERSION_URL = "https://raw.githubusercontent.com/DK-ONLINE/MyAISpace-Android/main/latest_version.txt"
    private val DOWNLOAD_LINK = "https://github.com/DK-ONLINE/MyAISpace-Android/releases/latest"

    // This should be pulled from the app's BuildConfig.VERSION_NAME
    val CURRENT_VERSION = "0.9.0-alpha" 

    data class UpdateResult(
        val needsUpdate: Boolean,
        val current: String,
        val latest: String?,
        val downloadUrl: String
    )

    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        var latestVersion: String? = null
        var needsUpdate = false
        
        try {
            val url = URL(LATEST_VERSION_URL)
            val reader = BufferedReader(InputStreamReader(url.openStream()))
            latestVersion = reader.readLine()?.trim()
            reader.close()

            if (!latestVersion.isNullOrEmpty() && latestVersion != CURRENT_VERSION) {
                // Simplified comparison (e.g., "1.0.0" > "0.9.0")
                if (latestVersion.compareTo(CURRENT_VERSION) > 0) {
                    needsUpdate = true
                }
            }

        } catch (e: Exception) {
            // Log error: Failed to fetch latest version
            latestVersion = null
        }

        return@withContext UpdateResult(
            needsUpdate = needsUpdate,
            current = CURRENT_VERSION,
            latest = latestVersion,
            downloadUrl = DOWNLOAD_LINK
        )
    }
}