package app.chut

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build

object Notifier {

    private const val CHANNEL_ID = "filtered_calls"
    private const val CHANNEL_CALLER = "caller_id"
    const val ACTION_NOT_SPAM = "app.chut.NOT_SPAM"
    const val EXTRA_NUMBER = "number"
    const val EXTRA_NOTIF_ID = "notif_id"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Appels filtrés",
            NotificationManager.IMPORTANCE_LOW // pas de son, pas de pop-up : juste dans le volet
        ).apply {
            description = "Une notification discrète quand un appel indésirable est filtré"
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }

    private fun ensureCallerChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_CALLER) != null) return
        val channel = NotificationChannel(
            CHANNEL_CALLER,
            "Qui appelle ?",
            NotificationManager.IMPORTANCE_HIGH // s'affiche en haut de l'écran pendant la sonnerie
        ).apply {
            description = "Nom trouvé dans l'annuaire inversé pendant qu'un numéro inconnu sonne"
            setSound(null, null) // la sonnerie de l'appel suffit
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun canNotify(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Pendant qu'un numéro inconnu sonne : affiche le nom trouvé dans l'annuaire. */
    fun notifyCallerId(context: Context, number: String, who: ReverseLookup.Identity) {
        if (!canNotify(context)) return
        ensureCallerChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val n = Notification.Builder(context, CHANNEL_CALLER)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("Qui appelle ? ${who.name}")
            .setContentText(listOf(who.place, who.ratingText(), SpamRules.pretty(number, context)).filter { it.isNotEmpty() }.joinToString(" · "))
            .setCategory(Notification.CATEGORY_CALL)
            .setTimeoutAfter(90_000)
            .setAutoCancel(true)
            .build()
        nm.notify(number.hashCode(), n)
    }

    fun notifyFiltered(
        context: Context, number: String, reason: String, silenced: Boolean, name: String = ""
    ) {
        if (!canNotify(context)) return

        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val id = (System.currentTimeMillis() and 0x0FFFFFFFL).toInt()
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(if (silenced) "Appel indésirable mis en silence" else "Appel indésirable bloqué")
            .setContentText(
                if (name.isEmpty()) "${SpamRules.pretty(number, context)} · $reason"
                else "$name · $reason"
            )
            .setSubText(if (name.isEmpty()) null else SpamRules.pretty(number, context))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_STATUS)

        if (number.isNotEmpty()) {
            val notSpam = PendingIntent.getBroadcast(
                context, id,
                Intent(context, NotificationActionReceiver::class.java)
                    .setAction(ACTION_NOT_SPAM)
                    .putExtra(EXTRA_NUMBER, number)
                    .putExtra(EXTRA_NOTIF_ID, id),
                flags
            )
            val callBack = PendingIntent.getActivity(
                context, id + 1,
                Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                flags
            )
            builder.addAction(Notification.Action.Builder(null as Icon?, "Pas un spam", notSpam).build())
            builder.addAction(Notification.Action.Builder(null as Icon?, "Rappeler", callBack).build())
            if (name.isEmpty()) {
                val who = PendingIntent.getActivity(
                    context, id + 2,
                    Intent(Intent.ACTION_VIEW, ReverseLookup.webSearchUri(number))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    flags
                )
                builder.addAction(Notification.Action.Builder(null as Icon?, "Qui est-ce ?", who).build())
            }
        }

        nm.notify(id, builder.build())
    }
}
