/*
 * fork: úvodní obrazovka asistenta bez linphone.org účtů.
 * Nabízí jen QR provisioning (doporučená cesta — QR generuje brána)
 * a ruční zadání údajů. Původní LandingFragment zůstává v projektu, ale
 * z navigace na něj nevede cesta.
 */
package org.linphone.twentyone.assistant

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import org.linphone.R

class TwentyOneLandingFragment : Fragment() {

    // oprávnění Místní síť se vyžádá před otevřením skeneru, ne přes kameru
    private val localNetPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        org.linphone.core.tools.Log.i("[Landing] ACCESS_LOCAL_NETWORK granted [$granted]")
        if (isAdded) openScanner()
    }

    private fun openScanner() {
        findNavController().navigate(R.id.action_twentyOneLanding_to_qrCodeScanner)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.twentyone_landing_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Button>(R.id.twentyone_scan_qr).setOnClickListener {
            if (org.linphone.compatibility.Compatibility
                    .isAccessLocalNetworkPermissionGranted(requireContext())
            ) {
                openScanner()
            } else {
                localNetPermission.launch(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }
        view.findViewById<Button>(R.id.twentyone_manual).setOnClickListener {
            findNavController().navigate(R.id.action_twentyOneLanding_to_thirdPartySipAccountLogin)
        }
    }
}
