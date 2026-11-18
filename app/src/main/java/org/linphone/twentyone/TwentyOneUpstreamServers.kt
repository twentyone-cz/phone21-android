package org.linphone.twentyone

import androidx.annotation.WorkerThread
import org.linphone.core.Core
import org.linphone.core.tools.Log

/**
 * Phone21 nic neposílá na servery původní appky.
 *
 * Upstream při migraci konfigurace (tedy i po čisté instalaci a po
 * aktualizaci) doplňuje adresu serveru pro nahrání logů. Tlačítko „nahrát
 * logy“ v Nápovědě by pak poslalo podrobný log (čísla, adresy) cizímu
 * serveru. Log se ukládá do souboru v Diagnostice, nahrávání nepotřebujeme.
 * Prázdná adresa tlačítko skryje (HelpViewModel.uploadLogsAvailable).
 * Stejně tak kontrola aktualizací: ptala by se serveru původní appky a
 * nabízela jeho verze (HelpViewModel.checkUpdateAvailable).
 */
object TwentyOneUpstreamServers {
    private const val TAG = "[TwentyOne Upstream Servers]"

    @WorkerThread
    fun clear(core: Core) {
        val logUrl = core.logCollectionUploadServerUrl
        if (!logUrl.isNullOrEmpty() && isUpstream(logUrl)) {
            Log.w("$TAG Removing upstream logs upload server")
            core.logCollectionUploadServerUrl = ""
        }
        val fileUrl = core.fileTransferServer
        if (!fileUrl.isNullOrEmpty() && isUpstream(fileUrl)) {
            Log.w("$TAG Removing upstream file transfer server")
            core.fileTransferServer = ""
        }
        val updateUrl = core.config.getString("misc", "version_check_url_root", "")
        if (!updateUrl.isNullOrEmpty() && isUpstream(updateUrl)) {
            Log.w("$TAG Removing upstream update check server")
            core.config.setString("misc", "version_check_url_root", "")
        }
    }

    private fun isUpstream(url: String): Boolean {
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()
        return host == "linphone.org" || host.endsWith(".linphone.org")
    }
}
