/*
 * fork: obrazovka deníku chyb (P21-S2). Chybová hláška na obrazovce zmizí —
 * tady se dá dohledat zpětně, s kódem, časem a detaily, a jedním klepnutím
 * zkopírovat pro nahlášení.
 */
package org.linphone.twentyone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.twentyone.vpn.TsManager

class TwentyOneDiagFragment : Fragment() {

    private lateinit var logView: TextView
    private lateinit var netView: TextView
    private var pingResult: String = ""

    // Podrobný log knihovny (průběh hovorů, SIP) se ukládá do souboru, který
    // si uživatel sám vybere — nic se nenahrává na cizí server. Zabalený log
    // vzniká v interním úložišti aplikace a sem se jen zkopíruje.
    private var pendingLogPath: String? = null
    private val saveGzLog = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip")
    ) { uri -> onLogTargetChosen(uri) }
    private val saveTextLog = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> onLogTargetChosen(uri) }

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

        TsManager.netDiag.observe(viewLifecycleOwner) {
            renderNet(it)
        }
        // Prázdnou mapu sítě čte emptyNetText() podle TsManager.networkPaused
        // (tedy podle stejné dvojité kontroly jako obrazovka Konexe), takže se
        // obrazovka musí překreslit i při změně toho příznaku. Stav sítě
        // zůstává druhým podnětem — po odpojení a připojení se obrazovka
        // obnoví, i když nový status ještě nedorazil.
        TsManager.networkPaused.observe(viewLifecycleOwner) {
            renderNet(TsManager.netDiag.value)
        }
        TsManager.state.observe(viewLifecycleOwner) {
            renderNet(TsManager.netDiag.value)
        }
        TsManager.gatewayState.observe(viewLifecycleOwner) {
            renderNet(TsManager.netDiag.value)
        }
        renderNet(TsManager.netDiag.value)
        TsManager.refreshStatus()

        view.findViewById<Button>(R.id.diag_net_ping).setOnClickListener {
            val diag = TsManager.netDiag.value
            // Měří se proti bráně, ne proti prvnímu protějšku v mapě:
            // „spojení s miniserverem" je jediné, co na téhle obrazovce znamená.
            val address = TsManager.gatewayPeer(diag)?.address.orEmpty()
            if (address.isEmpty()) {
                pingResult = noGatewayText(diag)
                renderNet(diag)
                return@setOnClickListener
            }
            pingResult = getString(R.string.twentyone_diag_net_waiting)
            renderNet(diag)
            TsManager.pingPeer(address) { text ->
                pingResult = text
                view.post {
                    TsManager.refreshStatus()
                    renderNet(TsManager.netDiag.value)
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
        view.findViewById<Button>(R.id.diag_save_log).setOnClickListener { button ->
            button.isEnabled = false
            coreContext.postOnCoreThread { core ->
                val path = core.compressLogCollection()
                button.post {
                    button.isEnabled = true
                    if (!isAdded) return@post
                    if (path.isNullOrEmpty() || !File(path).isFile) {
                        Log.e("[Diagnostika] Zabalení logu selhalo: [$path]")
                        toast(R.string.twentyone_diag_save_log_failed)
                        return@post
                    }
                    pendingLogPath = path
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    if (path.endsWith(".gz")) {
                        saveGzLog.launch("phone21-log-$stamp.gz")
                    } else {
                        saveTextLog.launch("phone21-log-$stamp.txt")
                    }
                }
            }
        }
        refresh()
    }

    private fun onLogTargetChosen(uri: Uri?) {
        val source = pendingLogPath
        pendingLogPath = null
        if (uri == null || source == null) return
        val resolver = requireContext().applicationContext.contentResolver
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val out = resolver.openOutputStream(uri, "w")
                        ?: return@withContext false
                    out.use { o -> File(source).inputStream().use { it.copyTo(o) } }
                    true
                } catch (e: Exception) {
                    Log.e("[Diagnostika] Uložení logu selhalo: $e")
                    false
                }
            }
            toast(
                if (ok) R.string.twentyone_diag_save_log_done
                else R.string.twentyone_diag_save_log_failed
            )
        }
    }

    private fun toast(res: Int) {
        if (!isAdded) return
        Toast.makeText(requireContext(), res, Toast.LENGTH_LONG).show()
    }

    /**
     * Text pro prázdnou mapu sítě. Pozastavení (odříznutí) se hlásí VÝHRADNĚ
     * podle TsManager.networkPaused — ten příznak už čeká na dvě prázdné mapy
     * po sobě, takže Diagnostika a Konexe říkají totéž a přechodně prázdná
     * mapa hned po připojení nevyrobí nepravdivou větu o chybějícím kreditu.
     * Běžící síť bez mapy (statusSeen = true, ale nepozastaveno) i stav před
     * prvním statusem (statusSeen = false) dostanou neutrální text.
     */
    private fun emptyNetText(statusSeen: Boolean = true): String =
        if (statusSeen && TsManager.networkPaused.value == true) {
            getString(R.string.twentyone_diag_net_paused)
        } else {
            getString(R.string.twentyone_diag_net_none)
        }

    /** Proč se nedá měřit: buď v mapě nikdo není, nebo v ní není brána. */
    private fun noGatewayText(diag: TsManager.NetDiag?): String {
        if (diag == null || diag.peers.isEmpty()) return emptyNetText(diag != null)
        return when (TsManager.gatewayState.value) {
            TsManager.GatewayState.AMBIGUOUS ->
                getString(R.string.twentyone_tunnel_gateway_ambiguous)
            else -> getString(R.string.twentyone_tunnel_gateway_absent)
        }
    }

    private fun renderNet(diag: TsManager.NetDiag?) {
        if (diag == null) {
            // Žádný status zatím nedorazil — o pozastavení sítě (a už vůbec ne
            // o penězích v účtu) se z toho nedá usuzovat nic.
            netView.text = emptyNetText(statusSeen = false)
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
        if (diag.peers.isEmpty()) {
            sb.append(emptyNetText()).append("\n")
        }
        // gatewayPeer vrací protějšek z TÉHOŽ seznamu, takže stačí totožnost
        // instance — stableId může u netagovaného uzlu chybět.
        val gateway = TsManager.gatewayPeer(diag)
        diag.peers.forEach { peer ->
            val isGateway = gateway != null && peer === gateway
            if (isGateway) sb.append("» ")
            sb.append(peer.host).append(": ")
                .append(if (peer.online) "online" else "offline")
            if (isGateway) {
                sb.append(if (peer.isExitNode) ", miniserver (provoz ven)" else ", miniserver")
            }
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
