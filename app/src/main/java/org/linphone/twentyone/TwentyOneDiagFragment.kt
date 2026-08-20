/*
 * fork: obrazovka deníku chyb (P21-S2). Chybová hláška na obrazovce zmizí —
 * tady se dá dohledat zpětně, s kódem, časem a detaily, a jedním klepnutím
 * zkopírovat pro nahlášení.
 */
package org.linphone.twentyone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.linphone.R

class TwentyOneDiagFragment : Fragment() {

    private lateinit var logView: TextView
    private lateinit var netView: TextView
    private var pingResult: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.twentyone_diag_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        logView = view.findViewById(R.id.diag_log)
        netView = view.findViewById(R.id.diag_net)

        org.linphone.twentyone.vpn.TsManager.netDiag.observe(viewLifecycleOwner) {
            renderNet(it)
        }
        renderNet(org.linphone.twentyone.vpn.TsManager.netDiag.value)
        org.linphone.twentyone.vpn.TsManager.refreshStatus()

        view.findViewById<Button>(R.id.diag_net_ping).setOnClickListener {
            val peer = org.linphone.twentyone.vpn.TsManager.netDiag.value
                ?.peers?.firstOrNull { p -> p.address.isNotEmpty() }
            if (peer == null) {
                pingResult = getString(R.string.twentyone_diag_net_none)
                renderNet(org.linphone.twentyone.vpn.TsManager.netDiag.value)
                return@setOnClickListener
            }
            pingResult = getString(R.string.twentyone_diag_net_waiting)
            renderNet(org.linphone.twentyone.vpn.TsManager.netDiag.value)
            org.linphone.twentyone.vpn.TsManager.pingPeer(peer.address) { text ->
                pingResult = text
                view.post {
                    org.linphone.twentyone.vpn.TsManager.refreshStatus()
                    renderNet(org.linphone.twentyone.vpn.TsManager.netDiag.value)
                }
            }
        }

        view.findViewById<Button>(R.id.diag_copy).setOnClickListener {
            val clipboard = requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("Phone21 diagnostika", logView.text)
            )
            Toast.makeText(requireContext(),
                R.string.twentyone_diag_copied, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<Button>(R.id.diag_clear).setOnClickListener {
            TwentyOneDiag.clear()
            refresh()
        }
        refresh()
    }

    private fun renderNet(diag: org.linphone.twentyone.vpn.TsManager.NetDiag?) {
        if (diag == null) {
            netView.text = getString(R.string.twentyone_diag_net_none)
            return
        }
        val sb = StringBuilder()
        sb.append("vlastní endpointy: ").append(diag.endpoints.size)
            .append(" (veřejné ").append(diag.publicEndpoints.size).append(")\n")
        diag.endpoints.forEach { sb.append("  ").append(it).append("\n") }
        sb.append("přenosový uzel: ")
            .append(diag.relay.ifEmpty { "—" }).append("\n")
        if (diag.health.isNotEmpty()) {
            sb.append("hlášení sítě:\n")
            diag.health.forEach { sb.append("  ").append(it).append("\n") }
        }
        diag.peers.forEach { peer ->
            sb.append(peer.host).append(": ")
                .append(if (peer.online) "online" else "offline")
            if (peer.directAddr.isNotEmpty()) {
                sb.append(", přímo přes ").append(peer.directAddr)
            } else if (peer.relay.isNotEmpty()) {
                sb.append(", přes uzel ").append(peer.relay)
            }
            sb.append("\n  adresa ").append(peer.address)
                .append(", rx ").append(peer.rx)
                .append(" B, tx ").append(peer.tx).append(" B\n")
            if (peer.lastHandshake.isNotEmpty()) {
                sb.append("  poslední spojení ").append(peer.lastHandshake).append("\n")
            }
        }
        if (pingResult.isNotEmpty()) {
            sb.append("měření: ").append(pingResult).append("\n")
        }
        netView.text = sb.toString()
    }

    private fun refresh() {
        val text = TwentyOneDiag.read()
        logView.text = text.ifEmpty { getString(R.string.twentyone_diag_empty) }
    }
}
