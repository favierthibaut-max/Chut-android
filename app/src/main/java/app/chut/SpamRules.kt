package app.chut

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

data class Verdict(
    val block: Boolean,
    val reason: String,
    val number: String, // numéro normalisé (E.164 si possible)
    /** false = aucune règle locale n'a tranché : une vérification en ligne peut encore décider. */
    val definitive: Boolean = true,
    /** true = numéro enregistré dans les contacts (pas besoin de l'identifier). */
    val contact: Boolean = false
)

object SpamRules {

    /**
     * Plages de numéros que la loi réserve au démarchage commercial, par indicatif pays.
     * Elles sont bloquées où que l'utilisateur se trouve (un démarcheur français peut appeler
     * un téléphone suisse). Pour ajouter un pays : une ligne ici, avec son code ISO.
     *
     * - France (Arcep, depuis 2023) : 0162, 0163, 0270… en métropole ; 09475 à 09479 en Outre-mer.
     * - Espagne (depuis le 17 octobre 2026) : numéros à 9 chiffres commençant par 400.
     */
    private data class OfficialRange(val countryCode: String, val iso: String, val prefixes: List<String>)

    private val OFFICIAL_TELEMARKETING = listOf(
        OfficialRange("+33", "FR", listOf(
            "162", "163", "270", "271", "377", "378",
            "424", "425", "568", "569", "948", "949",
            "9475", "9476", "9477", "9478", "9479"
        )),
        OfficialRange("+590", "GP", listOf("9475")), // Guadeloupe, Saint-Martin, Saint-Barthélemy
        OfficialRange("+594", "GF", listOf("9476")), // Guyane
        OfficialRange("+596", "MQ", listOf("9477")), // Martinique
        OfficialRange("+262", "RE", listOf("9478", "9479")), // La Réunion, Mayotte
        OfficialRange("+34", "ES", listOf("400"))
    )

    fun evaluate(
        context: Context,
        rawNumber: String?,
        hidden: Boolean,
        spoofed: Boolean,
        now: Long = System.currentTimeMillis()
    ): Verdict {
        val store = Store(context)
        val iso = countryIso(context)
        val raw = clean(rawNumber.orEmpty())

        val expecting = store.isExpectingCall(now)

        // 1. Numéro masqué / inconnu
        if (hidden || raw.isEmpty()) {
            return if (store.blockHidden && !expecting) Verdict(true, context.getString(R.string.r_hidden), "")
            else Verdict(false, context.getString(R.string.r_hidden_allowed), "")
        }

        val number = toE164(raw, iso)

        // 2. Ce qui passe toujours
        if (store.whitelist.any { matches(it, raw, number, iso) })
            return Verdict(false, context.getString(R.string.r_white), number)
        if (isInContacts(context, rawNumber!!))
            return Verdict(false, context.getString(R.string.r_contact), number, contact = true)

        // 3. Ta liste noire passe avant tout le reste
        if (store.blacklist.any { matches(it, raw, number, iso) })
            return Verdict(true, context.getString(R.string.r_black), number)

        // 4. Mode « J'attends un appel » : tout le reste sonne
        if (expecting)
            return Verdict(false, context.getString(R.string.r_expect), number)

        // 5. Exception "appel insistant" (urgence possible)
        if (store.allowRepeated && store.filteredRecently(number, now))
            return Verdict(false, context.getString(R.string.r_repeat), number)

        // 6. Règles automatiques
        if (store.blockOfficialTelemarketing) {
            officialTelemarketingCountry(number)?.let { iso ->
                val country = Locale("", iso).displayCountry
                return Verdict(true, context.getString(R.string.r_official, country), number)
            }
        }
        if (store.blockSpoofed && spoofed)
            return Verdict(true, context.getString(R.string.r_spoof), number)
        if (store.blockAllUnknown)
            return Verdict(true, context.getString(R.string.r_strict), number)

        return Verdict(false, context.getString(R.string.r_none), number, definitive = false)
    }

    /** Code ISO du pays si ce numéro est dans une plage officielle de démarchage, sinon null. */
    fun officialTelemarketingCountry(e164: String): String? =
        OFFICIAL_TELEMARKETING.firstOrNull { r ->
            e164.startsWith(r.countryCode) && r.prefixes.any { e164.startsWith(r.countryCode + it) }
        }?.iso

    /** Une entrée de liste correspond-elle à ce numéro ? Gère numéros complets et préfixes. */
    fun matches(entry: String, raw: String, e164: String, iso: String): Boolean {
        val e = clean(entry)
        if (e.isEmpty()) return false
        if (e.startsWith("+")) return e164.startsWith(e) || raw.startsWith(e)
        if (raw.startsWith(e)) return true
        val entryE164 = toE164(e, iso)
        return entryE164.startsWith("+") && entryE164 == e164
    }

    /** Garde uniquement chiffres et "+" initial ; transforme 00xx en +xx. */
    fun clean(input: String): String {
        val sb = StringBuilder()
        for (c in input) {
            if (c.isDigit()) sb.append(c)
            else if (c == '+' && sb.isEmpty()) sb.append(c)
        }
        var s = sb.toString()
        if (s.startsWith("00")) s = "+" + s.substring(2)
        return s
    }

    fun toE164(cleaned: String, iso: String): String {
        if (cleaned.isEmpty() || cleaned.startsWith("+")) return cleaned
        return try {
            PhoneNumberUtils.formatNumberToE164(cleaned, iso) ?: cleaned
        } catch (e: Exception) {
            cleaned
        }
    }

    fun pretty(number: String, context: Context): String {
        if (number.isEmpty()) return context.getString(R.string.r_hidden)
        return try {
            PhoneNumberUtils.formatNumber(number, countryIso(context)) ?: number
        } catch (e: Exception) {
            number
        }
    }

    fun countryIso(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        // Pays de la carte SIM d'abord : c'est le format dans lequel l'utilisateur saisit ses numéros,
        // et il ne change pas en voyage (en itinérance, les appels arrivent au format international).
        val iso = tm?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: tm?.networkCountryIso?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country
        return iso.uppercase(Locale.ROOT)
    }

    private fun isInContacts(context: Context, number: String): Boolean {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver
                .query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
                ?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
