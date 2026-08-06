package org.linphone.twentyone.vpn

import androidx.annotation.WorkerThread
import androidx.lifecycle.Observer
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
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
    }

    private val stateObserver = Observer<TsManager.State> {
        evaluate()
    }

    private val loginObserver = Observer<Boolean> {
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
        }
        applyState(false)
    }

    @WorkerThread
    fun onCoreStopped() {
        core?.removeListener(coreListener)
        coreContext.postOnMainThread {
            TsManager.state.removeObserver(stateObserver)
            TsManager.loggedIn.removeObserver(loginObserver)
        }
        this.core = null
    }

    private fun evaluate() {
        val state = TsManager.state.value
        val loggedIn = TsManager.loggedIn.value == true
        val available = loggedIn && (state == TsManager.State.RUNNING)
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
