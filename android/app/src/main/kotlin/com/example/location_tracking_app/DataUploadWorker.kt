package com.example.location_tracking_app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class DataUploadWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "DataUploadWorker"
    }
    
    override suspend fun doWork(): Result {
        Log.d(WORK_NAME, "Worker starting...")

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(WORK_NAME, "Location permission not granted. Stopping worker.")
            return Result.failure()
        }

        return try {
            val dataHandler = DataHandler(context)
            // For background work, we want to clear notifications after sending.
            val success = dataHandler.gatherAndSendData(clearNotificationsAfterSend = true)
            
            if (success) {
                Log.d(WORK_NAME, "Worker finished successfully.")
                Result.success()
            } else {
                Log.w(WORK_NAME, "Data handling/sending failed. Retrying later.")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(WORK_NAME, "Worker failed with an exception", e)
            Result.retry()
        }
    }
}
