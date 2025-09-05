package me.rhunk.snapenhance.task

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import me.rhunk.snapenhance.common.config.impl.Global
import java.util.concurrent.TimeUnit

object UpdateScheduler {
    private const val UPDATE_WORK_TAG = "snapenhance_update_check"

    fun schedule(context: Context, updateManagerConfig: Global.UpdateManagerConfig) {
        val workManager = WorkManager.getInstance(context)

        if (!updateManagerConfig.automaticUpdateCheck.get()) {
            workManager.cancelUniqueWork(UPDATE_WORK_TAG)
            return
        }

        val interval = when (updateManagerConfig.updateCheckInterval.get()) {
            "every_6_hours" -> 6L
            "every_12_hours" -> 12L
            "daily" -> 24L
            "weekly" -> 7 * 24L
            else -> 24L
        }

        val workRequest = PeriodicWorkRequestBuilder<UpdateWorker>(interval, TimeUnit.HOURS)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UPDATE_WORK_TAG,
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )
    }
}
