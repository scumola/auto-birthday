package com.autobirthday

import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

// Self-update for side-loaded builds: polls version.json on badcheese.com and offers an
// in-app APK download/install when a newer versionCode is published. Modeled on the geonote
// app's UpdateChecker, but uses java.net.URL + org.json so no extra dependencies are needed.
// NOTE: only meaningful for side-loaded distribution — a Play Store build must not phone home
// over http, so this whole feature is dropped if/when the app moves to the Play Store.
private const val TAG = "UpdateChecker"
private const val VERSION_URL = "http://www.badcheese.com/autobirthday/version.json"

private data class VersionInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String
)

object UpdateChecker {

    // Step 1: fetch version.json off the main thread, then hop back to the UI thread to prompt.
    fun check(activity: AppCompatActivity) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val info = fetchVersionInfo() ?: return@launch
            Log.d(TAG, "Local: ${BuildConfig.VERSION_CODE}, Remote: ${info.versionCode}")
            if (info.versionCode <= BuildConfig.VERSION_CODE) return@launch
            withContext(Dispatchers.Main) {
                showUpdateDialog(activity, info)
            }
        }
    }

    private fun fetchVersionInfo(): VersionInfo? {
        return try {
            // Send an explicit Host header so the internal /etc/hosts override for
            // www.badcheese.com resolves to the tools server's Apache vhost.
            val conn = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Host", "www.badcheese.com")
                connectTimeout = 5000
                readTimeout = 5000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            VersionInfo(
                versionCode = json.getInt("version_code"),
                versionName = json.getString("version_name"),
                apkUrl = json.getString("apk_url"),
                changelog = json.optString("changelog", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch version.json: ${e.message}")
            null
        }
    }

    private fun showUpdateDialog(activity: AppCompatActivity, info: VersionInfo) {
        AlertDialog.Builder(activity)
            .setTitle("Update Available — v${info.versionName}")
            .setMessage(if (info.changelog.isNotEmpty()) info.changelog else "A new version is available.")
            .setPositiveButton("Update") { _, _ ->
                activity.lifecycleScope.launch {
                    downloadAndInstall(activity, info.apkUrl)
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    // Step 2: download the APK to cache, then hand it to the system installer via FileProvider.
    private suspend fun downloadAndInstall(activity: AppCompatActivity, apkUrl: String) {
        val cacheDir = activity.cacheDir
        val apkFile = withContext(Dispatchers.IO) { downloadApk(apkUrl, cacheDir) } ?: return
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun downloadApk(apkUrl: String, cacheDir: File): File? {
        return try {
            val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Host", "www.badcheese.com")
                connectTimeout = 5000
                readTimeout = 30000
            }
            val apkFile = File(cacheDir, "update.apk")
            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "APK downloaded to ${apkFile.absolutePath}")
            apkFile
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            null
        }
    }
}
