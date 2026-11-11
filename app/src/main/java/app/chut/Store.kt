package app.chut

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val time: Long,
    val number: String,   // numéro normalisé (E.164 si possible), vide si masqué
    val reason: String,
    val silenced: Boolean, // true = mis en silence, false = raccroché
    val name: String = "",  // nom trouvé dans l'annuaire inversé, s'il y en a un
    val rang: Boolean = false // true = l'appel a sonné normalement (non filtré)
)

/** Réglages, listes noire/blanche et historique, stockés localement sur le téléphone. */
class Store(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("chut_prefs", Context.MODE_PRIVATE)

    // --- Règles -------------------------------------------------------------

    /** Plages officielles de démarchage (France, Espagne…), quel que soit le pays où l'on se trouve. */
    var blockOfficialTelemarketing: Boolean
        get() = prefs.getBoolean(K_FR, true)
        set(v) = prefs.edit().putBoolean(K_FR, v).apply()

    var blockSpoofed: Boolean
        get() = prefs.getBoolean(K_SPOOF, true)
        set(v) = prefs.edit().putBoolean(K_SPOOF, v).apply()

    var blockHidden: Boolean
        get() = prefs.getBoolean(K_HIDDEN, false)
        set(v) = prefs.edit().putBoolean(K_HIDDEN, v).apply()

    var blockAllUnknown: Boolean
        get() = prefs.getBoolean(K_STRICT, false)
        set(v) = prefs.edit().putBoolean(K_STRICT, v).apply()

    var allowRepeated: Boolean
        get() = prefs.getBoolean(K_REPEAT, false)
        set(v) = prefs.edit().putBoolean(K_REPEAT, v).apply()

    /** false = l'appel est raccroché ; true = il arrive mais sans sonner. */
    var silenceOnly: Boolean
        get() = prefs.getBoolean(K_SILENCE, false)
        set(v) = prefs.edit().putBoolean(K_SILENCE, v).apply()

    /** Vérification en ligne dans la base PhoneBlock. */
    var onlineLookup: Boolean
        get() = prefs.getBoolean(K_ONLINE, true)
        set(v) = prefs.edit().putBoolean(K_ONLINE, v).apply()

    /** true = bloque dès 1 signalement ; false = à partir de 3. */
    var onlineAggressive: Boolean
        get() = prefs.getBoolean(K_ONLINE_AGGR, false)
        set(v) = prefs.edit().putBoolean(K_ONLINE_AGGR, v).apply()

    /** Clé API PhoneBlock facultative (Paramètres de ton compte phoneblock.net). */
    var phoneBlockToken: String
        get() = prefs.getString(K_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(K_TOKEN, v.trim()).apply()

    /** Annuaire inversé suisse (tel.search.ch). */
    var reverseLookup: Boolean
        get() = prefs.getBoolean(K_REVERSE, true)
        set(v) = prefs.edit().putBoolean(K_REVERSE, v).apply()

    /** Clé API tel.search.ch facultative. */
    var searchChKey: String
        get() = prefs.getString(K_SEARCHCH, "") ?: ""
        set(v) = prefs.edit().putString(K_SEARCHCH, v.trim()).apply()

    /** Clé Google Maps Platform (Places API) facultative : nom + note Google des entreprises. */
    var googleKey: String
        get() = prefs.getString(K_GOOGLE, "") ?: ""
        set(v) = prefs.edit().putString(K_GOOGLE, v.trim()).apply()

    /** Mode « J'attends un appel » : tout sonne (sauf liste noire) jusqu'à cette heure. */
    var expectingCallUntil: Long
        get() = prefs.getLong(K_EXPECT, 0L)
        set(v) = prefs.edit().putLong(K_EXPECT, v).apply()

    fun isExpectingCall(now: Long = System.currentTimeMillis()) = now < expectingCallUntil

    // --- Listes ------------------------------------------------------------

    val blacklist: List<String> get() = readSet(K_BLACK)
    val whitelist: List<String> get() = readSet(K_WHITE)

    fun addToBlacklist(entry: String) = editSet(K_BLACK) { it.add(entry) }
    fun removeFromBlacklist(entry: String) = editSet(K_BLACK) { it.remove(entry) }

    fun addToWhitelist(entry: String) {
        editSet(K_WHITE) { it.add(entry) }
        editSet(K_BLACK) { it.remove(entry) }
    }
    fun removeFromWhitelist(entry: String) = editSet(K_WHITE) { it.remove(entry) }

    private fun readSet(key: String): List<String> =
        prefs.getStringSet(key, emptySet())?.sorted() ?: emptyList()

    private fun editSet(key: String, block: (MutableSet<String>) -> Unit) {
        // Toujours copier : on ne doit jamais modifier le Set renvoyé par SharedPreferences.
        val set = HashSet(prefs.getStringSet(key, emptySet()) ?: emptySet())
        block(set)
        prefs.edit().putStringSet(key, set).apply()
    }

    // --- Historique ----------------------------------------------------------

    fun history(): List<HistoryEntry> {
        val raw = prefs.getString(K_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(o.getLong("t"), o.getString("n"), o.getString("r"), o.optBoolean("s"), o.optString("nm", ""), o.optBoolean("a", false))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addHistory(entry: HistoryEntry) {
        val arr = JSONArray()
        (listOf(entry) + history()).take(MAX_HISTORY).forEach { e ->
            arr.put(
                JSONObject()
                    .put("t", e.time)
                    .put("n", e.number)
                    .put("r", e.reason)
                    .put("s", e.silenced)
                    .put("nm", e.name)
                    .put("a", e.rang)
            )
        }
        prefs.edit().putString(K_HISTORY, arr.toString()).apply()
    }

    fun clearHistory() = prefs.edit().remove(K_HISTORY).apply()

    /** Ce numéro a-t-il déjà été filtré dans les dernières minutes ? */
    fun filteredRecently(number: String, now: Long, windowMs: Long = 3 * 60_000L): Boolean =
        number.isNotEmpty() && history().any { !it.rang && it.number == number && now - it.time in 0..windowMs }

    companion object {
        private const val K_FR = "rule_fr_telemarketing"
        private const val K_SPOOF = "rule_spoofed"
        private const val K_HIDDEN = "rule_hidden"
        private const val K_STRICT = "rule_all_unknown"
        private const val K_REPEAT = "rule_allow_repeated"
        private const val K_SILENCE = "mode_silence_only"
        private const val K_ONLINE = "rule_online_lookup"
        private const val K_ONLINE_AGGR = "rule_online_aggressive"
        private const val K_TOKEN = "phoneblock_token"
        private const val K_REVERSE = "reverse_lookup"
        private const val K_SEARCHCH = "searchch_key"
        private const val K_GOOGLE = "google_places_key"
        private const val K_EXPECT = "expecting_call_until"
        private const val K_BLACK = "blacklist"
        private const val K_WHITE = "whitelist"
        private const val K_HISTORY = "history"
        private const val MAX_HISTORY = 200
    }
}
