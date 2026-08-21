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
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.twentyone.car.CarCallLog
import org.linphone.twentyone.car.CarPhoneAccount
import org.linphone.twentyone.car.CarPreferences
import org.linphone.twentyone.car.CarSmsRole
import org.linphone.twentyone.TwentyOneBattery
import org.linphone.twentyone.contacts.TwentyOneContactsTarget

class TwentyOneTunnelFragment : Fragment() {
    companion object {
        private const val TAG = "[Tunnel Fragment]"
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

        TsManager.ensureStarted(requireContext().applicationContext)

        // dva stupně nad výchozím stavem; vyšší v sobě obsahuje nižší
        fun applyAccess(access: TsPreferences.TunnelAccess) {
            TsManager.prefs.tunnelAccess = access
            TsManager.applyExitNode(access == TsPreferences.TunnelAccess.INTERNET)
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
            if (TsManager.vpnActive.value == true) {
                TsManager.stopService()
            } else {
                connect()
            }
        }

        TsManager.state.observe(viewLifecycleOwner) { updateState() }
        TsManager.vpnActive.observe(viewLifecycleOwner) { updateState() }
        TsManager.directConnection.observe(viewLifecycleOwner) { updateState() }
        TsManager.tailnetAddress.observe(viewLifecycleOwner) { updateState() }
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

    private fun connect() {
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
        stateView.text = getString(
            when (state) {
                TsManager.State.RUNNING -> R.string.twentyone_tunnel_state_connected
                TsManager.State.STARTING -> R.string.twentyone_tunnel_state_connecting
                TsManager.State.NEEDS_LOGIN,
                TsManager.State.NEEDS_MACHINE_AUTH -> R.string.twentyone_tunnel_state_needs_login
                else -> R.string.twentyone_tunnel_state_stopped
            }
        )

        routeView.visibility = if (state == TsManager.State.RUNNING) View.VISIBLE else View.GONE
        routeView.text = getString(
            if (TsManager.directConnection.value == true) {
                R.string.twentyone_tunnel_route_direct
            } else {
                R.string.twentyone_tunnel_route_relay
            }
        )

        val address = TsManager.tailnetAddress.value
        addressView.visibility = if (address.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (!address.isNullOrEmpty()) {
            addressView.text = getString(R.string.twentyone_tunnel_address, address)
        }

        toggleButton.setText(
            if (TsManager.vpnActive.value == true) {
                R.string.twentyone_tunnel_disconnect
            } else {
                R.string.twentyone_tunnel_connect
            }
        )
        updateAccessViews()
    }

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
