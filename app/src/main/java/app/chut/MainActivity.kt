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
import android.provider.CallLog
import android.text.format.DateFormat
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
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        Notifier.ensureChannel(this)

        bindSwitch(R.id.swSilence, { store.silenceOnly }) { store.silenceOnly = it }
        bindSwitch(R.id.swOfficial, { store.blockOfficialTelemarketing }) { store.blockOfficialTelemarketing = it }
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
            toast(getString(if (store.searchChKey.isEmpty()) R.string.key_removed else R.string.key_saved))
        }

        findViewById<EditText>(R.id.etGoogleKey).setText(store.googleKey)
        findViewById<Button>(R.id.btnGoogleKey).setOnClickListener {
            store.googleKey = findViewById<EditText>(R.id.etGoogleKey).text.toString()
            ReverseLookup.clearCache(this)
            toast(getString(if (store.googleKey.isEmpty()) R.string.key_removed else R.string.key_saved))
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
            toast(getString(if (store.phoneBlockToken.isEmpty()) R.string.key_removed else R.string.key_saved))
        }

        findViewById<Button>(R.id.btnActivate).setOnClickListener { requestScreeningRole() }
        findViewById<Button>(R.id.btnPermissions).setOnClickListener { requestMissingPermissions() }
        findViewById<Button>(R.id.btnCallLog).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), REQ_PERMS)
        }

        findViewById<Button>(R.id.btnAddBlack).setOnClickListener {
            addEntry(R.id.etBlack) { store.addToBlacklist(it) }
        }
        findViewById<Button>(R.id.btnAddWhite).setOnClickListener {
            addEntry(R.id.etWhite) { store.addToWhitelist(it) }
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener { runTest() }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.clear_history_q)
                .setPositiveButton(R.string.clear) { _, _ -> store.clearHistory(); refresh() }
                .setNegativeButton(R.string.cancel, null)
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
                R.string.role_unavailable,
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
                R.string.role_denied,
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
            text = getString(if (active) R.string.status_active else R.string.status_inactive)
            setTextColor(getColor(if (active) R.color.ok else R.color.ko))
        }
        findViewById<TextView>(R.id.tvStatusDetail).setText(when {
            !active -> R.string.detail_inactive
            missing.contains(Manifest.permission.POST_NOTIFICATIONS) -> R.string.detail_no_notif
            missing.contains(Manifest.permission.READ_CONTACTS) -> R.string.detail_no_contacts
            else -> R.string.detail_ok
        })
        findViewById<Button>(R.id.btnActivate).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.btnPermissions).visibility =
            if (active && missing.isNotEmpty()) View.VISIBLE else View.GONE

        findViewById<Button>(R.id.btnExpect).text =
            if (store.isExpectingCall())
                getString(R.string.expect_on, DateFormat.getTimeFormat(this).format(Date(store.expectingCallUntil)))
            else getString(R.string.expect_off)

        renderList(R.id.listBlack, store.blacklist) { entry ->
            confirm(getString(R.string.remove_black_q, entry)) { store.removeFromBlacklist(entry) }
        }
        renderList(R.id.listWhite, store.whitelist) { entry ->
            confirm(getString(R.string.remove_white_q, entry)) { store.removeFromWhitelist(entry) }
        }
        renderHistory(enrich = true)
    }

    // ---------------------------------------------------------------- Listes

    private fun addEntry(editId: Int, add: (String) -> Unit) {
        val edit = findViewById<EditText>(editId)
        val value = SpamRules.clean(edit.text.toString())
        if (value.isEmpty() || value == "+") {
            Toast.makeText(this, R.string.enter_number, Toast.LENGTH_SHORT).show()
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
            container.addView(row(getString(R.string.no_numbers), dim = true, onClick = null))
            return
        }
        entries.forEach { entry ->
            container.addView(row("$entry   ✕", onClick = { onTap(entry) }))
        }
    }

    /** Une ligne de l'historique : un appel d'un numéro inconnu. */
    private data class CallRow(
        val time: Long, val number: String, val name: String,
        val status: String, val reason: String, val blocked: Boolean
    )

    /** L'édition « lite » ne déclare pas l'accès au journal d'appels. */
    private fun callLogDeclared(): Boolean = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.READ_CALL_LOG) == true
    } catch (e: Exception) {
        false
    }

    private fun hasCallLog() =
        checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    private fun statusOf(h: HistoryEntry) = getString(when {
        h.rang -> R.string.action_rang
        h.silenced -> R.string.action_silenced
        else -> R.string.action_blocked
    })

    /**
     * Avec l'accès au journal d'appels : tous les appels reçus de numéros inconnus, y compris
     * avant l'installation, complétés par ce que Chut sait (raison du blocage, nom trouvé).
     * Sans : seulement ce que Chut a vu passer depuis son installation.
     */
    private fun buildRows(): List<CallRow> {
        val history = store.history()
        val fromHistory = history.take(100).map { h ->
            CallRow(h.time, h.number, h.name, statusOf(h), h.reason, !h.rang)
        }
        if (!hasCallLog()) return fromHistory

        val iso = SpamRules.countryIso(this)
        val rows = mutableListOf<CallRow>()
        val used = HashSet<HistoryEntry>()
        try {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE,
                    CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER_PRESENTATION
                ),
                null, null, CallLog.Calls.DATE + " DESC"
            )?.use { c ->
                while (c.moveToNext() && rows.size < 100) {
                    val type = c.getInt(2)
                    if (type == CallLog.Calls.OUTGOING_TYPE) continue
                    if (!c.getString(3).isNullOrBlank()) continue // contact : déjà identifié
                    val raw = if (c.getInt(4) == CallLog.Calls.PRESENTATION_ALLOWED)
                        SpamRules.clean(c.getString(0).orEmpty()) else ""
                    val number = SpamRules.toE164(raw, iso)
                    val time = c.getLong(1)
                    val h = history.firstOrNull {
                        it !in used && it.number == number && abs(it.time - time) < 120_000
                    }
                    if (h != null) used += h
                    val name = h?.name?.takeIf { it.isNotEmpty() }
                        ?: ReverseLookup.peek(this, number)?.display(this).orEmpty()
                    val reason = h?.reason ?: OnlineLookup.peek(this, number)?.label.orEmpty()
                    val blockedByChut = h != null && !h.rang
                    val status = if (blockedByChut) statusOf(h!!) else getString(when (type) {
                        CallLog.Calls.MISSED_TYPE -> R.string.cl_missed
                        CallLog.Calls.REJECTED_TYPE -> R.string.cl_rejected
                        CallLog.Calls.BLOCKED_TYPE -> R.string.action_blocked
                        CallLog.Calls.VOICEMAIL_TYPE -> R.string.cl_voicemail
                        else -> R.string.cl_answered
                    })
                    rows += CallRow(time, number, name, status, reason,
                        blockedByChut || type == CallLog.Calls.BLOCKED_TYPE)
                }
            }
        } catch (e: Exception) {
            return fromHistory
        }
        return rows
    }

    private fun renderHistory(enrich: Boolean) {
        val container = findViewById<LinearLayout>(R.id.listHistory)
        container.removeAllViews()
        val callLog = hasCallLog()
        findViewById<Button>(R.id.btnCallLog).visibility =
            if (!callLog && callLogDeclared()) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnClear).visibility = if (callLog) View.GONE else View.VISIBLE

        val rows = buildRows()
        if (rows.isEmpty()) {
            container.addView(row(getString(R.string.no_history), dim = true, onClick = null))
            return
        }
        val fmt = SimpleDateFormat(
            DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMMjjmm"), Locale.getDefault()
        )
        rows.forEach { r ->
            val pretty = SpamRules.pretty(r.number, this)
            val label = if (r.name.isEmpty()) pretty else "${r.name} ($pretty)"
            val icon = if (r.blocked) "🚫" else "📞"
            val details = listOf(fmt.format(Date(r.time)), r.status, r.reason)
                .filter { it.isNotEmpty() }.joinToString(" · ")
            container.addView(row("$icon $label\n$details", onClick = { callMenu(r.number) }))
        }
        if (enrich) enrich(rows)
    }

    private var enriching = false

    /** Interroge PhoneBlock et les annuaires pour les numéros pas encore connus, puis réaffiche. */
    private fun enrich(rows: List<CallRow>) {
        if (enriching) return
        val todo = rows.map { it.number }.filter { it.startsWith("+") }.distinct().filter {
            (store.onlineLookup && OnlineLookup.peek(this, it) == null) ||
                (ReverseLookup.supports(this, it) && !ReverseLookup.isCached(this, it))
        }.take(25)
        if (todo.isEmpty()) return
        enriching = true
        Thread {
            todo.forEach { n ->
                if (store.onlineLookup) OnlineLookup.check(this, n, timeoutMs = 5000)
                ReverseLookup.identify(this, n, timeoutMs = 5000)
            }
            runOnUiThread {
                enriching = false
                if (!isFinishing) renderHistory(enrich = false)
            }
        }.start()
    }

    private fun callMenu(number: String) {
        if (number.isEmpty()) return
        val label = SpamRules.pretty(number, this)
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(arrayOf(
                getString(R.string.menu_not_spam), getString(R.string.menu_always_block),
                getString(R.string.call_back), getString(R.string.menu_who)
            )) { _, which ->
                when (which) {
                    0 -> { store.addToWhitelist(number); toast(getString(R.string.toast_allowed, label)) }
                    1 -> { store.addToBlacklist(number); toast(getString(R.string.toast_blocked, label)) }
                    2 -> startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
                    3 -> startActivity(Intent(Intent.ACTION_VIEW, ReverseLookup.webSearchUri(this, number)))
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
        showTest(null, getString(R.string.checking_online))
        Thread {
            // Pas de limite serrée ici : on n'est pas pendant un vrai appel.
            val online = if (needOnline) OnlineLookup.check(this, local.number, timeoutMs = 8000) else null
            val who = if (needName) ReverseLookup.identify(this, local.number, timeoutMs = 8000) else null
            val block = (online != null && online.spam) || local.block
            var reason = when {
                online != null && online.spam -> online.label
                needOnline && online == null -> getString(R.string.online_unreachable)
                online != null -> online.label
                else -> local.reason
            }
            if (who != null) reason += "\n👤 ${who.display(this)}"
            runOnUiThread { showTest(block, reason) }
        }.start()
    }

    private fun showTest(block: Boolean?, reason: String) {
        findViewById<TextView>(R.id.tvTest).apply {
            text = when (block) {
                null -> reason
                true -> getString(R.string.test_block, reason)
                false -> getString(R.string.test_ring, reason)
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
            .setPositiveButton(R.string.yes) { _, _ -> action(); refresh() }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_ROLE = 1
        private const val REQ_PERMS = 2
    }
}
