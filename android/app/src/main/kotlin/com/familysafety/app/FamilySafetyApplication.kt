package com.familysafety.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.familysafety.app.data.di.ApplicationScope
import com.familysafety.app.data.notify.Notifier
import com.familysafety.app.data.realtime.RealtimeLifecycle
import com.familysafety.app.data.server.ServerConfigStore
import com.familysafety.app.data.session.SessionStore
import com.familysafety.app.data.work.WorkScheduler
import com.familysafety.app.domain.model.Role
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FamilySafetyApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var sessionStore: SessionStore
    @Inject lateinit var serverConfig: ServerConfigStore
    @Inject lateinit var realtimeLifecycle: RealtimeLifecycle
    @Inject lateinit var workScheduler: WorkScheduler
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        realtimeLifecycle.start()
        appScope.launch {
            serverConfig.load() // before any network call uses the base URL
            sessionStore.load()
            sessionStore.session.value?.let { s ->
                val role = Role.valueOf(s.role)
                workScheduler.onSignedIn(role)
                realtimeLifecycle.onSignedIn(role)
            }
        }
    }
}
