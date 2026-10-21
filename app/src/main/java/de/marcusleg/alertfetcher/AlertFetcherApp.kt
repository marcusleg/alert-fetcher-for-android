package de.marcusleg.alertfetcher

import android.app.Application
import android.content.Context
import de.marcusleg.alertfetcher.alertmanager.AlertmanagerApi
import de.marcusleg.alertfetcher.alertmanager.RequestAuthenticator
import de.marcusleg.alertfetcher.alertmanager.SilenceManager
import de.marcusleg.alertfetcher.auth.OidcManager
import de.marcusleg.alertfetcher.data.AlertRepository
import de.marcusleg.alertfetcher.data.AppDatabase
import de.marcusleg.alertfetcher.data.SettingsRepository
import de.marcusleg.alertfetcher.monitor.PollScheduler
import de.marcusleg.alertfetcher.monitor.PollTrigger
import de.marcusleg.alertfetcher.monitor.Poller
import de.marcusleg.alertfetcher.notify.AlertNotifier
import de.marcusleg.alertfetcher.service.MonitorController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class AlertFetcherApp : Application() {
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

    /** Wakes the service's poll loop; see [de.marcusleg.alertfetcher.service.MonitorService]. */
    val pollTriggers = Channel<PollTrigger>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val pollScheduler = PollScheduler(appContext)
    val poller by lazy { Poller(appContext, settingsRepository, api, alertRepository, notifier) }
    val monitorController by lazy { MonitorController(appContext, settingsRepository, pollScheduler, pollTriggers) }
    val silenceManager by lazy {
        SilenceManager(api, settingsRepository, alertRepository, notifier, oidc, onSilenced = monitorController::checkNow)
    }
}

val Context.container: AppContainer
    get() = (applicationContext as AlertFetcherApp).container
