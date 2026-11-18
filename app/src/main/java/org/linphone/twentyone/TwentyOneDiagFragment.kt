/*
 * fork: obrazovka deníku chyb (P21-S2). Chybová hláška na obrazovce zmizí —
 * tady se dá dohledat zpětně, s kódem, časem a detaily, a jedním klepnutím
 * zkopírovat pro nahlášení.
 *
 * Síťová část ukazuje i režim tunelu (systémová VPN / tunel uvnitř appky)
 * a relé vnitřního tunelu (porty, šifrování hlasu, hovory, zahozené
 * pakety — jen počty, nikdy token ani klíče).
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
import org.json.JSONObject
import org.linphone.twentyone.vpn.TsManager
import org.linphone.twentyone.vpn.TsModeController
import org.linphone.twentyone.vpn.TsPreferences
import org.linphone.twentyone.vpn.TsRelay

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
        // režim a relé (vnitřní tunel)
        TsManager.mode.observe(viewLifecycleOwner) {
            renderNet(TsManager.netDiag.value)
        }
        TsManager.transportUp.observe(viewLifecycleOwner) {
            renderNet(TsManager.netDiag.value)
        }
        TsRelay.relayInfo.observe(viewLifecycleOwner) {
            renderNet(TsManager.netDiag.value)
        }
        TsModeController.systemBlock.observe(viewLifecycleOwner) {
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
        val appContext = requireContext().applicationContext
        val resolver = appContext.contentResolver
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val out = resolver.openOutputStream(uri, "w")
                        ?: return@withContext false
                    // Soubor odchází z telefonu: bez soukromých klíčů, tokenů
                    // a klíčů šifrování hlasu (viz TwentyOneLogScrub).
                    val secrets = TsRelay.secretsForScrub() +
                        TsPreferences(appContext).accessToken
                    val scrubbed = out.use { o ->
                        TwentyOneLogScrub.copy(File(source), o, source.endsWith(".gz"), secrets)
                    }
                    Log.i(
                        "[Diagnostika] Log uložen, odstraněno: klíčů ${scrubbed.keys}, " +
                            "tokenů ${scrubbed.tokens}, řádků s klíči hlasu ${scrubbed.srtp}"
                    )
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

    /** Řádky o režimu tunelu a relé vnitřního tunelu (bez tokenu). */
    private fun modeLines(): String {
        val sb = StringBuilder()
        val mode = TsModeController.modeInUse()
        sb.append("režim: ").append(
            when (mode) {
                TsModeController.Mode.SYSTEM -> "systémová VPN"
                TsModeController.Mode.INTERNAL -> "tunel uvnitř appky (bez VPN)"
                TsModeController.Mode.OFF -> "vypnuto"
            }
        )
        if (TsModeController.isSwitching()) sb.append(", přepíná se")
        sb.append(", cesta k miniserveru ")
            .append(if (TsManager.isTransportUp()) "stojí" else "nestojí").append("\n")
        val block = TsModeController.systemBlockNow()
        if (block != TsModeController.SystemBlock.NONE) {
            sb.append("  zvolený stupeň chce systémovou VPN: ").append(
                when (block) {
                    TsModeController.SystemBlock.NO_CONSENT -> "chybí souhlas"
                    TsModeController.SystemBlock.OTHER_VPN -> "místo drží jiná VPN"
                    TsModeController.SystemBlock.SLOT_LOST -> "Phone21 o místo přišlo"
                    TsModeController.SystemBlock.NONE -> ""
                }
            ).append("\n")
        }
        if (mode != TsModeController.Mode.INTERNAL) return sb.toString()

        val info = TsRelay.currentInfo()
        if (info == null || !TsRelay.isUp()) {
            val err = TsRelay.status()?.error.orEmpty()
            sb.append("relé: neběží")
            if (err.isNotEmpty()) sb.append(" — ").append(err)
            sb.append(" [P21-E22]\n")
            return sb.toString()
        }
        val stats = try {
            JSONObject(TsRelay.statsJson())
        } catch (_: Exception) {
            JSONObject()
        }
        val media = stats.optJSONObject("media") ?: JSONObject()
        val counters = stats.optJSONObject("counters") ?: JSONObject()
        fun m(key: String) = media.optLong(key, 0L)
        fun c(key: String) = counters.optLong(key, 0L)
        sb.append("relé: signalizace 127.0.0.1:").append(info.sipPort)
            .append(", web ").append(info.httpPort)
            .append(if (info.httpTls) " šifrovaný" else " NEšifrovaný (nepoužit)")
            .append(", hlas ").append(if (info.clientSrtp) "šifrovaný" else "NEšifrovaný")
            .append(", volací knihovna ")
            .append(
                if (stats.optBoolean("clientConnected", false)) {
                    "připojena"
                } else {
                    "nepřipojena (registrace přes relé: P21-E25 v deníku)"
                }
            )
            .append(", běží ").append(stats.optLong("uptimeSec", 0L) / 60).append(" min\n")
        sb.append("  hovory ").append(m("calls")).append(", proudy ").append(m("streams"))
            .append(", pakety k miniserveru ").append(m("packetsToUpstream"))
            .append(", k telefonu ").append(m("packetsToClient")).append("\n")
        sb.append("  zahozeno: cizí odesílatel v telefonu ").append(m("dropForeignClient"))
            .append(", cizí odesílatel ze sítě ").append(m("dropForeignNetwork"))
            .append(", bez cíle ").append(m("dropNoDestination"))
            .append(", chyba zápisu ").append(m("writeErrors")).append("\n")
        if (info.clientSrtp) {
            sb.append("  šifrování hlasu: neověřeno ").append(m("srtpAuthFail"))
                .append(", opakované ").append(m("srtpReplay"))
                .append(", bez klíče ").append(m("srtpNoKey") + m("srtpNoKeyToClient"))
                .append("\n")
        }
        val rejected = c("client.rejected")
        val forbidden = c("http.forbidden_token")
        val tlsFailed = c("http.tls_failed")
        if (rejected > 0 || forbidden > 0 || tlsFailed > 0) {
            sb.append("  cizí klient na 127.0.0.1: bez certifikátu ").append(rejected)
                .append(", bez tokenu ").append(forbidden)
                .append(", web bez šifrování ").append(tlsFailed).append(" [P21-E23]\n")
        }
        val upErr = c("http.upstream_error") + c("http.rejected_target")
        sb.append("  web: požadavků ").append(c("http.requests"))
        if (upErr > 0) sb.append(", nedoručeno ").append(upErr).append(" [P21-E24]")
        sb.append("\n")
        return sb.toString()
    }

    private fun renderNet(diag: TsManager.NetDiag?) {
        if (diag == null) {
            // Žádný status zatím nedorazil — o pozastavení sítě (a už vůbec ne
            // o penězích v účtu) se z toho nedá usuzovat nic.
            netView.text = modeLines() + emptyNetText(statusSeen = false)
            return
        }
        val sb = StringBuilder(modeLines())
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
