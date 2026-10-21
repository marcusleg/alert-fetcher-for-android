package de.marcusleg.alertfetcher.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import de.marcusleg.alertfetcher.R
import de.marcusleg.alertfetcher.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.container
        val fingerprint = intent.getStringExtra(EXTRA_FINGERPRINT)
        val pending = goAsync()
        container.scope.launch {
            try {
                when (intent.action) {
                    ACTION_ACK -> if (fingerprint != null) {
                        container.alertRepository.setAcknowledged(fingerprint, true)
                        container.notifier.cancel(fingerprint)
                    }
                    ACTION_SILENCE -> if (fingerprint != null) {
                        val result = container.silenceManager.silence(fingerprint, SILENCE_DURATION_MS)
                        val message = result.fold(
                            onSuccess = { context.getString(R.string.silence_created) },
                            onFailure = { context.getString(R.string.silence_failed, it.message ?: it.javaClass.simpleName) },
                        )
                        withContext(Dispatchers.Main) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
                    }
                    ACTION_STOP -> container.monitorController.setEnabled(false)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ACK = "de.marcusleg.alertfetcher.action.ACK"
        const val ACTION_SILENCE = "de.marcusleg.alertfetcher.action.SILENCE"
        const val ACTION_STOP = "de.marcusleg.alertfetcher.action.STOP"
        private const val EXTRA_FINGERPRINT = "fingerprint"
        private const val SILENCE_DURATION_MS = 60 * 60 * 1000L

        fun intent(context: Context, action: String, fingerprint: String?): PendingIntent {
            val intent = Intent(context, NotificationActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_FINGERPRINT, fingerprint)
            return PendingIntent.getBroadcast(
                context,
                (action + fingerprint).hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
