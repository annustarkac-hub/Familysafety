package com.familysafety.app.feature.child

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.familysafety.app.data.di.ApplicationScope
import com.familysafety.app.data.notify.Notifier
import com.familysafety.app.data.realtime.RealtimeLifecycle
import com.familysafety.app.data.webrtc.PeerState
import com.familysafety.app.data.webrtc.ScreenShareCoordinator
import com.familysafety.app.data.webrtc.ScreenSharePublisher
import com.familysafety.app.data.webrtc.WebRtcEngine
import com.familysafety.app.domain.logic.ShareEvent
import com.familysafety.app.domain.logic.ShareState
import com.familysafety.app.domain.model.StopReason
import com.familysafety.app.domain.repository.RealtimeRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service (type mediaProjection) that performs the actual capture. It only ever runs after:
 *  1) the child approved the parent's request in-app, and
 *  2) the child accepted Android's own screen-capture consent dialog.
 * A persistent, non-dismissable notification with a Stop action is shown for the whole session.
 */
@AndroidEntryPoint
class ScreenCaptureService : Service() {
    @Inject lateinit var coordinator: ScreenShareCoordinator
    @Inject lateinit var engine: WebRtcEngine
    @Inject lateinit var realtime: RealtimeRepository
    @Inject lateinit var realtimeLifecycle: RealtimeLifecycle
    @Inject lateinit var notifier: Notifier
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var publisher: ScreenSharePublisher? = null
    private var holding = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { finish(StopReason.USER_STOPPED); return START_NOT_STICKY }
            ACTION_START -> Unit
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        val data = intent.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        val session = coordinator.approvedSession
        val state = coordinator.state.value
        if (data == null || session == null || state !is ShareState.Starting || state.session.id != session.id) {
            // Never capture without a matching, approved session.
            stopSelf()
            return START_NOT_STICKY
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, Notifier.ID_SHARE_ACTIVE, buildNotification(session.requestedByDisplayName), type)
        realtimeLifecycle.acquire(); holding = true
        realtime.connect()

        val pub = ScreenSharePublisher(applicationContext, engine, realtime, scope)
        publisher = pub
        try {
            pub.start(session.id, session.iceServers, data) { scope.launch { finish(StopReason.USER_STOPPED) } }
        } catch (e: Exception) {
            finish(StopReason.CAPTURE_FAILED)
            return START_NOT_STICKY
        }
        scope.launch {
            pub.state.collect { s ->
                when (s) {
                    PeerState.CONNECTED -> coordinator.dispatch(ShareEvent.PeerConnected)
                    PeerState.FAILED -> finish(StopReason.CONNECTION_FAILED)
                    else -> Unit
                }
            }
        }
        scope.launch {
            coordinator.state.collect { st -> if (st is ShareState.Ended || st is ShareState.Idle) teardown() }
        }
        return START_NOT_STICKY
    }

    private fun finish(reason: StopReason) {
        appScope.launch { coordinator.stop(reason) }
        teardown()
    }

    private fun teardown() {
        publisher?.stop()
        publisher = null
        if (holding) { realtimeLifecycle.release(); holding = false }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        publisher?.let { finish(StopReason.USER_STOPPED) }
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(parentName: String?): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, Notifier.CHANNEL_SHARE_ACTIVE)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Sharing your screen")
            .setContentText("${parentName ?: "Your parent"} can see your screen. Tap Stop to end.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(notifier.openAppIntent(Notifier.DEST_SCREEN_SHARE_CONSENT, 3))
            .addAction(android.R.drawable.ic_media_pause, "Stop sharing", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "com.familysafety.app.action.START_SCREEN_SHARE"
        const val ACTION_STOP = "com.familysafety.app.action.STOP_SCREEN_SHARE"
        private const val EXTRA_DATA = "projection_data"

        fun start(context: Context, projectionData: Intent) {
            ContextCompat.startForegroundService(context,
                Intent(context, ScreenCaptureService::class.java).setAction(ACTION_START).putExtra(EXTRA_DATA, projectionData))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
