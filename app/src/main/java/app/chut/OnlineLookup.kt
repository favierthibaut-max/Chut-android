package app.chut

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Interroge la base communautaire PhoneBlock (https://phoneblock.net) pour savoir
 * si un numéro a été signalé comme indésirable par d'autres utilisateurs.
 *
 * Contrainte : Android laisse quelques secondes seulement pour décider avant de faire sonner.
 * On coupe donc la requête au bout de ~2,5 s et, en cas de doute, on laisse sonner.
 * Les réponses sont gardées en cache pour ne pas réinterroger à chaque appel.
 */
object OnlineLookup {

    data class Result(val spam: Boolean, val label: String)

    private const val BASE = "https://phoneblock.net/phoneblock/api/num/"
    private const val SPAM_TTL = 7L * 24 * 3600_000   // un spam reste spam une semaine
    private const val CLEAN_TTL = 24L * 3600_000      // un numéro propre est revérifié chaque jour

    /** À appeler hors du thread principal. Renvoie null si la base n'a pas pu répondre à temps. */
    fun check(context: Context, e164: String, timeoutMs: Int = 2500): Result? {
        if (!e164.startsWith("+")) return null
        val store = Store(context)
        readCache(context, e164)?.let { return it }
        val result = try {
            query(context, e164, store.phoneBlockToken, timeoutMs, store.onlineAggressive)
        } catch (e: Exception) {
            null
        }
        if (result != null) writeCache(context, e164, result)
        return result
    }

    private fun query(context: Context, e164: String, token: String, timeoutMs: Int, aggressive: Boolean): Result? {
        val url = URL(BASE + URLEncoder.encode(e164, "UTF-8") + "?format=json")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs / 2
            conn.readTimeout = timeoutMs / 2
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "Chut-Android/1.4")
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${token.trim()}")

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return Result(false, context.getString(R.string.pb_none))
            if (code != HttpURLConnection.HTTP_OK) return null

            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val votes = o.optInt("votes", 0)
            val confidence = o.optInt("spamConfidence", -1)
            val rating = o.optString("rating", "")
            val whiteListed = o.optBoolean("whiteListed", false)

            val minVotes = if (aggressive) 1 else 3
            val minConfidence = if (aggressive) 30 else 60
            val spam = !whiteListed && rating != "A_LEGITIMATE" &&
                (votes >= minVotes || confidence >= minConfidence)

            val kind = ratingLabel(context, rating)
            // PhoneBlock renvoie la note « A_LEGITIMATE » par défaut pour un numéro qu'il ne connaît
            // pas : sans aucun signalement, on dit donc « aucun signalement », pas « fiable ».
            val label = when {
                whiteListed -> context.getString(R.string.pb_trusted)
                votes <= 0 && confidence <= 0 -> context.getString(R.string.pb_none)
                rating == "A_LEGITIMATE" -> context.getString(R.string.pb_trusted)
                else -> context.getString(R.string.pb_reported, votes) + (if (kind != null) " ($kind)" else "")
            }
            return Result(spam, label)
        } finally {
            conn.disconnect()
        }
    }

    private fun ratingLabel(context: Context, rating: String): String? = when (rating) {
        "B_MISSED" -> R.string.kind_missed
        "C_PING" -> R.string.kind_ping
        "D_POLL" -> R.string.kind_poll
        "E_ADVERTISING" -> R.string.kind_ad
        "F_GAMBLE" -> R.string.kind_gamble
        "G_FRAUD" -> R.string.kind_fraud
        else -> null
    }?.let { context.getString(it) }

    /** Résultat déjà connu (cache), sans aucune requête réseau. */
    fun peek(context: Context, e164: String): Result? =
        if (e164.startsWith("+")) readCache(context, e164) else null

    // --- Cache local -------------------------------------------------------

    private fun cache(context: Context) =
        context.applicationContext.getSharedPreferences("chut_lookup_cache_v2", Context.MODE_PRIVATE)

    private fun readCache(context: Context, e164: String): Result? {
        val raw = cache(context).getString(e164, null) ?: return null
        val parts = raw.split("|", limit = 3)
        if (parts.size != 3) return null
        val spam = parts[0] == "1"
        val time = parts[1].toLongOrNull() ?: return null
        val ttl = if (spam) SPAM_TTL else CLEAN_TTL
        if (System.currentTimeMillis() - time > ttl) return null
        return Result(spam, parts[2])
    }

    private fun writeCache(context: Context, e164: String, r: Result) {
        val value = (if (r.spam) "1" else "0") + "|" + System.currentTimeMillis() + "|" + r.label
        cache(context).edit().putString(e164, value).apply()
    }

    fun clearCache(context: Context) = cache(context).edit().clear().apply()
}
