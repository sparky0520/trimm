package ai.trimm.mobile.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import ai.trimm.mobile.domain.MobileProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaExportWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            createNotificationChannel()
            setForeground(createForegroundInfo("Starting export..."))
            
            // Perform the export via ExportCoordinator (dummy progress for now)
            for (i in 1..100) {
                setForeground(createForegroundInfo("Exporting... $i%"))
                kotlinx.coroutines.delay(100)
            }
            
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }

    private fun createForegroundInfo(progressText: String): ForegroundInfo {
        val notificationId = 1
        
        val notification = NotificationCompat.Builder(applicationContext, "export_channel")
            .setContentTitle("Trimm Export")
            .setContentText(progressText)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setOngoing(true)
            .build()
            
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId, 
                notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "export_channel",
                "Export Notifications",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }
}
