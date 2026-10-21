package de.marcusleg.alertmanagerreceiver

import android.app.Application
import android.content.Context
import de.marcusleg.alertmanagerreceiver.alertmanager.AlertmanagerApi
import de.marcusleg.alertmanagerreceiver.alertmanager.RequestAuthenticator
import de.marcusleg.alertmanagerreceiver.alertmanager.SilenceManager
import de.marcusleg.alertmanagerreceiver.auth.OidcManager
import de.marcusleg.alertmanagerreceiver.data.AlertRepository
import de.marcusleg.alertmanagerreceiver.data.AppDatabase
import de.marcusleg.alertmanagerreceiver.data.SettingsRepository
import de.marcusleg.alertmanagerreceiver.monitor.PollScheduler
import de.marcusleg.alertmanagerreceiver.monitor.PollTrigger
import de.marcusleg.alertmanagerreceiver.monitor.Poller
import de.marcusleg.alertmanagerreceiver.notify.AlertNotifier
import de.marcusleg.alertmanagerreceiver.service.MonitorController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class AlertReceiverApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        container.scope.launch {
            container.alertRepository.applyRetention(container.settingsRepository.current().retentionDays)
        }
    }
}

/** Minimal manual dependency container; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.create(appContext) }
    val settingsRepository = SettingsRepository(appContext)
    val alertRepository: AlertRepository by lazy { AlertRepository(database) }
    val notifier = AlertNotifier(appContext)
    val oidc = OidcManager(appContext, settingsRepository)
    val api = AlertmanagerApi(RequestAuthenticator(oidc))

    /** Wakes the service's poll loop; see [de.marcusleg.alertmanagerreceiver.service.MonitorService]. */
    val pollTriggers = Channel<PollTrigger>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val pollScheduler = PollScheduler(appContext)
    val poller by lazy { Poller(appContext, settingsRepository, api, alertRepository, notifier) }
    val monitorController by lazy { MonitorController(appContext, settingsRepository, pollScheduler, pollTriggers) }
    val silenceManager by lazy {
        SilenceManager(api, settingsRepository, alertRepository, notifier, oidc, onSilenced = monitorController::checkNow)
    }
}

val Context.container: AppContainer
    get() = (applicationContext as AlertReceiverApp).container
