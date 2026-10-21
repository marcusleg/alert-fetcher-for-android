package de.marcusleg.alertmanagerreceiver

import android.app.Application
import android.content.Context
import de.marcusleg.alertmanagerreceiver.alertmanager.AlertmanagerClient
import de.marcusleg.alertmanagerreceiver.alertmanager.SilenceManager
import de.marcusleg.alertmanagerreceiver.data.AlertRepository
import de.marcusleg.alertmanagerreceiver.data.AppDatabase
import de.marcusleg.alertmanagerreceiver.data.SettingsRepository
import de.marcusleg.alertmanagerreceiver.heartbeat.HeartbeatMonitor
import de.marcusleg.alertmanagerreceiver.notify.AlertNotifier
import de.marcusleg.alertmanagerreceiver.server.WebhookServer
import de.marcusleg.alertmanagerreceiver.service.AlertProcessor
import de.marcusleg.alertmanagerreceiver.service.ReceiverController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AlertReceiverApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        container.scope.launch {
            container.settingsRepository.ensureDefaults()
            container.alertRepository.applyRetention(container.settingsRepository.current().retentionDays)
        }
    }
}

/** Minimal manual dependency container; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.create(appContext) }
    val settingsRepository = SettingsRepository(appContext, scope)
    val alertRepository: AlertRepository by lazy { AlertRepository(database) }
    val webhookServer = WebhookServer()
    val notifier = AlertNotifier(appContext)
    val alertmanagerClient = AlertmanagerClient()
    val silenceManager by lazy { SilenceManager(alertmanagerClient, settingsRepository, alertRepository, notifier) }
    val heartbeatMonitor by lazy { HeartbeatMonitor(appContext, settingsRepository, notifier) }
    val alertProcessor by lazy { AlertProcessor(settingsRepository, alertRepository, notifier, heartbeatMonitor) }
    val receiverController by lazy { ReceiverController(appContext, settingsRepository) }
}

val Context.container: AppContainer
    get() = (applicationContext as AlertReceiverApp).container
