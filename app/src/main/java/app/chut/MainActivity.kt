package app.chut

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        Notifier.ensureChannel(this)

        bindSwitch(R.id.swSilence, { store.silenceOnly }) { store.silenceOnly = it }
        bindSwitch(R.id.swFr, { store.blockFrenchTelemarketing }) { store.blockFrenchTelemarketing = it }
        bindSwitch(R.id.swSpoof, { store.blockSpoofed }) { store.blockSpoofed = it }
        bindSwitch(R.id.swHidden, { store.blockHidden }) { store.blockHidden = it }
        bindSwitch(R.id.swStrict, { store.blockAllUnknown }) { store.blockAllUnknown = it }
        bindSwitch(R.id.swRepeat, { store.allowRepeated }) { store.allowRepeated = it }
        bindSwitch(R.id.swOnline, { store.onlineLookup }) { store.onlineLookup = it }
        bindSwitch(R.id.swAggressive, { store.onlineAggressive }) {
            store.onlineAggressive = it
            OnlineLookup.clearCache(this)
        }

        bindSwitch(R.id.swReverse, { store.reverseLookup }) { store.reverseLookup = it }
        findViewById<EditText>(R.id.etSearchKey).setText(store.searchChKey)
        findViewById<Button>(R.id.btnSearchKey).setOnClickListener {
            store.searchChKey = findViewById<EditText>(R.id.etSearchKey).text.toString()
            ReverseLookup.clearCache(this)
            toast(if (store.searchChKey.isEmpty()) "Clé supprimée" else "Clé enregistrée")
        }

        findViewById<EditText>(R.id.etGoogleKey).setText(store.googleKey)
        findViewById<Button>(R.id.btnGoogleKey).setOnClickListener {
            store.googleKey = findViewById<EditText>(R.id.etGoogleKey).text.toString()
            ReverseLookup.clearCache(this)
            toast(if (store.googleKey.isEmpty()) "Clé supprimée" else "Clé enregistrée")
        }

        findViewById<Button>(R.id.btnExpect).setOnClickListener {
            store.expectingCallUntil =
                if (store.isExpectingCall()) 0L else System.currentTimeMillis() + 2 * 3600_000L
            refresh()
        }

        findViewById<EditText>(R.id.etToken).setText(store.phoneBlockToken)
        findViewById<Button>(R.id.btnToken).setOnClickListener {
            store.phoneBlockToken = findViewById<EditText>(R.id.etToken).text.toString()
            OnlineLookup.clearCache(this)
            toast(if (store.phoneBlockToken.isEmpty()) "Clé supprimée" else "Clé enregistrée")
        }

        findViewById<Button>(R.id.btnActivate).setOnClickListener { requestScreeningRole() }
        findViewById<Button>(R.id.btnPermissions).setOnClickListener { requestMissingPermissions() }

        findViewById<Button>(R.id.btnAddBlack).setOnClickListener {
            addEntry(R.id.etBlack) { store.addToBlacklist(it) }
        }
        findViewById<Button>(R.id.btnAddWhite).setOnClickListener {
            addEntry(R.id.etWhite) { store.addToWhitelist(it) }
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener { runTest() }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Effacer l'historique des appels filtrés ?")
                .setPositiveButton("Effacer") { _, _ -> store.clearHistory(); refresh() }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------------------------------------------------------- État

    private fun hasRole(): Boolean {
        val rm = getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    private fun missingPermissions(): List<String> {
        val wanted = mutableListOf(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        return wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun requestScreeningRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            Toast.makeText(
                this,
                "Ton téléphone ne propose pas le filtrage d'appels pour les applis tierces.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), REQ_ROLE)
    }

    private fun requestMissingPermissions() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ROLE) {
            if (hasRole()) requestMissingPermissions()
            else Toast.makeText(
                this,
                "Choisis « Chut » comme appli d'identification de l'appelant et anti-spam.",
                Toast.LENGTH_LONG
            ).show()
            refresh()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val active = hasRole()
        val missing = missingPermissions()

        findViewById<TextView>(R.id.tvStatus).apply {
            text = if (active) "🛡️ Protection active" else "⚠️ Protection inactive"
            setTextColor(getColor(if (active) R.color.ok else R.color.ko))
        }
        findViewById<TextView>(R.id.tvStatusDetail).text = when {
            !active -> "Appuie sur « Activer la protection » et choisis Chut comme appli anti-spam."
            missing.contains(Manifest.permission.POST_NOTIFICATIONS) ->
                "Les appels sont filtrés, mais sans notification : autorise-les ci-dessous."
            missing.contains(Manifest.permission.READ_CONTACTS) ->
                "Autorise l'accès aux contacts pour être sûr qu'ils ne soient jamais filtrés."
            else -> "Les appels indésirables ne sonneront plus. Tu recevras juste une notification discrète."
        }
        findViewById<Button>(R.id.btnActivate).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.btnPermissions).visibility =
            if (active && missing.isNotEmpty()) View.VISIBLE else View.GONE

        findViewById<Button>(R.id.btnExpect).text =
            if (store.isExpectingCall())
                "⏳ Tout sonne jusqu'à ${SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(store.expectingCallUntil))} — arrêter"
            else "J'attends un appel important (tout laisser sonner 2 h)"

        renderList(R.id.listBlack, store.blacklist) { entry ->
            confirm("Retirer $entry de la liste noire ?") { store.removeFromBlacklist(entry) }
        }
        renderList(R.id.listWhite, store.whitelist) { entry ->
            confirm("Retirer $entry de la liste blanche ?") { store.removeFromWhitelist(entry) }
        }
        renderHistory()
    }

    // ---------------------------------------------------------------- Listes

    private fun addEntry(editId: Int, add: (String) -> Unit) {
        val edit = findViewById<EditText>(editId)
        val value = SpamRules.clean(edit.text.toString())
        if (value.isEmpty() || value == "+") {
            Toast.makeText(this, "Entre un numéro ou un préfixe", Toast.LENGTH_SHORT).show()
            return
        }
        add(value)
        edit.setText("")
        refresh()
    }

    private fun renderList(containerId: Int, entries: List<String>, onTap: (String) -> Unit) {
        val container = findViewById<LinearLayout>(containerId)
        container.removeAllViews()
        if (entries.isEmpty()) {
            container.addView(row("Aucun numéro", dim = true, onClick = null))
            return
        }
        entries.forEach { entry ->
            container.addView(row("$entry   ✕", onClick = { onTap(entry) }))
        }
    }

    private fun renderHistory() {
        val container = findViewById<LinearLayout>(R.id.listHistory)
        container.removeAllViews()
        val items = store.history().take(50)
        if (items.isEmpty()) {
            container.addView(row("Aucun appel filtré pour l'instant", dim = true, onClick = null))
            return
        }
        val fmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.FRANCE)
        items.forEach { h ->
            val label = if (h.name.isEmpty()) SpamRules.pretty(h.number, this)
            else "${h.name} (${SpamRules.pretty(h.number, this)})"
            val action = if (h.silenced) "mis en silence" else "bloqué"
            val text = "$label\n${fmt.format(Date(h.time))} · $action · ${h.reason}"
            container.addView(row(text, onClick = { historyMenu(h) }))
        }
    }

    private fun historyMenu(h: HistoryEntry) {
        if (h.number.isEmpty()) return
        val label = SpamRules.pretty(h.number, this)
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(arrayOf("Ce n'est pas un spam (autoriser)", "Toujours bloquer ce numéro", "Rappeler", "Qui est-ce ? (recherche web)")) { _, which ->
                when (which) {
                    0 -> { store.addToWhitelist(h.number); toast("$label est autorisé") }
                    1 -> { store.addToBlacklist(h.number); toast("$label ajouté à la liste noire") }
                    2 -> startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", h.number, null)))
                    3 -> startActivity(Intent(Intent.ACTION_VIEW, ReverseLookup.webSearchUri(h.number)))
                }
                refresh()
            }
            .show()
    }

    // ---------------------------------------------------------------- Test

    private fun runTest() {
        val input = findViewById<EditText>(R.id.etTest).text.toString()
        val local = SpamRules.evaluate(this, input, hidden = input.isBlank(), spoofed = false)
        val needOnline = !local.definitive && store.onlineLookup && local.number.isNotEmpty()
        val needName = !local.contact && ReverseLookup.supports(this, local.number)
        if (!needOnline && !needName) {
            showTest(local.block, local.reason)
            return
        }
        showTest(null, "Vérification en ligne…")
        Thread {
            // Pas de limite serrée ici : on n'est pas pendant un vrai appel.
            val online = if (needOnline) OnlineLookup.check(this, local.number, timeoutMs = 8000) else null
            val who = if (needName) ReverseLookup.identify(this, local.number, timeoutMs = 8000) else null
            val block = (online != null && online.spam) || local.block
            var reason = when {
                online != null && online.spam -> online.label
                needOnline && online == null -> "base en ligne injoignable"
                online != null -> online.label
                else -> local.reason
            }
            if (who != null) reason += "\n👤 ${who.display()}"
            runOnUiThread { showTest(block, reason) }
        }.start()
    }

    private fun showTest(block: Boolean?, reason: String) {
        findViewById<TextView>(R.id.tvTest).apply {
            text = when (block) {
                null -> reason
                true -> "🚫 Ne sonnera pas — $reason"
                false -> "✅ Sonnera normalement — $reason"
            }
            setTextColor(getColor(if (block == true) R.color.ko else R.color.ok))
        }
    }

    // ---------------------------------------------------------------- Utilitaires

    private fun bindSwitch(id: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = get()
        sw.setOnCheckedChangeListener { _, checked -> set(checked) }
    }

    private fun row(text: String, dim: Boolean = false, onClick: (() -> Unit)?): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            val pad = (10 * resources.displayMetrics.density).toInt()
            setPadding(pad / 2, pad, pad / 2, pad)
            if (dim) alpha = 0.6f
            if (onClick != null) {
                val tv = TypedValue()
                this@MainActivity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { onClick() }
            }
        }

    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Oui") { _, _ -> action(); refresh() }
            .setNegativeButton("Non", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_ROLE = 1
        private const val REQ_PERMS = 2
    }
}
