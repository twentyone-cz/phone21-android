/*
 * fork: obrazovka stavu tunelu. Odsud se také vyvolá systémový dialog
 * povolení VPN (VpnService.prepare musí přijít z aktivity).
 *
 * Režim tunelu (TsModeController) určuje stupeň přístupu: „jen hovory“ jede
 * tunelem uvnitř appky a souhlas s VPN nepotřebuje (Připojit, přihlášení
 * přes web — bez dialogu). Přechod na stupeň, který potřebuje systémovou
 * VPN (miniserver, síť, synchronizace kontaktů), se ptá: potvrzení, když
 * běží jiná VPN (21p.45), a systémový souhlas; při odmítnutí volba zůstane
 * na „jen hovory“. Stupeň převzatý z miniserveru se ptá stejně a odmítnutí
 * se zapíše zpátky na miniserver, ať sdílená volba odpovídá skutečnosti.
 * Připojit (i Moje síť) při miniserveru/síti s odmítnutým souhlasem tunel
 * nenechá vypnutý: hovory jedou vnitřním tunelem s výzvou „Zapnout přístup“.
 */
package org.linphone.twentyone.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.twentyone.car.CarCallLog
import org.linphone.twentyone.car.CarPhoneAccount
import org.linphone.twentyone.car.CarPreferences
import org.linphone.twentyone.car.CarSmsRole
import org.linphone.twentyone.TwentyOneBattery
import org.linphone.twentyone.contacts.TwentyOneContactsTarget
import org.linphone.ui.assistant.AssistantActivity

class TwentyOneTunnelFragment : Fragment() {
    companion object {
        private const val TAG = "[Tunnel Fragment]"

        // značky views vkládaných z kódu — podle nich se poznají už vložené
        private const val TAG_HINT = "twentyone_tunnel_hint"
        private const val TAG_LOGOUT = "twentyone_tunnel_logout"
        private const val TAG_WEB_LOGIN = "twentyone_tunnel_web_login"
        private const val TAG_SYSTEM_ACCESS = "twentyone_tunnel_system_access"

        // rozepsaný souhlas s VPN přes otočení obrazovky
        private const val STATE_PENDING = "twentyone_pending_consent"
        private const val STATE_PENDING_ACCESS = "twentyone_pending_access"
        private const val STATE_PENDING_FROM_SERVER = "twentyone_pending_from_server"
    }

    /** Na co se čeká v systémovém dialogu souhlasu s VPN. */
    private enum class Pending {
        /** Připojit */
        CONNECT,
        /** přihlášení přes Moji síť */
        WEB_LOGIN,
        /** „Zapnout přístup“: zvolený stupeň chce systémovou VPN */
        SYSTEM_ACCESS,
        /** změna stupně z „jen hovory“ na miniserver/síť */
        ACCESS,
        /** zapnutí synchronizace kontaktů přes tunel */
        SYNC
    }

    private lateinit var stateView: TextView
    private lateinit var routeView: TextView
    private lateinit var addressView: TextView
    private lateinit var scopeLabel: TextView
    // POZOR: v AppCompat/Material aktivitě se <Switch> v layoutu nafoukne jako
    // SwitchCompat, což NENÍ potomek android.widget.Switch — findViewById<Switch>
    // proto padalo na ClassCastException hned při otevření obrazovky
    private lateinit var accessServerSwitch: SwitchCompat
    private lateinit var accessInternetSwitch: SwitchCompat
    private lateinit var toggleButton: Button
    private lateinit var carLogSwitch: SwitchCompat
    private lateinit var carSmsButton: Button
    private lateinit var contactsLabel: TextView
    private lateinit var contactsHint: TextView
    private lateinit var batteryLabel: TextView
    private lateinit var batteryFix: Button

    // Nápověda pod stavem a náhradní volba „odhlásit“ vznikají z kódu:
    // layout obrazovky se v téhle vlně nemění. Reference se v onDestroyView
    // pouští, ať fragment nedrží zbořené view.
    private var hintView: TextView? = null
    private var logoutButton: Button? = null
    private var webLoginButton: Button? = null
    private var systemAccessButton: Button? = null
    private var syncSwitch: SwitchCompat? = null

    // souhlas s VPN, na který se čeká (systémový dialog vrací výsledek sem)
    private var pending: Pending? = null
    private var pendingAccess: TsPreferences.TunnelAccess? = null
    private var pendingFromServer = false

    /** Běží dotaz na vyšší stupeň (potvrzení nebo souhlas) — převzetí
     *  stupně z miniserveru se mezitím neptá podruhé. */
    private var askingAccess = false

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val what = pending ?: Pending.CONNECT
        val access = pendingAccess
        val fromServer = pendingFromServer
        pending = null
        pendingAccess = null
        pendingFromServer = false
        askingAccess = false
        val ok = result.resultCode == Activity.RESULT_OK
        when (what) {
            Pending.CONNECT, Pending.WEB_LOGIN -> if (ok) {
                TsManager.startService()
                if (what == Pending.WEB_LOGIN || needsNewCode() || TsManager.loggedIn.value != true) {
                    TsManager.login(TsManager.prefs.controlUrl, null)
                }
            } else {
                Log.w("$TAG VPN permission was refused")
                // Bez souhlasu tunel nezůstává vypnutý: hovory pojedou
                // vnitřním tunelem s výzvou „Zapnout přístup“ (jako u jiné
                // VPN nebo ztraceného místa). Místo pro VPN se nebere.
                org.linphone.twentyone.TwentyOneDiag.log(
                    "P21-TUN", "souhlas s VPN odmítnut — hovory pojedou vnitřním tunelem, přístup čeká na souhlas")
                TsManager.startService(userInitiated = false)
                TsModeController.onConsentMissing("souhlas s VPN odmítnut")
                if (what == Pending.WEB_LOGIN || needsNewCode() || TsManager.loggedIn.value != true) {
                    TsManager.login(TsManager.prefs.controlUrl, null)
                }
                // trvale zapnutá jiná VPN: systém souhlas zamítne sám — říct proč
                if (isAdded && !TsConsentActivity.explainRefusal(requireContext()) { }) {
                    toast(R.string.twentyone_system_access_refused)
                }
            }
            Pending.SYSTEM_ACCESS -> if (ok) {
                TsManager.startService()
            } else {
                org.linphone.twentyone.TwentyOneDiag.log(
                    "P21-TUN", "souhlas s VPN odmítnut — hovory jedou dál vnitřním tunelem")
                if (isAdded && !TsConsentActivity.explainRefusal(requireContext()) { }) {
                    toast(R.string.twentyone_system_access_refused)
                }
            }
            Pending.ACCESS -> if (ok && access != null) {
                commitAccess(access, fromServer)
            } else {
                refuseAccess(fromServer)
            }
            Pending.SYNC -> if (ok) commitSync() else refuseSync()
        }
    }

    private val writeCallLogPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            CarCallLog.setEnabled(requireContext(), true)
        } else {
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-CAR",
                "oprávnění k zápisu historie hovorů zamítnuto"
            )
            if (!shouldShowRequestPermissionRationale(
                    android.Manifest.permission.WRITE_CALL_LOG
                )
            ) {
                // trvale zamítnuto — dialog se už neukáže, zbývá nastavení aplikace
                org.linphone.twentyone.TwentyOneDiag.openAppSettings(requireContext())
            }
        }
        carLogSwitch.isChecked = CarCallLog.isActive(requireContext())
    }

    private val smsRoleRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-CAR",
            if (result.resultCode == Activity.RESULT_OK) "role SMS udělena" else "role SMS neudělena"
        )
        carSmsButton.setText(CarSmsRole.titleRes(requireContext()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Systémový dialog souhlasu přežije otočení obrazovky, fragment ne:
        // bez tohohle by výsledek nevěděl, ke které volbě patří.
        savedInstanceState?.let { st ->
            pending = st.getString(STATE_PENDING)?.let { n ->
                runCatching { Pending.valueOf(n) }.getOrNull()
            }
            pendingAccess = st.getString(STATE_PENDING_ACCESS)?.let { n ->
                runCatching { TsPreferences.TunnelAccess.valueOf(n) }.getOrNull()
            }
            pendingFromServer = st.getBoolean(STATE_PENDING_FROM_SERVER, false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pending?.let { outState.putString(STATE_PENDING, it.name) }
        pendingAccess?.let { outState.putString(STATE_PENDING_ACCESS, it.name) }
        outState.putBoolean(STATE_PENDING_FROM_SERVER, pendingFromServer)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.twentyone_tunnel_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        stateView = view.findViewById(R.id.tunnel_state)
        routeView = view.findViewById(R.id.tunnel_route)
        addressView = view.findViewById(R.id.tunnel_address)
        scopeLabel = view.findViewById(R.id.tunnel_scope_label)
        accessServerSwitch = view.findViewById(R.id.access_server_switch)
        accessInternetSwitch = view.findViewById(R.id.access_internet_switch)
        toggleButton = view.findViewById(R.id.tunnel_toggle)
        installExtraViews()

        TsManager.ensureStarted(requireContext().applicationContext)

        // dva stupně nad výchozím stavem; vyšší v sobě obsahuje nižší
        accessServerSwitch.setOnCheckedChangeListener { view2, checked ->
            if (!view2.isPressed) return@setOnCheckedChangeListener
            requestAccess(
                if (checked) {
                    TsPreferences.TunnelAccess.SERVER
                } else {
                    TsPreferences.TunnelAccess.APP_ONLY
                },
                fromServer = false
            )
        }
        accessInternetSwitch.setOnCheckedChangeListener { view2, checked ->
            if (!view2.isPressed) return@setOnCheckedChangeListener
            requestAccess(
                if (checked) {
                    TsPreferences.TunnelAccess.INTERNET
                } else {
                    TsPreferences.TunnelAccess.SERVER
                },
                fromServer = false
            )
        }

        // fork: kam se ukládají nové kontakty
        contactsLabel = view.findViewById(R.id.contacts_target_label)
        contactsHint = view.findViewById(R.id.contacts_target_hint)
        view.findViewById<Button>(R.id.contacts_target_change).setOnClickListener {
            showContactsTargetDialog()
        }
        updateContactsTarget()

        // fork: pustit do tunelu i synchronizaci kontaktů. Je to cizí appka,
        // takže „jen hovory“ se synchronizací jede systémovou VPN — zapnutí
        // se ptá na souhlas, vypnutí přepne zpátky na vnitřní tunel samo
        // (režim srovná posluchač předvoleb v TsModeController).
        val sync = view.findViewById<SwitchCompat>(R.id.contacts_sync_tunnel_switch)
        syncSwitch = sync
        // stav přepínače je vždy ten uložený: obnova po otočení by jinak
        // spustila posluchače a s ním i dotaz na souhlas znovu
        sync.isSaveEnabled = false
        sync.isChecked = TsManager.prefs.contactsSyncViaTunnel
        sync.setOnCheckedChangeListener { _, checked ->
            if (checked == TsManager.prefs.contactsSyncViaTunnel) return@setOnCheckedChangeListener
            if (checked && crossesToSystem(sync = true)) {
                requestSync()
            } else {
                TsManager.prefs.contactsSyncViaTunnel = checked
            }
        }
        updateAccessViews()

        // fork: běh na pozadí (výjimka z optimalizace baterie)
        batteryLabel = view.findViewById(R.id.battery_label)
        batteryFix = view.findViewById(R.id.battery_fix)
        batteryFix.setOnClickListener {
            try {
                startActivity(TwentyOneBattery.requestIntent(requireContext()))
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    requireContext(),
                    R.string.twentyone_battery_hint,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        updateBattery()

        carLogSwitch = view.findViewById(R.id.tunnel_car_log_switch)
        carLogSwitch.isChecked = CarCallLog.isActive(requireContext())
        carLogSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (CarCallLog.hasPermission(requireContext())) {
                    CarCallLog.setEnabled(requireContext(), true)
                } else if (carLogSwitch.isPressed) {
                    writeCallLogPermission.launch(
                        android.Manifest.permission.WRITE_CALL_LOG
                    )
                } else {
                    // obnova stavu view bez uživatele (otočení displeje) —
                    // bez oprávnění se dialog nesmí otevřít sám od sebe
                    carLogSwitch.isChecked = false
                }
            } else {
                CarCallLog.setEnabled(requireContext(), false)
            }
        }

        // fork: vytáčení z auta — účet pro volání se registruje/odebírá
        // podle přepínače; výchozí vypnuto
        val carPrefs = CarPreferences(requireContext())
        val carSwitch = view.findViewById<SwitchCompat>(R.id.car_dialing_switch)
        val carHint = view.findViewById<TextView>(R.id.car_dialing_hint)
        val carAccounts = view.findViewById<Button>(R.id.car_dialing_accounts)

        fun updateCarViews(enabled: Boolean) {
            carHint.visibility = if (enabled) View.VISIBLE else View.GONE
            carAccounts.visibility = if (enabled) View.VISIBLE else View.GONE
        }
        carSwitch.isChecked = carPrefs.carDialingEnabled
        updateCarViews(carPrefs.carDialingEnabled)

        carSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (CarPhoneAccount.register(requireContext())) {
                    carPrefs.carDialingEnabled = true
                } else {
                    // vrácení přepínače vyvolá listener znovu — větev
                    // níže je proto podmíněná uloženou hodnotou
                    carSwitch.isChecked = false
                    android.widget.Toast.makeText(
                        requireContext(),
                        R.string.twentyone_car_register_failed,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return@setOnCheckedChangeListener
                }
            } else if (carPrefs.carDialingEnabled) {
                carPrefs.carDialingEnabled = false
                CarPhoneAccount.unregister(requireContext())
            }
            updateCarViews(checked)
        }

        carAccounts.setOnClickListener {
            CarPhoneAccount.openCallingAccounts(requireContext())
        }

        // SMS do auta: žádost o roli výchozí SMS aplikace (P21-CAR)
        carSmsButton = view.findViewById(R.id.car_sms_role)
        carSmsButton.setText(CarSmsRole.titleRes(requireContext()))
        carSmsButton.setOnClickListener {
            val intent = CarSmsRole.requestIntent(requireContext())
            if (intent != null) {
                smsRoleRequest.launch(intent)
            }
        }

        view.findViewById<Button>(R.id.tunnel_diag).setOnClickListener {
            findNavController().navigate(
                R.id.action_twentyOneTunnelFragment_to_twentyOneDiagFragment
            )
        }

        toggleButton.setOnClickListener {
            when {
                // Telefon ze sítě vypadl: „Připojit" by jen nastartoval
                // službu a nic by se nestalo (příznak přihlášení zůstává
                // viset). Jediná cesta zpět je nový kód.
                needsNewCode() -> startNewCode()
                // cesta k miniserveru podle režimu (systémové rozhraní,
                // nebo vnitřní tunel s relé), viz offersDisconnect
                offersDisconnect() -> TsManager.stopService()
                else -> connect()
            }
        }

        TsManager.state.observe(viewLifecycleOwner) { updateState() }
        TsManager.vpnActive.observe(viewLifecycleOwner) { updateState() }
        TsManager.transportUp.observe(viewLifecycleOwner) { updateState() }
        TsManager.mode.observe(viewLifecycleOwner) { updateState() }
        TsModeController.systemBlock.observe(viewLifecycleOwner) { updateState() }
        TsManager.directConnection.observe(viewLifecycleOwner) { updateState() }
        TsManager.tailnetAddress.observe(viewLifecycleOwner) { updateState() }
        TsManager.networkPaused.observe(viewLifecycleOwner) { updateState() }
        TsManager.gatewayState.observe(viewLifecycleOwner) { updateState() }
        TsManager.slotLost.observe(viewLifecycleOwner) { updateState() }
        // Věta o provozu ven se opírá o poslední stav sítě (je uzel opravdu
        // nasazený?) — bez tohohle pozorovatele by zůstala viset na tom, co
        // platilo při otevření obrazovky.
        TsManager.netDiag.observe(viewLifecycleOwner) { updateState() }
        TsManager.browseToUrl.observe(viewLifecycleOwner) { url ->
            if (url.isNullOrEmpty()) return@observe
            // Hodnotu spotřebovat HNED: LiveData ji jinak přehraje při
            // každém dalším otevření obrazovky a pokus o otevření odkazu
            // by se opakoval donekonečna (přesně tak padala P21-S1).
            TsManager.browseToUrl.value = null
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (e: android.content.ActivityNotFoundException) {
                // telefon bez prohlížeče (na GrapheneOS jde vypnout) —
                // neodchycená výjimka tady zabíjela celou aplikaci
                // uprostřed registrace
                org.linphone.twentyone.TwentyOneDiag.log(
                    "P21-E14",
                    "není čím otevřít přihlašovací odkaz (telefon bez prohlížeče)"
                )
                showLoginUrlDialog(url)
            }
        }
        updateState()
    }

    /**
     * Nápověda pod stavem a tlačítko „odhlásit“. Zakládají se z kódu, aby
     * obrazovka nepotřebovala změnu layoutu; když by rodič nebyl sloupec,
     * prostě nevzniknou a zbytek obrazovky funguje dál.
     */
    private fun installExtraViews() {
        val column = stateView.parent as? LinearLayout ?: return
        val density = resources.displayMetrics.density

        // Pojistka proti dvojímu vložení: onCreateView vrací pokaždé čerstvě
        // nafouknutý strom, takže při běžném průchodu životním cyklem
        // duplikát vzniknout nemůže. Kdyby ale metoda někdy proběhla nad
        // TÝMŽ sloupcem podruhé, visely by na obrazovce dvě nápovědy a dvě
        // tlačítka „odhlásit" — a to druhé by nikdo neaktualizoval. Hotová
        // views se proto poznají podle značky a jen se převezmou.
        hintView = (column.findViewWithTag<View>(TAG_HINT) as? TextView)
            ?: TextView(requireContext()).apply {
                tag = TAG_HINT
                textSize = 14f
                setTextColor(routeView.currentTextColor)
                visibility = View.GONE
                column.addView(
                    this,
                    column.indexOfChild(stateView) + 1,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (4 * density).toInt() }
                )
            }

        // MaterialButton, ne obyčejný Button: v Material3 tématu se <Button>
        // z layoutu nafoukne právě jako MaterialButton a tenhle by jinak
        // vypadal jinak než ten vedle něj.
        logoutButton = (column.findViewWithTag<View>(TAG_LOGOUT) as? Button)
            ?: MaterialButton(requireContext()).apply {
                tag = TAG_LOGOUT
                setText(R.string.twentyone_tunnel_logout)
                visibility = View.GONE
                setOnClickListener {
                    org.linphone.twentyone.TwentyOneDiag.log(
                        "P21-TUN",
                        "odhlášení telefonu ze sítě na žádost uživatele"
                    )
                    TsManager.logout()
                }
                column.addView(
                    this,
                    column.indexOfChild(toggleButton) + 1,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (8 * density).toInt() }
                )
            }

        // Zvolený stupeň chce systémovou VPN, hovory zatím jedou vnitřním
        // tunelem: tady (a ve výstraze) se dá souhlas dát.
        systemAccessButton = (column.findViewWithTag<View>(TAG_SYSTEM_ACCESS) as? Button)
            ?: MaterialButton(requireContext()).apply {
                tag = TAG_SYSTEM_ACCESS
                setText(R.string.twentyone_system_access_blocked_action)
                visibility = View.GONE
                setOnClickListener { enableSystemAccess() }
                column.addView(
                    this,
                    column.indexOfChild(toggleButton) + 1,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (8 * density).toInt() }
                )
            }

        // přihlášení v prohlížeči jako druhá cesta do sítě vedle QR kódu
        webLoginButton = (column.findViewWithTag<View>(TAG_WEB_LOGIN) as? Button)
            ?: MaterialButton(requireContext()).apply {
                tag = TAG_WEB_LOGIN
                setText(R.string.twentyone_tunnel_web_login)
                visibility = View.GONE
                setOnClickListener { webLogin() }
                column.addView(
                    this,
                    column.indexOfChild(toggleButton) + 1,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (8 * density).toInt() }
                )
            }
    }

    override fun onDestroyView() {
        hintView = null
        logoutButton = null
        webLoginButton = null
        systemAccessButton = null
        syncSwitch = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        // oprávnění i role šly mezitím změnit v nastavení systému
        if (::carLogSwitch.isInitialized) {
            carLogSwitch.isChecked = CarCallLog.isActive(requireContext())
        }
        if (::carSmsButton.isInitialized) {
            carSmsButton.setText(CarSmsRole.titleRes(requireContext()))
        }
        if (::contactsLabel.isInitialized) {
            updateContactsTarget()
        }
        if (::batteryLabel.isInitialized) {
            updateBattery()
        }
        if (::accessServerSwitch.isInitialized) {
            pullAccess()
            // jiná VPN se mohla mezitím zapnout/vypnout (věta pod stavem)
            updateState()
        }
    }

    private fun updateBattery() {
        val ctx = requireContext()
        val free = TwentyOneBattery.isUnrestricted(ctx)
        batteryLabel.text = getString(
            R.string.twentyone_battery_label,
            getString(
                if (free) {
                    R.string.twentyone_battery_unrestricted
                } else {
                    R.string.twentyone_battery_restricted
                }
            )
        )
        batteryFix.visibility = if (free) View.GONE else View.VISIBLE
    }

    private fun updateContactsTarget() {
        val ctx = requireContext()
        contactsLabel.text = getString(
            R.string.twentyone_contacts_target_label,
            TwentyOneContactsTarget.describe(ctx)
        )
        val books = TwentyOneContactsTarget.availableBooks(ctx)
        val hint = when {
            TwentyOneContactsTarget.mode(ctx) == TwentyOneContactsTarget.Mode.BOOK &&
                TwentyOneContactsTarget.resolve(ctx) == null ->
                R.string.twentyone_contacts_hint_lost
            books.isEmpty() && TwentyOneContactsTarget.davAppInstalled(ctx) ->
                R.string.twentyone_contacts_hint_app_no_book
            books.isEmpty() -> R.string.twentyone_contacts_hint_none
            books.size > 1 && TwentyOneContactsTarget.mode(ctx) ==
                TwentyOneContactsTarget.Mode.AUTO ->
                R.string.twentyone_contacts_hint_multiple
            else -> 0
        }
        if (hint == 0) {
            contactsHint.visibility = View.GONE
        } else {
            contactsHint.visibility = View.VISIBLE
            contactsHint.setText(hint)
        }
    }

    private fun showContactsTargetDialog() {
        val ctx = requireContext()
        val books = TwentyOneContactsTarget.availableBooks(ctx)
        val labels = ArrayList<String>()
        labels.add(getString(R.string.twentyone_contacts_target_option_auto))
        labels.add(getString(R.string.twentyone_contacts_target_option_phone))
        books.forEach { labels.add(it.name) }
        val mode = TwentyOneContactsTarget.mode(ctx)
        val current = when (mode) {
            TwentyOneContactsTarget.Mode.AUTO -> 0
            TwentyOneContactsTarget.Mode.LOCAL -> 1
            TwentyOneContactsTarget.Mode.BOOK -> {
                val target = TwentyOneContactsTarget.resolve(ctx)
                val idx = books.indexOfFirst { it == target }
                if (idx >= 0) idx + 2 else 0
            }
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.twentyone_contacts_target_dialog_title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                when (which) {
                    0 -> TwentyOneContactsTarget.setAuto(ctx)
                    1 -> TwentyOneContactsTarget.setLocal(ctx)
                    else -> TwentyOneContactsTarget.setBook(ctx, books[which - 2])
                }
                updateContactsTarget()
                dialog.dismiss()
            }
            .show()
    }

    private fun showLoginUrlDialog(url: String) {
        val ctx = requireContext()
        android.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.twentyone_tunnel_login_url_title))
            .setMessage(getString(R.string.twentyone_tunnel_login_url_message, url))
            .setPositiveButton(R.string.twentyone_tunnel_login_url_copy) { _, _ ->
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("login", url)
                )
            }
            .setNegativeButton(R.string.twentyone_tunnel_login_url_close, null)
            .show()
    }

    /**
     * Odříznutí (síť běží, ale nemá jediné zařízení) platí JEN pro běžící
     * síť. Příznak v TsManager se přepočítává výhradně z doručeného stavu
     * sítě a ty se mimo RUNNING netahají, takže umí zůstat viset na true
     * i po „Odpojit" nebo po vyhození telefonu ze sítě. Obrazovka se ho
     * proto ptá jen ve stavu RUNNING — jinak by na odpojeném telefonu
     * svítilo „Pozastaveno" a věta o chybějícím kreditu, a hlavní tlačítko
     * by místo nabídky nového kódu zůstalo tichým „Připojit".
     */
    private fun isPaused(state: TsManager.State?): Boolean =
        state == TsManager.State.RUNNING && TsManager.networkPaused.value == true

    /**
     * Telefon do sítě nepatří (koordinátor ho zná jako odebraný). Není to
     * odříznutí: odříznutá síť běží dál a kód by pro ni byl k ničemu.
     */
    private fun needsNewCode(): Boolean {
        val state = TsManager.state.value
        // Pozastavení může přebít nabídku nového kódu jen tehdy, když síť
        // opravdu běží; zaseknutý příznak mimo RUNNING nesmí telefon
        // vyhozený ze sítě připravit o jedinou cestu zpátky.
        if (isPaused(state)) return false
        return state == TsManager.State.NEEDS_LOGIN ||
            state == TsManager.State.NEEDS_MACHINE_AUTH
    }

    /** Jediná cesta zpět do sítě: přečíst nový kód v průvodci. */
    private fun startNewCode() {
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-TUN", "telefon do sítě nepatří — otevírám čtení nového kódu"
        )
        startActivity(Intent(requireContext(), AssistantActivity::class.java))
    }

    /**
     * VpnService.prepare() vezme místo pro VPN a VPN jiné aplikace tím vypne
     * (Android dovolí jen jednu). Běží-li nějaká (nebo Phone21 o místo
     * přišlo), nejdřív se zeptat — viz TsConsentActivity.takeoverConfirmTexts.
     * Detekce jiné VPN slouží jen k tomuhle dotazu, nikdy jako zábrana.
     * [onCancel] = uživatel nepotvrdil (zrušit, zpět, klepnutí vedle).
     */
    private fun confirmOtherVpn(onCancel: () -> Unit = {}, action: () -> Unit) {
        val ctx = requireContext()
        val texts = TsConsentActivity.takeoverConfirmTexts(ctx)
        if (texts == null) {
            action()
            return
        }
        var proceed = false
        android.app.AlertDialog.Builder(ctx)
            .setTitle(texts.first)
            .setMessage(texts.second)
            .setPositiveButton(R.string.twentyone_slot_lost_action) { _, _ ->
                proceed = true
            }
            .setNegativeButton(R.string.twentyone_other_vpn_confirm_cancel, null)
            .setOnDismissListener {
                if (!isAdded) return@setOnDismissListener
                if (proceed) {
                    org.linphone.twentyone.TwentyOneDiag.log(
                        "P21-TUN", "uživatel potvrdil převzetí místa pro VPN")
                    action()
                } else {
                    onCancel()
                }
            }
            .show()
    }

    /** Stupeň „jen hovory“ bez synchronizace = vnitřní tunel, bez VPN. */
    private fun internalWanted(): Boolean =
        TsModeController.desiredAccessMode(requireContext()) == TsModeController.Mode.INTERNAL

    /** Přihlášení přes Moji síť: odkaz se otevře v prohlížeči (browseToUrl). */
    private fun webLogin() {
        if (internalWanted()) {
            // vnitřní tunel: žádný souhlas s VPN, jiná VPN běží dál
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-TUN", "telefon mimo síť — přihlášení přes Moji síť v prohlížeči (jen hovory, bez VPN)"
            )
            TsManager.startService(userInitiated = false)
            TsManager.login(TsManager.prefs.controlUrl, null)
            return
        }
        confirmOtherVpn { doWebLogin() }
    }

    private fun doWebLogin() {
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-TUN", "telefon mimo síť — přihlášení přes Moji síť v prohlížeči"
        )
        val intent = VpnService.prepare(requireContext())
        if (intent != null) {
            pending = Pending.WEB_LOGIN
            vpnPermission.launch(intent)
            return
        }
        TsManager.startService()
        TsManager.login(TsManager.prefs.controlUrl, null)
    }

    private fun connect() {
        if (needsNewCode()) {
            startNewCode()
            return
        }
        if (internalWanted()) {
            // vnitřní tunel: Připojit bez dialogu VPN a bez prepare()
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-TUN", "připojení na pokyn uživatele — jen hovory, bez VPN")
            TsManager.startService(userInitiated = false)
            if (TsManager.loggedIn.value != true) {
                TsManager.login(TsManager.prefs.controlUrl, null)
            }
            return
        }
        // Ztracené místo pro VPN se tu NEruší: zruší ho až služba, když
        // rozhraní opravdu stojí.
        confirmOtherVpn { doConnect() }
    }

    private fun doConnect() {
        val intent = VpnService.prepare(requireContext())
        if (intent != null) {
            pending = Pending.CONNECT
            vpnPermission.launch(intent)
        } else {
            TsManager.startService()
            if (TsManager.loggedIn.value != true) {
                TsManager.login(TsManager.prefs.controlUrl, null)
            }
        }
    }

    /** „Zapnout přístup“: zvolený stupeň chce systémovou VPN, hovory zatím
     *  jedou vnitřním tunelem. Stejně jako výstraha: potvrzení a souhlas. */
    private fun enableSystemAccess() {
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-TUN", "uživatel chce zapnout přístup přes systémovou VPN (Konexe)")
        confirmOtherVpn {
            val intent = VpnService.prepare(requireContext())
            if (intent != null) {
                pending = Pending.SYSTEM_ACCESS
                vpnPermission.launch(intent)
            } else {
                TsManager.startService()
            }
        }
    }

    /**
     * Přejde změna z vnitřního tunelu na systémovou VPN? Jen když uživatel
     * tunel chce — vypnutý tunel se souhlasem zeptá až při Připojit.
     */
    private fun crossesToSystem(
        access: TsPreferences.TunnelAccess? = null,
        sync: Boolean? = null
    ): Boolean {
        if (!TsManager.wantsRunning()) return false
        val ctx = requireContext()
        val p = TsManager.prefs
        val a = access ?: p.tunnelAccess
        val s = (sync ?: p.contactsSyncViaTunnel) && TwentyOneContactsTarget.davAppInstalled(ctx)
        val next = if (a == TsPreferences.TunnelAccess.APP_ONLY && !s) {
            TsModeController.Mode.INTERNAL
        } else {
            TsModeController.Mode.SYSTEM
        }
        return next == TsModeController.Mode.SYSTEM &&
            TsModeController.desiredAccessMode(ctx) == TsModeController.Mode.INTERNAL
    }

    /**
     * Změna stupně přístupu (přepínač, nebo převzetí z miniserveru). Z „jen
     * hovory“ na miniserver/síť jen se souhlasem s VPN (a potvrzením, když
     * běží jiná VPN); zpátky na „jen hovory“ bez ptaní.
     */
    private fun requestAccess(access: TsPreferences.TunnelAccess, fromServer: Boolean) {
        if (!crossesToSystem(access = access)) {
            applyAccess(access, push = !fromServer)
            if (fromServer) toast(R.string.twentyone_access_sync_pulled, Toast.LENGTH_SHORT)
            return
        }
        askingAccess = true
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-TUN",
            "stupeň %s (%s) chce systémovou VPN — ptám se na souhlas".format(
                access, if (fromServer) "z miniserveru" else "volba uživatele"))
        confirmOtherVpn(onCancel = { refuseAccess(fromServer) }) {
            val intent = VpnService.prepare(requireContext())
            if (intent != null) {
                pending = Pending.ACCESS
                pendingAccess = access
                pendingFromServer = fromServer
                vpnPermission.launch(intent)
            } else {
                commitAccess(access, fromServer)
            }
        }
    }

    /** Souhlas je: stupeň platí a systémové místo se smí vzít. */
    private fun commitAccess(access: TsPreferences.TunnelAccess, fromServer: Boolean) {
        askingAccess = false
        // NAPŘED: srovnání režimu (posluchač předvoleb) jinak místo nevezme
        TsModeController.approveSystem()
        applyAccess(access, push = !fromServer)
        if (fromServer && isAdded) toast(R.string.twentyone_access_sync_pulled, Toast.LENGTH_SHORT)
    }

    /** Bez souhlasu: zůstává „jen hovory“; převzatý stupeň se na
     *  miniserveru vrátí, ať sdílená volba odpovídá skutečnosti. */
    private fun refuseAccess(fromServer: Boolean) {
        askingAccess = false
        val kept = TsManager.prefs.tunnelAccess
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-TUN",
            "souhlas se systémovou VPN nedán — stupeň zůstává %s%s".format(
                kept, if (fromServer) ", zapisuji ho zpátky na miniserver" else ""))
        if (fromServer) pushAccess(kept)
        if (!isAdded) return
        updateAccessViews()
        toast(R.string.twentyone_access_refused)
    }

    private fun requestSync() {
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-TUN", "synchronizace kontaktů přes tunel chce systémovou VPN — ptám se na souhlas")
        confirmOtherVpn(onCancel = { refuseSync() }) {
            val intent = VpnService.prepare(requireContext())
            if (intent != null) {
                pending = Pending.SYNC
                vpnPermission.launch(intent)
            } else {
                commitSync()
            }
        }
    }

    private fun commitSync() {
        // NAPŘED: srovnání režimu (posluchač předvoleb) jinak místo nevezme
        TsModeController.approveSystem()
        TsManager.prefs.contactsSyncViaTunnel = true
        syncSwitch?.isChecked = true
    }

    private fun refuseSync() {
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-TUN", "souhlas se systémovou VPN nedán — synchronizace kontaktů zůstává mimo tunel")
        if (!isAdded) return
        syncSwitch?.isChecked = false
        toast(R.string.twentyone_access_refused)
    }

    private fun toast(res: Int, length: Int = Toast.LENGTH_LONG) {
        if (!isAdded) return
        Toast.makeText(requireContext(), res, length).show()
    }

    private fun updateState() {
        val state = TsManager.state.value ?: TsManager.State.NO_STATE
        // Odříznutí NENÍ odhlášení: nesmí tvrdit, že telefon do sítě
        // nepatří, ani nabízet nový kód. A vyhodnocuje se jen v běžící
        // síti (viz isPaused) — mimo RUNNING je příznak nevěrohodný.
        val paused = isPaused(state)
        val needsCode = needsNewCode()
        val mode = TsManager.mode.value ?: TsModeController.Mode.OFF
        val transport = TsManager.transportUp.value == true
        // Systémový režim: jádro může běžet i bez rozhraní (místo pro VPN
        // drží jiná aplikace) — pak „Připojeno“ ani trasa neplatí, hovory
        // nechodí. Vnitřní tunel místo pro VPN nepotřebuje.
        val noSlot = mode == TsModeController.Mode.SYSTEM &&
            TsManager.slotLost.value == true && TsManager.vpnActive.value != true

        stateView.text = getString(
            when {
                paused -> R.string.twentyone_tunnel_state_paused
                noSlot && !needsCode -> R.string.twentyone_tunnel_state_stopped
                // uzel je v síti, ale cesta k miniserveru (rozhraní / relé)
                // ještě nestojí — hovory zatím nejdou
                state == TsManager.State.RUNNING && !transport && TsManager.wantsRunning() ->
                    R.string.twentyone_tunnel_state_connecting
                state == TsManager.State.RUNNING -> R.string.twentyone_tunnel_state_connected
                state == TsManager.State.STARTING -> R.string.twentyone_tunnel_state_connecting
                needsCode -> R.string.twentyone_tunnel_state_needs_login
                else -> R.string.twentyone_tunnel_state_stopped
            }
        )

        // Trasa se měří k bráně; při odříznutí není ke komu a věta o přímém
        // spojení by lhala, proto řádek zmizí celý.
        routeView.visibility = if (state == TsManager.State.RUNNING && !paused && !noSlot) {
            View.VISIBLE
        } else {
            View.GONE
        }
        routeView.text = getString(
            if (TsManager.directConnection.value == true) {
                R.string.twentyone_tunnel_route_direct
            } else {
                R.string.twentyone_tunnel_route_relay
            }
        )

        val hint = hintFor(state, paused, needsCode)
        hintView?.visibility = if (hint == null) View.GONE else View.VISIBLE
        if (hint != null) hintView?.text = hint

        // Odhlášení dává smysl jen tomu, kdo ze sítě vypadl; při odříznutí by
        // to byla past (telefon by se ze sítě odhlásil kvůli chybějícímu
        // kreditu a zpátky by ho pustil až nový kód).
        logoutButton?.visibility = if (needsCode) View.VISIBLE else View.GONE
        webLoginButton?.visibility = if (needsCode) View.VISIBLE else View.GONE
        systemAccessButton?.visibility = if (systemBlocked() && !needsCode) View.VISIBLE else View.GONE

        val address = TsManager.tailnetAddress.value
        addressView.visibility = if (address.isNullOrEmpty() || noSlot) View.GONE else View.VISIBLE
        if (!address.isNullOrEmpty()) {
            addressView.text = getString(R.string.twentyone_tunnel_address, address)
        }

        toggleButton.setText(
            when {
                needsCode -> R.string.twentyone_tunnel_needs_login_action
                offersDisconnect() -> R.string.twentyone_tunnel_disconnect
                else -> R.string.twentyone_tunnel_connect
            }
        )
        updateAccessViews()
    }

    /**
     * Nabídnout Odpojit? Když cesta stojí — a ve vnitřním režimu vždy, když
     * uživatel tunel chce: relé může stát (čeká na cíle, chyba identity)
     * a „Připojit“ by nepřidalo nic, co srovnání nedělá samo. Bez toho by
     * tunel nešlo vypnout.
     */
    private fun offersDisconnect(): Boolean =
        TsManager.transportUp.value == true ||
            (TsManager.wantsRunning() && TsManager.mode.value == TsModeController.Mode.INTERNAL)

    /** Zvolený stupeň chce systémovou VPN, ale hovory jedou vnitřním
     *  tunelem (chybí souhlas, místo drží jiná VPN, nebo o něj Phone21
     *  přišlo). Jen když uživatel tunel chce. */
    private fun systemBlocked(): Boolean =
        TsManager.wantsRunning() &&
            (TsModeController.systemBlock.value ?: TsModeController.SystemBlock.NONE) !=
            TsModeController.SystemBlock.NONE

    /**
     * Věta pod stavem. Pořadí: odříznutí → telefon mimo síť → zvolený
     * stupeň čeká na systémovou VPN (hovory jedou vnitřním tunelem) →
     * ztracené místo pro VPN (jen bez vnitřního tunelu) → co appka ví
     * o bráně (to zajímá jen toho, kdo chce provoz ven přes miniserver).
     */
    private fun hintFor(
        state: TsManager.State,
        paused: Boolean,
        needsCode: Boolean
    ): String? {
        if (paused) {
            val base = getString(R.string.twentyone_tunnel_paused_hint)
            return if (TsManager.prefs.tunnelAccess == TsPreferences.TunnelAccess.INTERNET) {
                base + "\n" + getString(R.string.twentyone_tunnel_paused_internet)
            } else {
                base
            }
        }
        if (needsCode) {
            return getString(R.string.twentyone_tunnel_needs_login_hint) + "\n" +
                getString(R.string.twentyone_tunnel_needs_login_no_code)
        }
        if (systemBlocked()) {
            return getString(
                when (TsModeController.systemBlock.value) {
                    TsModeController.SystemBlock.OTHER_VPN ->
                        R.string.twentyone_system_access_blocked_other_vpn
                    TsModeController.SystemBlock.SLOT_LOST ->
                        R.string.twentyone_system_access_blocked_slot_lost
                    else -> R.string.twentyone_system_access_blocked_no_consent
                }
            )
        }
        // „Hovory nechodí“ jen bez vnitřního tunelu (ten místo nepotřebuje)
        if (TsManager.slotLost.value == true && TsManager.mode.value != TsModeController.Mode.INTERNAL) {
            return getString(
                if (TsManager.otherVpnActive(requireContext())) {
                    R.string.twentyone_slot_lost_hint_other_vpn
                } else {
                    R.string.twentyone_slot_lost_hint
                }
            )
        }
        // provoz ven přes bránu jde jen systémovou VPN
        if (state != TsManager.State.RUNNING ||
            TsManager.prefs.tunnelAccess != TsPreferences.TunnelAccess.INTERNET ||
            TsManager.mode.value != TsModeController.Mode.SYSTEM
        ) {
            return null
        }
        return when (TsManager.gatewayState.value) {
            TsManager.GatewayState.ABSENT ->
                getString(R.string.twentyone_tunnel_gateway_absent)
            TsManager.GatewayState.NO_EXIT_OPTION ->
                getString(R.string.twentyone_tunnel_gateway_no_exit)
            TsManager.GatewayState.AMBIGUOUS ->
                getString(R.string.twentyone_tunnel_gateway_ambiguous)
            // „Provoz ven vede přes miniserver" jen tehdy, když opravdu vede:
            // přes přenosový uzel se stupeň snižuje (uzel nasazený není) a
            // samotné READY je jen to, co by ŠLO — ne to, co je nastavené.
            TsManager.GatewayState.READY ->
                if (TsManager.effectiveTunnelAccess() ==
                    TsPreferences.TunnelAccess.INTERNET &&
                    exitNodeInUse()
                ) {
                    getString(R.string.twentyone_tunnel_gateway_ready)
                } else {
                    null
                }
            null -> null
        }
    }

    /**
     * Vede provoz ven opravdu přes bránu? Bere se ze stavu sítě, ne z přání:
     * protějšek nese příznak výstupního uzlu jen tehdy, když jeho stabilní
     * identifikátor opravdu leží v předvolbách jádra. Dokud se nasazení
     * nepotvrdí (zápis neprošel, brána v tu chvíli cestu ven nenabízela,
     * srovnání se ještě nestihlo), obrazovka radši mlčí, než aby slibovala
     * něco, co neplatí. Vlastní opatrná věta pro „brána je, ale uzel zatím
     * nasazený není" by chtěla nový text v strings.xml — ten v téhle vlně
     * přibýt nemůže, proto se zatím nepíše nic.
     */
    private fun exitNodeInUse(): Boolean =
        TsManager.gatewayPeer(TsManager.netDiag.value)?.isExitNode == true

    /** Stupeň přístupu v telefonu; push = zapsat ho i na miniserver. */
    private fun applyAccess(access: TsPreferences.TunnelAccess, push: Boolean = true) {
        TsManager.prefs.tunnelAccess = access
        // Uzel se nasazuje podle stupně, který OPRAVDU platí (přes
        // přenosový uzel se nejvyšší stupeň snižuje) — a až PO uložení
        // volby, aby effectiveTunnelAccess() četla novou hodnotu.
        // Dřív se zapínal natvrdo podle přání a přes relay tak vznikl
        // výstupní uzel, o kterém zbytek appky nevěděl.
        TsManager.applyExitNode(
            TsManager.effectiveTunnelAccess() == TsPreferences.TunnelAccess.INTERNET
        )
        // Věta pod stavem se řídí SKUTEČNOSTÍ (je uzel nasazený?), a ta
        // se dozví až z dalšího stavu sítě. Bez tohohle dotazu by se
        // potvrzení objevilo až s dalším pollem. Dotaz jde na stejný
        // jednovláknový worker jako PATCH výše, takže se provede až po něm.
        TsManager.refreshStatus()
        updateAccessViews()
        // Přechod mezi vnitřním tunelem a systémovou VPN srovná posluchač
        // předvoleb (TsModeController); přestavba systémového rozhraní
        // (miniserver ↔ síť) jen tehdy, když systémová VPN zůstává.
        if (TsManager.vpnActive.value == true) {
            TsManager.restartService()
        }
        if (push) pushAccess(access)
    }

    /** Zapsat stupeň na miniserver (sdílená volba). */
    private fun pushAccess(access: TsPreferences.TunnelAccess) {
        TsServerAccess.push(TsManager.prefs, access) { result ->
            val msg = when (result) {
                TsServerAccess.Result.OK -> null
                TsServerAccess.Result.NO_TOKEN, TsServerAccess.Result.NO_GATEWAY ->
                    R.string.twentyone_access_sync_no_token
                TsServerAccess.Result.UNAUTHORIZED -> R.string.twentyone_access_sync_unauthorized
                TsServerAccess.Result.FAILED -> R.string.twentyone_access_sync_failed
            }
            if (msg != null && isAdded) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Volba změněná v ovládání miniserveru se při návratu na obrazovku
     * převezme. Stupeň, který potřebuje systémovou VPN, se místo nevezme
     * potichu: zeptá se (requestAccess) a odmítnutí zapíše zpátky.
     */
    private fun pullAccess() {
        if (askingAccess || pending != null) return
        TsServerAccess.pull(TsManager.prefs) { result, level ->
            if (result != TsServerAccess.Result.OK || level == null || !isAdded) return@pull
            if (askingAccess || pending != null) return@pull
            if (level != TsManager.prefs.tunnelAccess) {
                requestAccess(level, fromServer = true)
            }
        }
    }

    private fun updateAccessViews() {
        val wanted = TsManager.prefs.tunnelAccess
        val effective = TsManager.effectiveTunnelAccess()
        accessServerSwitch.isChecked = wanted != TsPreferences.TunnelAccess.APP_ONLY
        accessInternetSwitch.isChecked = wanted == TsPreferences.TunnelAccess.INTERNET
        accessInternetSwitch.isEnabled = wanted != TsPreferences.TunnelAccess.APP_ONLY
        // synchronizace kontaktů jen u „jen hovory“ (vyšší stupně pouštějí
        // do sítě všechny appky) a jen s nainstalovanou synchronizační appkou
        val syncVisible = wanted == TsPreferences.TunnelAccess.APP_ONLY &&
            TwentyOneContactsTarget.davAppInstalled(requireContext())
        syncSwitch?.let { sw ->
            sw.visibility = if (syncVisible) View.VISIBLE else View.GONE
            if (sw.isChecked != TsManager.prefs.contactsSyncViaTunnel) {
                sw.isChecked = TsManager.prefs.contactsSyncViaTunnel
            }
        }
        view?.findViewById<TextView>(R.id.contacts_sync_tunnel_hint)?.visibility =
            if (syncVisible) View.VISIBLE else View.GONE
        scopeLabel.text = getString(
            when {
                wanted == TsPreferences.TunnelAccess.INTERNET &&
                    effective != TsPreferences.TunnelAccess.INTERNET ->
                    R.string.twentyone_access_internet_paused
                wanted == TsPreferences.TunnelAccess.INTERNET ->
                    R.string.twentyone_access_internet
                wanted == TsPreferences.TunnelAccess.SERVER ->
                    R.string.twentyone_access_server
                // „jen hovory“ tunelem uvnitř appky — systémová VPN se nebere
                TsManager.mode.value == TsModeController.Mode.INTERNAL &&
                    !systemBlocked() ->
                    R.string.twentyone_tunnel_scope_app_internal
                else -> R.string.twentyone_tunnel_scope_app
            }
        )
    }
}
