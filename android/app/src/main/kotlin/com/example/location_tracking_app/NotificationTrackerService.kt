package com.example.location_tracking_app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.location_tracking_app.db.AppDatabase
import com.example.location_tracking_app.db.NotificationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationTrackerService : NotificationListenerService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var db: AppDatabase

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getDatabase(this)
        Log.d("NotificationTracker", "Service created.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        // Ignore notifications from this app to prevent loops
        if (sbn.packageName == applicationContext.packageName) {
            return
        }

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (title.isNotBlank() || text.isNotBlank()) {
            val notificationEntity = NotificationEntity(
                packageName = sbn.packageName,
                title = title,
                text = text
            )

            scope.launch {
                db.notificationDao().insert(notificationEntity)
                Log.d("NotificationTracker", "Saved notification from ${sbn.packageName}")
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("NotificationTracker", "Listener connected.")
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel() // Cancel coroutines when the service is destroyed
        Log.d("NotificationTracker", "Service destroyed.")
    }
}
