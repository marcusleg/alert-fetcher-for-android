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
import de.marcusleg.alertfetcher.monitor.MonitorController
import de.marcusleg.alertfetcher.monitor.PollScheduler
import de.marcusleg.alertfetcher.monitor.Poller
import de.marcusleg.alertfetcher.notify.AlertNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        container.scope.launch { container.monitorController.recheckOnSettingsChange() }
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

    val pollScheduler = PollScheduler(appContext)
    val poller by lazy { Poller(appContext, settingsRepository, api, alertRepository, notifier) }
    val monitorController by lazy { MonitorController(scope, settingsRepository, poller, pollScheduler) }
    val silenceManager by lazy {
        SilenceManager(api, settingsRepository, alertRepository, notifier, oidc, onSilenced = monitorController::checkNow)
    }
}

val Context.container: AppContainer
    get() = (applicationContext as AlertFetcherApp).container
