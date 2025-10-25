package me.rhunk.snapenhance.core.features.impl.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import me.rhunk.snapenhance.R

class BatchNotificationManager(private val context: Context) {
    
    companion object {
        private const val CHANNEL_ID = "batch_friend_selector"
        private const val CHANNEL_NAME = "Batch Friend Selector"
        private const val NOTIFICATION_ID_BASE = 9000
    }
    
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
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
                description = "Notifications pour l'envoi de snaps par batches"
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
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
            .setContentTitle("Envoi en cours")
            .setContentText("Batch $currentBatch/$totalBatches • $friendsCount amis")
            .setProgress(totalBatches, currentBatch, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_BASE + sessionId.hashCode(), notification)
    }
    
    fun showBatchComplete(sessionId: String, totalBatches: Int, totalFriends: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("✓ Envoi terminé")
            .setContentText("$totalBatches batches envoyés à $totalFriends amis")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_BASE + sessionId.hashCode(), notification)
    }
    
    fun showBatchError(sessionId: String, batchNumber: Int, error: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("❌ Erreur Batch #$batchNumber")
            .setContentText(error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_BASE + sessionId.hashCode() + batchNumber, notification)
    }
    
    fun cancelNotification(sessionId: String) {
        notificationManager.cancel(NOTIFICATION_ID_BASE + sessionId.hashCode())
    }
    
    fun showSessionCreated(sessionId: String, batchCount: Int, totalFriends: Int) {
        val intent = Intent(context, me.rhunk.snapenhance.ui.manager.pages.social.BatchManagerActivity::class.java)
        intent.putExtra("SESSION_ID", sessionId)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🐱 Batch Manager")
            .setContentText("$batchCount batches créés pour $totalFriends amis")
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_send,
                "Ouvrir",
                pendingIntent
            )
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_BASE + sessionId.hashCode(), notification)
    }
}