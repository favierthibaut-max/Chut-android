package app.chut

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.CallScreeningService.CallResponse
import android.telecom.Connection
import android.telecom.TelecomManager

/**
 * Android appelle ce service pour chaque appel entrant d'un numéro inconnu,
 * AVANT que le téléphone ne sonne. On décide ici : laisser sonner, ou couper.
 */
class SpamScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        val store = Store(this)
        val verdict = try {
            val raw = callDetails.handle?.schemeSpecificPart
            val presentation = callDetails.handlePresentation
            val hidden = presentation == TelecomManager.PRESENTATION_RESTRICTED ||
                presentation == TelecomManager.PRESENTATION_UNKNOWN
            val spoofed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                callDetails.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_FAILED
            SpamRules.evaluate(this, raw, hidden, spoofed)
        } catch (t: Throwable) {
            // En cas de souci, on ne bloque jamais : mieux vaut un spam qu'un appel important raté.
            Verdict(false, "Erreur", "")
        }

        // Rien de décisif en local : on demande à la base en ligne (sans bloquer le thread principal).
        if (!verdict.definitive && store.onlineLookup && verdict.number.isNotEmpty()) {
            Thread {
                val online = OnlineLookup.check(this, verdict.number)
                val finalVerdict = if (online?.spam == true) Verdict(true, online.label, verdict.number) else verdict
                finish(callDetails, finalVerdict, store)
            }.start()
            return
        }
        finish(callDetails, verdict, store)
    }

    private fun finish(callDetails: Call.Details, verdict: Verdict, store: Store) {
        if (!verdict.block) {
            respondToCall(callDetails, CallResponse.Builder().build())
            // Le téléphone sonne : on cherche en parallèle qui appelle, pour l'afficher.
            if (!verdict.contact && ReverseLookup.supports(this, verdict.number)) {
                Thread {
                    ReverseLookup.identify(this, verdict.number)?.let {
                        Notifier.notifyCallerId(this, verdict.number, it)
                    }
                }.start()
            }
            return
        }

        val silenceOnly = store.silenceOnly
        val response = if (silenceOnly) {
            CallResponse.Builder()
                .setSilenceCall(true)       // l'appel arrive, mais sans sonnerie ni vibration
                .build()
        } else {
            CallResponse.Builder()
                .setDisallowCall(true)      // l'appel n'atteint pas l'écran
                .setRejectCall(true)        // il est raccroché comme si tu avais refusé
                .setSkipCallLog(false)      // il reste visible dans le journal d'appels
                .setSkipNotification(true)  // pas de notif "appel manqué" du système…
                .build()                    // …on affiche la nôtre, plus discrète
        }
        respondToCall(callDetails, response)

        // Appel coupé : on peut prendre le temps de chercher le nom pour la notification.
        val time = System.currentTimeMillis()
        Thread {
            val name = if (verdict.number.isEmpty()) "" else
                ReverseLookup.identify(this, verdict.number)?.display(this).orEmpty()
            store.addHistory(HistoryEntry(time, verdict.number, verdict.reason, silenceOnly, name))
            Notifier.notifyFiltered(this, verdict.number, verdict.reason, silenceOnly, name)
        }.start()
    }
}
