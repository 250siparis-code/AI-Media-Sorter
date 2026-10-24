package com.aimediasorter.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.aimediasorter.app.R

object WorkerNotifications {
    private const val CHANNEL = "ai_media_sorter_operations"
    fun create(context: Context, transferring: Boolean): ForegroundInfo {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            context.getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(if (transferring) R.string.transfer_title else R.string.progress_analyzing))
            .setSmallIcon(R.drawable.ic_launcher_foreground).setOngoing(true).build()
        return ForegroundInfo(if (transferring) 1002 else 1001, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
