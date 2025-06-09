package com.example.location_tracking_app

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.example.location_tracking_app/launcher"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            if (call.method == "hideLauncherIcon") {
                val success = hideLauncherIcon()
                if (success) {
                    result.success(true)
                } else {
                    result.error("UNAVAILABLE", "Failed to hide launcher icon", null)
                }
            } else {
                result.notImplemented()
            }
        }
    }

    private fun hideLauncherIcon(): Boolean {
        return try {
            val pm: PackageManager = applicationContext.packageManager
            val componentName = ComponentName(
                applicationContext,
                "${applicationContext.packageName}.MainActivity"
            )
            pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
