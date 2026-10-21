package dev.yyblite

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.yyblite.work.UpdateNotifications
import dev.yyblite.work.UpdateWorker
import java.util.concurrent.TimeUnit

class YybApp : Application() {
    override fun onCreate() {
        super.onCreate()
        dev.yyblite.data.ErrorLog.init(this)
        UpdateNotifications.ensureChannel(this)
        scheduleUpdateCheck()
    }

    private fun scheduleUpdateCheck() {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(
            3, TimeUnit.HOURS,
            30, TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "yyb-update-check",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
