package de.marcusleg.alertfetcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import de.marcusleg.alertfetcher.ui.AppRoot
import de.marcusleg.alertfetcher.ui.PendingNavigation
import de.marcusleg.alertfetcher.ui.theme.AlertFetcherTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val pendingNavigation = mutableStateOf<PendingNavigation?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AlertFetcherTheme {
                AppRoot(
                    container = container,
                    pendingNavigation = pendingNavigation.value,
                    onNavigationHandled = { pendingNavigation.value = null },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-arms the check alarm, which a force stop cancels, and catches up on an overdue check.
        lifecycleScope.launch { container.monitorController.resume() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val fingerprint = intent?.getStringExtra(EXTRA_FINGERPRINT)
        val destination = intent?.getStringExtra(EXTRA_DESTINATION)
        pendingNavigation.value = when {
            fingerprint != null -> PendingNavigation.Alert(fingerprint)
            destination == DESTINATION_CONNECTION -> PendingNavigation.Connection
            intent?.hasExtra(EXTRA_DESTINATION) == true || intent?.hasExtra(EXTRA_FINGERPRINT) == true -> PendingNavigation.Alerts
            else -> null
        }
    }

    companion object {
        const val EXTRA_FINGERPRINT = "fingerprint"
        const val EXTRA_DESTINATION = "destination"
        const val DESTINATION_CONNECTION = "connection"
    }
}
