package de.marcusleg.alertmanagerreceiver

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import de.marcusleg.alertmanagerreceiver.ui.AppRoot
import de.marcusleg.alertmanagerreceiver.ui.PendingNavigation
import de.marcusleg.alertmanagerreceiver.ui.theme.AlertReceiverTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val pendingNavigation = mutableStateOf<PendingNavigation?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AlertReceiverTheme {
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
        // Brings the service back if the system killed it while the app was in the background.
        lifecycleScope.launch { container.receiverController.ensureRunningIfEnabled() }
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
            destination == DESTINATION_RECEIVER -> PendingNavigation.Receiver
            intent?.hasExtra(EXTRA_DESTINATION) == true || intent?.hasExtra(EXTRA_FINGERPRINT) == true -> PendingNavigation.Alerts
            else -> null
        }
    }

    companion object {
        const val EXTRA_FINGERPRINT = "fingerprint"
        const val EXTRA_DESTINATION = "destination"
        const val DESTINATION_RECEIVER = "receiver"
    }
}
