package app.chut

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Gère le bouton "Pas un spam" de la notification. */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifier.ACTION_NOT_SPAM) return
        val number = intent.getStringExtra(Notifier.EXTRA_NUMBER) ?: return
        Store(context).addToWhitelist(number)
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(intent.getIntExtra(Notifier.EXTRA_NOTIF_ID, 0))
        Toast.makeText(
            context,
            "${SpamRules.pretty(number, context)} pourra désormais t'appeler",
            Toast.LENGTH_SHORT
        ).show()
    }
}
