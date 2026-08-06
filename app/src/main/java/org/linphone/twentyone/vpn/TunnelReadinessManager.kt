package org.linphone.twentyone.vpn

import android.content.Intent
import androidx.annotation.WorkerThread
import androidx.lifecycle.Observer
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.ConfiguringState
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.tools.Log

class TunnelReadinessManager {
    companion object {
        private const val TAG = "[Tunnel Readiness]"
    }

    private var core: Core? = null
    private var ready = false

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onAccountAdded(core: Core, account: Account) {
            applyState(ready)
        }

        @WorkerThread
        override fun onConfiguringStatus(
            core: Core,
            status: ConfiguringState?,
            message: String?
        ) {
            if (status != ConfiguringState.Successful) return
            val (url, key) = TsProvisioning.takeNetworkKey(core)
            if (key.isEmpty()) return
            val intent = Intent(coreContext.context, TsConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(TsConsentActivity.EXTRA_URL, url)
                putExtra(TsConsentActivity.EXTRA_KEY, key)
            }
            coreContext.context.startActivity(intent)
        }
    }

    private val stateObserver = Observer<TsManager.State> {
        evaluate()
    }

    private val loginObserver = Observer<Boolean> {
        evaluate()
    }

    private val endpointObserver = Observer<Boolean> {
        evaluate()
    }

    @WorkerThread
    fun onCoreStarted(core: Core) {
        this.core = core
        core.addListener(coreListener)
        coreContext.postOnMainThread {
            TsManager.ensureStarted(coreContext.context)
            TsManager.state.observeForever(stateObserver)
            TsManager.loggedIn.observeForever(loginObserver)
            TsManager.endpointVerified.observeForever(endpointObserver)
        }
        applyState(false)
    }

    @WorkerThread
    fun onCoreStopped() {
        core?.removeListener(coreListener)
        coreContext.postOnMainThread {
            TsManager.state.removeObserver(stateObserver)
            TsManager.loggedIn.removeObserver(loginObserver)
            TsManager.endpointVerified.removeObserver(endpointObserver)
        }
        this.core = null
    }

    private fun evaluate() {
        val state = TsManager.state.value
        val loggedIn = TsManager.loggedIn.value == true
        val verified = TsManager.endpointVerified.value == true
        val available = loggedIn && verified && (state == TsManager.State.RUNNING)
        if (available == ready) return
        ready = available
        Log.i("$TAG Network readiness is now [$available]")
        coreContext.postOnCoreThread {
            applyState(available)
        }
    }

    @WorkerThread
    private fun applyState(available: Boolean) {
        val core = this.core ?: return
        for (account in core.accountList) {
            val params = account.params
            if (params.isRegisterEnabled == available) continue
            val copy = params.clone()
            copy.isRegisterEnabled = available
            account.params = copy
        }
    }
}
