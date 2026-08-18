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

    private fun refresh() {
        val text = TwentyOneDiag.read()
        logView.text = text.ifEmpty { getString(R.string.twentyone_diag_empty) }
    }
}
