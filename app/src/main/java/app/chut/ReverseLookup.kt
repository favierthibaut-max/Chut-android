package app.chut

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Annuaire inversé : retrouve le nom (personne ou entreprise) derrière un numéro.
 *
 * - Numéros suisses (+41) et du Liechtenstein (+423) : annuaire officiel tel.search.ch
 *   (fonctionne sans clé ; une clé gratuite donne plus de requêtes).
 * - Tous pays, si une clé Google est renseignée : fiches Google Maps (nom, ville, note et
 *   nombre d'avis). Couvre surtout les entreprises, pas les particuliers.
 * - Sinon : recherche web en un tap depuis la notification ou l'historique.
 */
object ReverseLookup {

    data class Identity(
        val name: String,
        val place: String,
        val rating: Double? = null,     // note Google sur 5
        val ratingCount: Int = 0
    ) {
        fun ratingText(): String =
            if (rating == null) "" else
                "★ " + String.format(Locale.FRANCE, "%.1f", rating) + " ($ratingCount avis Google)"

        fun display(): String =
            listOf(name, place, ratingText()).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private const val SEARCH_CH = "https://search.ch/tel/api/"
    private const val GOOGLE = "https://places.googleapis.com/v1/places:searchText"
    private const val FOUND_TTL = 30L * 24 * 3600_000
    private const val MISS_TTL = 7L * 24 * 3600_000
    private val ZIP_LINE = Regex("^\\d{4}\\s+.+")
    private val ZIP_PART = Regex("^\\d{4,5}\\s+.+")

    private fun isSwiss(e164: String) = e164.startsWith("+41") || e164.startsWith("+423")

    /** Peut-on chercher un nom pour ce numéro avec les réglages actuels ? */
    fun supports(context: Context, e164: String): Boolean {
        if (!e164.startsWith("+")) return false
        val s = Store(context)
        return s.reverseLookup && (isSwiss(e164) || s.googleKey.isNotBlank())
    }

    /** À appeler hors du thread principal. null = inconnu des annuaires, ou annuaires injoignables. */
    fun identify(context: Context, e164: String, timeoutMs: Int = 4000): Identity? {
        if (!supports(context, e164)) return null
        val store = Store(context)

        readCache(context, e164)?.let { (hit, value) -> if (hit) return value }

        var id: Identity? = null
        var failed = false
        if (isSwiss(e164)) {
            try {
                id = querySearchCh(e164, store.searchChKey, timeoutMs)
            } catch (e: Exception) {
                failed = true
            }
        }
        if (store.googleKey.isNotBlank()) {
            try {
                val g = queryGoogle(e164, store.googleKey, timeoutMs)
                if (g != null) {
                    id = id?.copy(rating = g.rating, ratingCount = g.ratingCount) ?: g
                }
            } catch (e: Exception) {
                if (id == null) failed = true
            }
        }
        if (id == null && failed) return null // erreur réseau : on ne met pas en cache
        writeCache(context, e164, id)
        return id
    }

    // --- tel.search.ch --------------------------------------------------------

    private fun querySearchCh(e164: String, key: String, timeoutMs: Int): Identity? {
        val sb = StringBuilder(SEARCH_CH)
            .append("?was=").append(URLEncoder.encode(e164, "UTF-8"))
            .append("&maxnum=1&lang=fr")
        if (key.isNotBlank()) sb.append("&key=").append(URLEncoder.encode(key.trim(), "UTF-8"))

        val conn = URL(sb.toString()).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs / 2
            conn.readTimeout = timeoutMs / 2
            conn.setRequestProperty("User-Agent", "Chut-Android/1.3")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP ${conn.responseCode}")

            conn.inputStream.use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, null) // l'encodage (ISO-8859-1) est lu dans l'en-tête XML
                var inEntry = false
                var title = ""
                var content = ""
                var city = ""
                while (true) {
                    val event = parser.next()
                    if (event == XmlPullParser.END_DOCUMENT) break
                    if (event == XmlPullParser.END_TAG && parser.name == "entry" && inEntry) break
                    if (event != XmlPullParser.START_TAG) continue
                    when (parser.name) {
                        "entry" -> inEntry = true
                        "title" -> if (inEntry) title = parser.nextText().trim()
                        "content" -> if (inEntry) content = parser.nextText()
                        "tel:city" -> if (inEntry) city = parser.nextText().trim()
                    }
                }
                if (title.isEmpty()) return null
                if (city.isEmpty()) {
                    city = content.lines().map { it.trim() }.firstOrNull { ZIP_LINE.matches(it) }
                        ?.substringAfter(' ')?.trim().orEmpty()
                }
                return Identity(title, city)
            }
        } finally {
            conn.disconnect()
        }
    }

    // --- Google Maps (Places API, Text Search) ----------------------------------

    private fun queryGoogle(e164: String, apiKey: String, timeoutMs: Int): Identity? {
        val conn = URL(GOOGLE).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs / 2
            conn.readTimeout = timeoutMs / 2
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Goog-Api-Key", apiKey.trim())
            conn.setRequestProperty(
                "X-Goog-FieldMask",
                "places.displayName,places.formattedAddress,places.rating," +
                    "places.userRatingCount,places.internationalPhoneNumber"
            )
            val body = JSONObject().put("textQuery", e164).put("languageCode", "fr").toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val places = json.optJSONArray("places") ?: return null

            for (i in 0 until places.length()) {
                val p = places.getJSONObject(i)
                // On ne garde qu'une fiche dont le numéro est exactement celui qui appelle,
                // pour ne jamais afficher la note d'une autre entreprise.
                val phone = SpamRules.clean(p.optString("internationalPhoneNumber", ""))
                if (phone != e164) continue
                val name = p.optJSONObject("displayName")?.optString("text").orEmpty()
                if (name.isEmpty()) continue
                val rating = if (p.has("rating")) p.optDouble("rating") else null
                return Identity(
                    name = name,
                    place = cityFromAddress(p.optString("formattedAddress", "")),
                    rating = rating,
                    ratingCount = p.optInt("userRatingCount", 0)
                )
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** "12 Rue X, 75001 Paris, France" → "Paris" */
    private fun cityFromAddress(address: String): String {
        val parts = address.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        parts.firstOrNull { ZIP_PART.matches(it) }?.let { return it.substringAfter(' ').trim() }
        return if (parts.size >= 2) parts[parts.size - 2] else ""
    }

    // --- Recherche web ------------------------------------------------------

    /** Lien de recherche web, pour les numéros qu'aucun annuaire ne couvre. */
    fun webSearchUri(e164: String): Uri =
        Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode("\"$e164\" qui appelle", "UTF-8"))

    // --- Cache local -------------------------------------------------------

    private fun cache(context: Context) =
        context.applicationContext.getSharedPreferences("chut_name_cache", Context.MODE_PRIVATE)

    /** (true, valeur) si le cache a une réponse valable (valeur null = « inconnu »). */
    private fun readCache(context: Context, e164: String): Pair<Boolean, Identity?>? {
        val raw = cache(context).getString(e164, null) ?: return null
        val p = raw.split("|")
        if (p.size != 5) return null
        val time = p[0].toLongOrNull() ?: return null
        val miss = p[1].isEmpty()
        if (System.currentTimeMillis() - time > (if (miss) MISS_TTL else FOUND_TTL)) return null
        if (miss) return Pair(true, null)
        return Pair(true, Identity(p[1], p[2], p[3].toDoubleOrNull(), p[4].toIntOrNull() ?: 0))
    }

    private fun writeCache(context: Context, e164: String, id: Identity?) {
        fun clean(s: String) = s.replace("|", " ")
        val value = listOf(
            System.currentTimeMillis().toString(),
            clean(id?.name.orEmpty()),
            clean(id?.place.orEmpty()),
            id?.rating?.toString().orEmpty(),
            id?.ratingCount?.toString().orEmpty()
        ).joinToString("|")
        cache(context).edit().putString(e164, value).apply()
    }

    fun clearCache(context: Context) = cache(context).edit().clear().apply()
}
