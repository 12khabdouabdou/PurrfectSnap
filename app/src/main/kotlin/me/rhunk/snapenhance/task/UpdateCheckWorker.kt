package me.rhunk.snapenhance.task

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import android.Manifest
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.work.WorkerParameters
import me.rhunk.snapenhance.R
import me.rhunk.snapenhance.ui.manager.MainActivity
import me.rhunk.snapenhance.ui.manager.data.Updater

class UpdateCheckWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val latestRelease = Updater.latestRelease
            if (latestRelease != null) {
                showUpdateNotification(latestRelease.versionName)
            }
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun showUpdateNotification(versionName: String) {
        val channelId = "snapenhance_updates"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "SnapEnhance Updates"
            val descriptionText = "Notifications for SnapEnhance updates"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.launcher_icon_monochrome)
            .setContentTitle("PurrfectSnap Update Available")
            .setContentText("Version $versionName is now available.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // Cannot request permission from a worker. The user must grant it from the app's settings.
                return
            }
        }
        with(NotificationManagerCompat.from(appContext)) {
            notify(1, builder.build())
        }
    }
}
