/*
 * fork: obrazovka stavu tunelu. Odsud se také vyvolá systémový dialog
 * povolení VPN (VpnService.prepare musí přijít z aktivity).
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

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            TsManager.startService()
            if (TsManager.loggedIn.value != true) {
                TsManager.login(TsManager.prefs.controlUrl, null)
            }
        } else {
            Log.w("$TAG VPN permission was refused")
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
        fun applyAccess(access: TsPreferences.TunnelAccess) {
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
            if (TsManager.vpnActive.value == true) {
                TsManager.restartService()
            }
        }
        accessServerSwitch.setOnCheckedChangeListener { view2, checked ->
            if (!view2.isPressed) return@setOnCheckedChangeListener
            applyAccess(
                if (checked) {
                    TsPreferences.TunnelAccess.SERVER
                } else {
                    TsPreferences.TunnelAccess.APP_ONLY
                }
            )
        }
        accessInternetSwitch.setOnCheckedChangeListener { view2, checked ->
            if (!view2.isPressed) return@setOnCheckedChangeListener
            applyAccess(
                if (checked) {
                    TsPreferences.TunnelAccess.INTERNET
                } else {
                    TsPreferences.TunnelAccess.SERVER
                }
            )
        }
        updateAccessViews()

        // fork: kam se ukládají nové kontakty
        contactsLabel = view.findViewById(R.id.contacts_target_label)
        contactsHint = view.findViewById(R.id.contacts_target_hint)
        view.findViewById<Button>(R.id.contacts_target_change).setOnClickListener {
            showContactsTargetDialog()
        }
        updateContactsTarget()

        // fork: pustit do tunelu i synchronizaci kontaktů
        val syncSwitch = view.findViewById<SwitchCompat>(R.id.contacts_sync_tunnel_switch)
        val syncHint = view.findViewById<TextView>(R.id.contacts_sync_tunnel_hint)
        // při otevřeném tunelu pro všechny aplikace je volba zbytečná
        val syncVisible = TwentyOneContactsTarget.davAppInstalled(requireContext()) &&
            TsManager.prefs.tunnelAccess == TsPreferences.TunnelAccess.APP_ONLY
        syncSwitch.visibility = if (syncVisible) View.VISIBLE else View.GONE
        syncHint.visibility = if (syncVisible) View.VISIBLE else View.GONE
        syncSwitch.isChecked = TsManager.prefs.contactsSyncViaTunnel
        syncSwitch.setOnCheckedChangeListener { _, checked ->
            TsManager.prefs.contactsSyncViaTunnel = checked
            if (TsManager.vpnActive.value == true) {
                TsManager.restartService()
            }
        }

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
                TsManager.vpnActive.value == true -> TsManager.stopService()
                else -> connect()
            }
        }

        TsManager.state.observe(viewLifecycleOwner) { updateState() }
        TsManager.vpnActive.observe(viewLifecycleOwner) { updateState() }
        TsManager.directConnection.observe(viewLifecycleOwner) { updateState() }
        TsManager.tailnetAddress.observe(viewLifecycleOwner) { updateState() }
        TsManager.networkPaused.observe(viewLifecycleOwner) { updateState() }
        TsManager.gatewayState.observe(viewLifecycleOwner) { updateState() }
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
    }

    override fun onDestroyView() {
        hintView = null
        logoutButton = null
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

    private fun connect() {
        if (needsNewCode()) {
            startNewCode()
            return
        }
        val intent = VpnService.prepare(requireContext())
        if (intent != null) {
            vpnPermission.launch(intent)
        } else {
            TsManager.startService()
            if (TsManager.loggedIn.value != true) {
                TsManager.login(TsManager.prefs.controlUrl, null)
            }
        }
    }

    private fun updateState() {
        val state = TsManager.state.value ?: TsManager.State.NO_STATE
        // Odříznutí NENÍ odhlášení: nesmí tvrdit, že telefon do sítě
        // nepatří, ani nabízet nový kód. A vyhodnocuje se jen v běžící
        // síti (viz isPaused) — mimo RUNNING je příznak nevěrohodný.
        val paused = isPaused(state)
        val needsCode = needsNewCode()

        stateView.text = getString(
            when {
                paused -> R.string.twentyone_tunnel_state_paused
                state == TsManager.State.RUNNING -> R.string.twentyone_tunnel_state_connected
                state == TsManager.State.STARTING -> R.string.twentyone_tunnel_state_connecting
                needsCode -> R.string.twentyone_tunnel_state_needs_login
                else -> R.string.twentyone_tunnel_state_stopped
            }
        )

        // Trasa se měří k bráně; při odříznutí není ke komu a věta o přímém
        // spojení by lhala, proto řádek zmizí celý.
        routeView.visibility = if (state == TsManager.State.RUNNING && !paused) {
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

        val address = TsManager.tailnetAddress.value
        addressView.visibility = if (address.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (!address.isNullOrEmpty()) {
            addressView.text = getString(R.string.twentyone_tunnel_address, address)
        }

        toggleButton.setText(
            when {
                needsCode -> R.string.twentyone_tunnel_needs_login_action
                TsManager.vpnActive.value == true -> R.string.twentyone_tunnel_disconnect
                else -> R.string.twentyone_tunnel_connect
            }
        )
        updateAccessViews()
    }

    /**
     * Věta pod stavem. Pořadí: odříznutí → telefon mimo síť → co appka ví
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
        if (state != TsManager.State.RUNNING ||
            TsManager.prefs.tunnelAccess != TsPreferences.TunnelAccess.INTERNET
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

    private fun updateAccessViews() {
        val wanted = TsManager.prefs.tunnelAccess
        val effective = TsManager.effectiveTunnelAccess()
        accessServerSwitch.isChecked = wanted != TsPreferences.TunnelAccess.APP_ONLY
        accessInternetSwitch.isChecked = wanted == TsPreferences.TunnelAccess.INTERNET
        accessInternetSwitch.isEnabled = wanted != TsPreferences.TunnelAccess.APP_ONLY
        scopeLabel.text = getString(
            when {
                wanted == TsPreferences.TunnelAccess.INTERNET &&
                    effective != TsPreferences.TunnelAccess.INTERNET ->
                    R.string.twentyone_access_internet_paused
                wanted == TsPreferences.TunnelAccess.INTERNET ->
                    R.string.twentyone_access_internet
                wanted == TsPreferences.TunnelAccess.SERVER ->
                    R.string.twentyone_access_server
                else -> R.string.twentyone_tunnel_scope_app
            }
        )
    }
}
