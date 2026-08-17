package me.eternal.purrfect.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BridgeWakeReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_WAKE = "me.eternal.purrfect.action.WAKE_BRIDGE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WAKE) return
        val serviceIntent = Intent(context, BridgeService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
