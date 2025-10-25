package me.rhunk.snapenhance.core.features.impl.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

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

    fun showBatchProgress(
        sessionId: String,
        currentBatch: Int,
        totalBatches: Int,
        friendsCount: Int
    ) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("✅ Batch Complete")
            .setContentText("$totalBatches batches sent to $totalFriends friends")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notify(sessionId.hashCode(), notification)
    }

    fun showBatchError(sessionId: String, batch
