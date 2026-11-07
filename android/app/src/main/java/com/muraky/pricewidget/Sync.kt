package com.muraky.pricewidget

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** 백그라운드에서 데이터를 받아오고 위젯을 다시 그림. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ok = DataRepo.fetch(applicationContext)
        Calc.refreshAll(applicationContext)
        DataRepo.setLoading(applicationContext, false)
        PriceWidget.updateAll(applicationContext)
        return if (ok) Result.success() else Result.retry()
    }
}

object Sync {
    private val net = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * 서버 데이터는 3시간마다 바뀌지만, 폰이 그 시각을 정확히 맞출 수 없으므로
     * 1시간마다 확인해서 새 값이 나오면 늦어도 1시간 안에 반영되게 함.
     */
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.HOURS)
            .setConstraints(net)
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork("pw-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun now(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setConstraints(net)
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork("pw-now", ExistingWorkPolicy.REPLACE, req)
    }
}
