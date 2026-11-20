package com.familysafety.app.data.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.familysafety.app.domain.model.AlertSeverity
import com.familysafety.app.domain.model.SafetyAlert
import com.familysafety.app.domain.model.ScreenShareSession

/** Central place for notification channels and user-visible notifications. */
class Notifier(private val context: Context) {
    companion object {
        const val CHANNEL_ALERTS = "safety_alerts"
        const val CHANNEL_SHARE_REQUESTS = "screen_share_requests"
        const val CHANNEL_SHARE_ACTIVE = "screen_share_active"
        const val CHANNEL_STATUS = "monitoring_status"
        const val ID_SHARE_REQUEST = 2001
        const val ID_SHARE_ACTIVE = 2002
        const val ID_USAGE_ACCESS = 2003
        const val EXTRA_DESTINATION = "com.familysafety.app.DESTINATION"
        const val DEST_SCREEN_SHARE_CONSENT = "screen_share_consent"
        const val DEST_ALERTS = "alerts"
        const val DEST_PERMISSIONS = "permissions"
    }

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_ALERTS, "Safety alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Check-ins, help requests and configured risk alerts"
            },
            NotificationChannel(CHANNEL_SHARE_REQUESTS, "Screen-sharing requests", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A parent asked to view this screen. Nothing is shared unless you approve."
            },
            NotificationChannel(CHANNEL_SHARE_ACTIVE, "Screen sharing active", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown for as long as this screen is being shared"
            },
            NotificationChannel(CHANNEL_STATUS, "Monitoring status", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Changes to permissions and monitoring status"
            },
        ))
    }

    fun canNotify(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openAppIntent(destination: String?, requestCode: Int): PendingIntent {
        val intent = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            destination?.let { putExtra(EXTRA_DESTINATION, it) }
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun showAlert(alert: SafetyAlert) {
        if (!canNotify()) return
        val priority = if (alert.severity >= AlertSeverity.HIGH) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(alert.title)
            .setContentText(alert.message ?: "Open Family Safety for details")
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message ?: alert.title))
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(DEST_ALERTS, alert.id.hashCode()))
            .build()
        notifySafely(alert.id.hashCode(), n)
    }

    fun showShareRequest(session: ScreenShareSession) {
        if (!canNotify()) return
        val who = session.requestedByDisplayName ?: "Your parent"
        val n = NotificationCompat.Builder(context, CHANNEL_SHARE_REQUESTS)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("$who wants to see your screen")
            .setContentText("Tap to review. Nothing is shared unless you approve.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setTimeoutAfter((session.requestExpiresAt.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(5_000))
            .setContentIntent(openAppIntent(DEST_SCREEN_SHARE_CONSENT, ID_SHARE_REQUEST))
            .build()
        notifySafely(ID_SHARE_REQUEST, n)
    }

    fun cancelShareRequest() = NotificationManagerCompat.from(context).cancel(ID_SHARE_REQUEST)

    fun showUsageAccessMissing() {
        if (!canNotify()) return
        val n = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Usage access is off")
            .setContentText("Activity summaries can't be shared until usage access is turned on.")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(DEST_PERMISSIONS, ID_USAGE_ACCESS))
            .build()
        notifySafely(ID_USAGE_ACCESS, n)
    }

    private fun notifySafely(id: Int, n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call; nothing else to do.
        }
    }
}
