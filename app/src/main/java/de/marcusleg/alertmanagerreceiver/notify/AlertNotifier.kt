package de.marcusleg.alertmanagerreceiver.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.marcusleg.alertmanagerreceiver.MainActivity
import de.marcusleg.alertmanagerreceiver.R
import de.marcusleg.alertmanagerreceiver.data.AlertChange
import de.marcusleg.alertmanagerreceiver.data.AlertEntity
import de.marcusleg.alertmanagerreceiver.data.AlertUpdate
import de.marcusleg.alertmanagerreceiver.data.AppSettings
import de.marcusleg.alertmanagerreceiver.model.AlertStatus
import de.marcusleg.alertmanagerreceiver.model.Severity
import de.marcusleg.alertmanagerreceiver.util.TimeFormat

class AlertNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP_ALERTS, context.getString(R.string.channel_group_alerts)))
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP_SYSTEM, context.getString(R.string.channel_group_system)))
        val channels = listOf(
            channel(CHANNEL_CRITICAL, R.string.channel_critical, R.string.channel_critical_desc, NotificationManager.IMPORTANCE_HIGH, GROUP_ALERTS) {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
                enableLights(true)
                lightColor = ContextCompat.getColor(context, R.color.severity_critical)
            },
            channel(CHANNEL_WARNING, R.string.channel_warning, R.string.channel_warning_desc, NotificationManager.IMPORTANCE_HIGH, GROUP_ALERTS) {
                enableVibration(true)
            },
            channel(CHANNEL_INFO, R.string.channel_info, R.string.channel_info_desc, NotificationManager.IMPORTANCE_DEFAULT, GROUP_ALERTS),
            channel(CHANNEL_RESOLVED, R.string.channel_resolved, R.string.channel_resolved_desc, NotificationManager.IMPORTANCE_LOW, GROUP_ALERTS),
            channel(CHANNEL_HEARTBEAT, R.string.channel_heartbeat, R.string.channel_heartbeat_desc, NotificationManager.IMPORTANCE_HIGH, GROUP_SYSTEM) {
                enableVibration(true)
            },
            channel(CHANNEL_SERVICE, R.string.channel_service, R.string.channel_service_desc, NotificationManager.IMPORTANCE_LOW, GROUP_SYSTEM) {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannels(channels)
    }

    private inline fun channel(
        id: String,
        name: Int,
        description: Int,
        importance: Int,
        group: String,
        configure: NotificationChannel.() -> Unit = {},
    ) = NotificationChannel(id, context.getString(name), importance).apply {
        this.description = context.getString(description)
        this.group = group
        configure()
    }

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun handle(updates: List<AlertUpdate>, settings: AppSettings) {
        if (updates.isEmpty()) return
        for ((alert, change) in updates) {
            val wanted = alert.severity.isAtLeast(settings.minNotifySeverity)
            when (change) {
                AlertChange.NEW_FIRING, AlertChange.REFIRED ->
                    if (wanted) showFiring(alert, settings, onlyAlertOnce = false)
                // Repeats are silent while the notification is still visible, but alert again if it was swiped away.
                AlertChange.STILL_FIRING ->
                    if (wanted && !alert.acknowledged) showFiring(alert, settings, onlyAlertOnce = true)
                AlertChange.RESOLVED ->
                    if (wanted && settings.notifyResolved) showResolved(alert) else cancel(alert.fingerprint)
                AlertChange.STILL_RESOLVED -> Unit
            }
        }
        updateSummary()
    }

    @SuppressLint("MissingPermission")
    fun showFiring(alert: AlertEntity, settings: AppSettings, onlyAlertOnce: Boolean) {
        if (!canPost()) return
        val builder = baseBuilder(alert, channelFor(alert.severity))
            .setContentTitle(alert.alertName)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(if (alert.severity == Severity.INFO) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setWhen(alert.startsAt ?: alert.firstReceivedAt)
            .addAction(
                R.drawable.ic_stat_check,
                context.getString(R.string.action_acknowledge),
                NotificationActionReceiver.intent(context, NotificationActionReceiver.ACTION_ACK, alert.fingerprint),
            )
        if (settings.alertmanagerUrl.isNotBlank() || !alert.externalUrl.isNullOrBlank()) {
            builder.addAction(
                R.drawable.ic_stat_silence,
                context.getString(R.string.action_silence_1h),
                NotificationActionReceiver.intent(context, NotificationActionReceiver.ACTION_SILENCE, alert.fingerprint),
            )
        }
        manager.notify(alert.fingerprint, ID_ALERT, builder.build())
    }

    @SuppressLint("MissingPermission")
    fun showResolved(alert: AlertEntity) {
        if (!canPost()) return
        val builder = baseBuilder(alert, CHANNEL_RESOLVED)
            .setContentTitle(context.getString(R.string.notification_resolved_title, alert.alertName))
            .setSmallIcon(R.drawable.ic_stat_resolved)
            .setColor(ContextCompat.getColor(context, R.color.resolved))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setWhen(alert.endsAt ?: alert.statusChangedAt)
            .setTimeoutAfter(RESOLVED_TIMEOUT_MS)
        manager.notify(alert.fingerprint, ID_ALERT, builder.build())
    }

    private fun baseBuilder(alert: AlertEntity, channel: String): NotificationCompat.Builder {
        val text = alert.summary ?: alert.description ?: alert.context ?: alert.severity.label
        val details = buildString {
            alert.summary?.let { appendLine(it) }
            alert.description?.takeIf { it != alert.summary }?.let { appendLine(it) }
            val context = AlertEntity.CONTEXT_LABELS.mapNotNull { key -> alert.labels[key]?.let { "$key=$it" } }
            if (context.isNotEmpty()) append(context.joinToString("  "))
        }.trim()
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setColor(ContextCompat.getColor(context, severityColor(alert.severity)))
            .setContentText(text)
            .setSubText(listOfNotNull(alert.severity.label, alert.context).joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(details.ifBlank { text }))
            .setShowWhen(true)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(openAlertIntent(alert.fingerprint))
    }

    fun cancel(fingerprint: String) {
        manager.cancel(fingerprint, ID_ALERT)
        updateSummary()
    }

    fun cancelAllAlerts() {
        activeAlertNotifications().forEach { manager.cancel(it.tag, ID_ALERT) }
        manager.cancel(ID_SUMMARY)
    }

    private fun activeAlertNotifications() =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.id == ID_ALERT && it.tag != null }

    @SuppressLint("MissingPermission")
    private fun updateSummary() {
        if (!canPost()) return
        val active = activeAlertNotifications()
        if (active.size < 2) {
            manager.cancel(ID_SUMMARY)
            return
        }
        val resolved = active.count { it.notification.channelId == CHANNEL_RESOLVED }
        val firing = active.size - resolved
        val title = context.resources.getQuantityString(R.plurals.summary_firing, firing, firing)
        val summary = NotificationCompat.Builder(context, CHANNEL_WARNING)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setColor(ContextCompat.getColor(context, R.color.severity_critical))
            .setContentTitle(title)
            .setContentText(if (resolved > 0) context.resources.getQuantityString(R.plurals.summary_resolved, resolved, resolved) else null)
            .setStyle(NotificationCompat.InboxStyle().also { style ->
                active.take(6).forEach { sbn ->
                    style.addLine(sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE))
                }
            })
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(openAlertIntent(null))
            .build()
        manager.notify(ID_SUMMARY, summary)
    }

    @SuppressLint("MissingPermission")
    fun showHeartbeatMissing(alertName: String, lastSeenAt: Long?, timeoutMinutes: Int) {
        if (!canPost()) return
        val last = lastSeenAt?.let { context.getString(R.string.heartbeat_last_seen, TimeFormat.relative(it)) }
            ?: context.getString(R.string.heartbeat_never_seen)
        val notification = NotificationCompat.Builder(context, CHANNEL_HEARTBEAT)
            .setSmallIcon(R.drawable.ic_stat_heartbeat)
            .setColor(ContextCompat.getColor(context, R.color.severity_critical))
            .setContentTitle(context.getString(R.string.heartbeat_missing_title, alertName))
            .setContentText(last)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.resources.getQuantityString(R.plurals.heartbeat_missing_text, timeoutMinutes, timeoutMinutes, alertName) + "\n" + last,
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAlertIntent(null))
            .setAutoCancel(true)
            .build()
        manager.notify(ID_HEARTBEAT, notification)
    }

    @SuppressLint("MissingPermission")
    fun showHeartbeatRestored(alertName: String) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_RESOLVED)
            .setSmallIcon(R.drawable.ic_stat_heartbeat)
            .setColor(ContextCompat.getColor(context, R.color.resolved))
            .setContentTitle(context.getString(R.string.heartbeat_restored_title, alertName))
            .setContentText(context.getString(R.string.heartbeat_restored_text))
            .setContentIntent(openAlertIntent(null))
            .setAutoCancel(true)
            .setTimeoutAfter(RESOLVED_TIMEOUT_MS)
            .build()
        manager.notify(ID_HEARTBEAT, notification)
    }

    fun serviceNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_receiver)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAlertIntent(null, MainActivity.DESTINATION_RECEIVER))
            .addAction(
                R.drawable.ic_stat_stop,
                context.getString(R.string.action_stop),
                NotificationActionReceiver.intent(context, NotificationActionReceiver.ACTION_STOP_SERVER, null),
            )
            .build()

    private fun openAlertIntent(fingerprint: String?, destination: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_FINGERPRINT, fingerprint)
            putExtra(MainActivity.EXTRA_DESTINATION, destination)
        }
        val requestCode = (fingerprint ?: destination ?: "main").hashCode()
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL_CRITICAL = "alerts_critical"
        const val CHANNEL_WARNING = "alerts_warning"
        const val CHANNEL_INFO = "alerts_info"
        const val CHANNEL_RESOLVED = "alerts_resolved"
        const val CHANNEL_HEARTBEAT = "heartbeat"
        const val CHANNEL_SERVICE = "receiver_service"

        private const val GROUP_ALERTS = "alerts"
        private const val GROUP_SYSTEM = "system"
        private const val GROUP_KEY = "de.marcusleg.alertmanagerreceiver.ALERTS"

        const val ID_SERVICE = 1
        private const val ID_SUMMARY = 2
        private const val ID_HEARTBEAT = 3
        private const val ID_ALERT = 100

        private const val RESOLVED_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        fun channelFor(severity: Severity) = when (severity) {
            Severity.CRITICAL -> CHANNEL_CRITICAL
            Severity.WARNING -> CHANNEL_WARNING
            Severity.INFO -> CHANNEL_INFO
        }

        fun severityColor(severity: Severity) = when (severity) {
            Severity.CRITICAL -> R.color.severity_critical
            Severity.WARNING -> R.color.severity_warning
            Severity.INFO -> R.color.severity_info
        }

        fun statusColor(alert: AlertEntity) =
            if (alert.status == AlertStatus.RESOLVED) R.color.resolved else severityColor(alert.severity)
    }
}
