package com.familysafety.app.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.familysafety.app.data.db.NotifiedAlertDao
import com.familysafety.app.data.db.NotifiedAlertEntity
import com.familysafety.app.data.notify.Notifier
import com.familysafety.app.data.repository.SessionLifecycleListener
import com.familysafety.app.data.session.SessionStore
import com.familysafety.app.domain.model.AlertType
import com.familysafety.app.domain.model.AppError
import com.familysafety.app.domain.model.AppResult
import com.familysafety.app.domain.model.Role
import com.familysafety.app.domain.repository.SafetyRepository
import com.familysafety.app.domain.repository.UsageCollector
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Child device: aggregates recent usage and uploads it. If usage access was revoked, the parent is informed
 * once per day (server-deduplicated) and the child sees a local notification — nothing is collected covertly.
 */
@HiltWorker
class UsageSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val store: SessionStore,
    private val collector: UsageCollector,
    private val safety: SafetyRepository,
    private val notifier: Notifier,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!store.loaded.value) store.load()
        val session = store.session.value ?: return Result.success()
        if (session.role != Role.CHILD.name || !session.paired) return Result.success()
        if (!collector.hasUsageAccess()) {
            notifier.showUsageAccessMissing()
            safety.createAlert(AlertType.USAGE_ACCESS_REVOKED, null)
            return Result.success()
        }
        return when (val r = collector.syncNow()) {
            is AppResult.Success -> Result.success()
            is AppResult.Failure -> when (r.error) {
                is AppError.Network -> Result.retry()
                is AppError.Api -> if ((r.error as AppError.Api).status >= 500) Result.retry() else Result.failure()
                else -> Result.failure()
            }
        }
    }
}

/**
 * Fallback alert delivery when the app is not in the foreground (no push provider is configured — see
 * DEPLOYMENT.md "Push notifications"). Polls unacknowledged alerts and notifies each once.
 */
@HiltWorker
class AlertPollWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val store: SessionStore,
    private val safety: SafetyRepository,
    private val notified: NotifiedAlertDao,
    private val notifier: Notifier,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!store.loaded.value) store.load()
        val session = store.session.value ?: return Result.success()
        val alerts = when (val r = safety.alerts(unacknowledgedOnly = true)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return if (r.error is AppError.Network) Result.retry() else Result.success()
        }
        val relevant = if (session.role == Role.CHILD.name) alerts.filter { it.type == AlertType.CHECK_IN_REQUEST } else alerts
        if (relevant.isEmpty()) return Result.success()
        val already = notified.existing(relevant.map { it.id }).toSet()
        val now = System.currentTimeMillis()
        relevant.filter { it.id !in already }.forEach {
            notifier.showAlert(it)
            notified.insert(NotifiedAlertEntity(it.id, now))
        }
        notified.prune(now - TimeUnit.DAYS.toMillis(30))
        return Result.success()
    }
}

/** Schedules role-appropriate background work while signed in and cancels it on sign-out. */
class WorkScheduler(private val context: Context) : SessionLifecycleListener {
    private val wm get() = WorkManager.getInstance(context)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun onSignedIn(role: Role) {
        if (role == Role.CHILD) {
            wm.enqueueUniquePeriodicWork(USAGE_SYNC, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<UsageSyncWorker>(15, TimeUnit.MINUTES).setConstraints(network)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
        }
        wm.enqueueUniquePeriodicWork(ALERT_POLL, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AlertPollWorker>(15, TimeUnit.MINUTES).setConstraints(network).build())
    }

    fun syncUsageNow() {
        wm.enqueueUniqueWork(USAGE_SYNC_NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UsageSyncWorker>().setConstraints(network).build())
    }

    override fun onSignedOut() {
        wm.cancelUniqueWork(USAGE_SYNC)
        wm.cancelUniqueWork(USAGE_SYNC_NOW)
        wm.cancelUniqueWork(ALERT_POLL)
    }

    companion object {
        const val USAGE_SYNC = "usage-sync"
        const val USAGE_SYNC_NOW = "usage-sync-now"
        const val ALERT_POLL = "alert-poll"
    }
}
