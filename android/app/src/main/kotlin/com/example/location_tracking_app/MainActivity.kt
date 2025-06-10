package com.example.location_tracking_app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.location_tracking_app.db.AppDatabase
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MainActivity : FlutterActivity() {
    private val BACKGROUND_CHANNEL = "com.example.location_tracking_app/background"
    private val NOTIFICATION_CHANNEL = "com.example.location_tracking_app/notifications"
    // NEW Channel for device name and fetching DB data
    private val DATA_CHANNEL = "com.example.location_tracking_app/data"

    // Define constants for SharedPreferences
    companion object {
        const val PREFS_NAME = "DeviceTrackerPrefs"
        const val KEY_DEVICE_NAME = "DeviceName"
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Handler for starting/stopping the background worker
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, BACKGROUND_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startPeriodicDataUpload" -> {
                    startPeriodicWorker()
                    result.success("Periodic work started")
                }
                "stopPeriodicDataUpload" -> {
                    stopPeriodicWorker()
                    result.success("Periodic work stopped")
                }
                else -> result.notImplemented()
            }
        }

        // Handler for notification permissions
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, NOTIFICATION_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "checkNotificationPermission" -> {
                    result.success(isNotificationServiceEnabled())
                }
                "requestNotificationPermission" -> {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    startActivity(intent)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        // NEW: Handler for device name and notification data
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, DATA_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getDeviceName" -> {
                    val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val name = prefs.getString(KEY_DEVICE_NAME, "") ?: ""
                    result.success(name)
                }
                "setDeviceName" -> {
                    val name = call.argument<String>("name")
                    val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putString(KEY_DEVICE_NAME, name).apply()
                    result.success(null)
                }
                "getStoredNotifications" -> {
                    // Launch a coroutine to fetch data from the database
                    CoroutineScope(Dispatchers.IO).launch {
                        val db = AppDatabase.getDatabase(applicationContext)
                        val notifications = db.notificationDao().getAll()
                        // Map entity to a list of maps for Dart
                        val notificationMaps = notifications.map {
                            mapOf(
                                "id" to it.id,
                                "packageName" to it.packageName,
                                "title" to it.title,
                                "text" to it.text,
                                "timestamp" to it.timestamp
                            )
                        }
                        // Switch back to the main thread to send the result
                        withContext(Dispatchers.Main) {
                            result.success(notificationMaps)
                        }
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, NotificationTrackerService::class.java)
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabledListeners != null && enabledListeners.contains(cn.flattenToString())
    }

    private fun startPeriodicWorker() {
        val dataUploadWorkRequest = PeriodicWorkRequestBuilder<DataUploadWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            DataUploadWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            dataUploadWorkRequest
        )
    }

    private fun stopPeriodicWorker() {
        WorkManager.getInstance(applicationContext).cancelUniqueWork(DataUploadWorker.WORK_NAME)
    }
}
