package me.rhunk.snapenhance.core.features.impl.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.rhunk.snapenhance.R // FIX: Added missing import for the R file

class BatchNotificationManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "batch_friend_selector"
        private const val CHANNEL_NAME = "Batch Friend Selector"
        private const val CHANNEL_DESC = "Notifications for Snap batch sending"
        private const val NOTIFICATION_ID_BASE = 9000
    }

    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = CHANNEL_DESC
                setShowBadge(true)
            }
            val sysManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            sysManager.createNotificationChannel(channel)
        }
    }

    private fun notify(id: Int, notification: android.app.Notification) {
        // Use NOTIFICATION_ID_BASE offset for unique IDs
        notificationManager.notify(NOTIFICATION_ID_BASE + id, notification)
    }

    fun showSessionCreated(
        sessionId: String,
        totalBatches: Int,
        totalFriends: Int
    ) {
        val intent = Intent(
            context,
            Class.forName("me.rhunk.snapenhance.ui.manager.pages.social.BatchManagerActivity")
        ).apply {
            putExtra("SESSION_ID", sessionId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // FIX: Used Intent.FLAG_ACTIVITY_NEW_TASK instead of unresolved 'flags'
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher) // FIX: Used R.drawable.ic_launcher
            .setContentTitle("Batch Sending Started")
            .setContentText("$totalBatches batches prepared for $totalFriends friends. Tap to manage.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notify(sessionId.hashCode(), notification)
    }

    fun showBatchProgress(
        sessionId: String,
        currentBatch: Int,
        totalBatches: Int,
        friendsCount: Int
    ) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher) // FIX: Used R.drawable.ic_launcher
            .setContentTitle("Sending Snaps…")
            .setContentText("Batch $currentBatch/$totalBatches • $friendsCount friends")
            .setProgress(totalBatches, currentBatch, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notify(sessionId.hashCode(), notification)
    }

    fun showBatchComplete(sessionId: String, totalBatches: Int, totalFriends: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher) // FIX: Used R.drawable.ic_launcher
            .setContentTitle("✅ Batch Complete")
            .setContentText("$totalBatches batches sent to $totalFriends friends")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notify(sessionId.hashCode(), notification)
        cancelNotification(sessionId.hashCode()) // Clean up any lingering progress notification
    }

    // COMPLETED FUNCTION: Based on the pattern of the other methods
    fun showBatchError(sessionId: String, batchIndex: Int, errorMessage: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher) // FIX: Used R.drawable.ic_launcher
            .setContentTitle("❌ Batch Error")
            .setContentText("Batch ${batchIndex} failed: ${errorMessage}. Tap to view details.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Batch ${batchIndex} failed: ${errorMessage}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notify(sessionId.hashCode() + batchIndex, notification) // Use unique ID for error notification
    }
    
    fun cancelNotification(id: Int) {
        notificationManager.cancel(NOTIFICATION_ID_BASE + id)
    }
}
