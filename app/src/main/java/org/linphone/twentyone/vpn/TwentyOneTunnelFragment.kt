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
    private lateinit var scopeSwitch: SwitchCompat
    private lateinit var toggleButton: Button

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
        scopeSwitch = view.findViewById(R.id.tunnel_scope_switch)
        toggleButton = view.findViewById(R.id.tunnel_toggle)

        TsManager.ensureStarted(requireContext().applicationContext)

        scopeSwitch.isChecked = TsManager.prefs.tunnelScope == TsPreferences.TunnelScope.FULL
        scopeSwitch.setOnCheckedChangeListener { _, checked ->
            TsManager.prefs.tunnelScope = if (checked) {
                TsPreferences.TunnelScope.FULL
            } else {
                TsPreferences.TunnelScope.APP_ONLY
            }
            updateScopeLabel()
            if (TsManager.vpnActive.value == true) {
                TsManager.restartService()
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
        updateScopeLabel()
    }

    private fun updateScopeLabel() {
        val wanted = TsManager.prefs.tunnelScope
        val effective = TsManager.effectiveTunnelScope()
        scopeLabel.text = getString(
            when {
                wanted == TsPreferences.TunnelScope.FULL &&
                    effective == TsPreferences.TunnelScope.APP_ONLY ->
                    R.string.twentyone_tunnel_scope_full_paused
                wanted == TsPreferences.TunnelScope.FULL -> R.string.twentyone_tunnel_scope_full
                else -> R.string.twentyone_tunnel_scope_app
            }
        )
    }
}
