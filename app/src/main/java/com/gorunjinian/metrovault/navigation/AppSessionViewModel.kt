package com.gorunjinian.metrovault.navigation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gorunjinian.metrovault.core.crypto.SessionKeyManager
import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.domain.Wallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-level session orchestration: the post-unlock auto-open use case and the
 * session-expiry watcher.
 *
 * Decisions are emitted as [NavigationEvent]s; AppNavigation collects them and
 * drives the NavController. Running the wallet load in [viewModelScope] keeps it
 * alive across recomposition and configuration changes, so no screen disposal
 * can cancel it mid-flight.
 */
class AppSessionViewModel(application: Application) : AndroidViewModel(application) {

    private val wallet: Wallet by lazy { Wallet.getInstance(application.applicationContext) }
    private val secureStorage: SecureStorage by lazy { SecureStorage(application.applicationContext) }
    private val sessionKeyManager = SessionKeyManager.getInstance()

    sealed interface NavigationEvent {
        /** Normal unlock: land on Home, clearing the unlock screen. */
        data object ToHome : NavigationEvent

        /** Auto-open unlock: Home then WalletDetails, so back returns to Home. */
        data object ToHomeThenWalletDetails : NavigationEvent

        /** Session expired while visible on a non-auth screen. */
        data object ToUnlock : NavigationEvent

        /**
         * Session ended and no main password exists any more: a duress session
         * just closed. The app is a fresh install now, so show setup.
         */
        data object ToSetup : NavigationEvent
    }

    private val _navigationEvents = Channel<NavigationEvent>(Channel.BUFFERED)
    val navigationEvents = _navigationEvents.receiveAsFlow()

    private val isAppResumed = MutableStateFlow(true)
    private val currentRoute = MutableStateFlow<String?>(null)

    // Guard against re-entry while a wallet load is in flight
    private var isProcessingAutoOpen = false

    init {
        // Session-expiry watcher. Only fires while the app is resumed (avoids
        // flashing the unlock screen when backgrounding) and not already on an
        // auth screen.
        viewModelScope.launch {
            combine(
                sessionKeyManager.isSessionActive,
                isAppResumed,
                currentRoute
            ) { active, resumed, route -> Triple(active, resumed, route) }
                .collect { (active, resumed, route) ->
                    if (!active && resumed && route != null &&
                        route != Screen.Unlock.route &&
                        route != Screen.SetupPassword.route
                    ) {
                        AppLog.d(TAG) { "Session expired while app resumed - navigating to auth screen" }
                        // Wipe stateless wallet on session lock
                        wallet.wipeStatelessWallet()
                        val provisioned = withContext(Dispatchers.IO) {
                            if (secureStorage.hasMainPassword()) {
                                true
                            } else {
                                // A duress session just ended. Anything it wrote to
                                // disk (settings, biometric material) must not leak
                                // into the vault about to be set up.
                                secureStorage.wipeResidueIfUnprovisioned()
                                false
                            }
                        }
                        _navigationEvents.send(
                            if (provisioned) NavigationEvent.ToUnlock else NavigationEvent.ToSetup
                        )
                    }
                }
        }
    }

    fun onAppResumedChanged(resumed: Boolean) {
        isAppResumed.value = resumed
    }

    fun onDestinationChanged(route: String?) {
        currentRoute.value = route
    }

    /**
     * Post-unlock: with auto-open requested and exactly one wallet, load it
     * before navigating so WalletDetails opens ready; otherwise go to Home.
     */
    fun onUnlockSuccess(autoOpenRequested: Boolean) {
        if (isProcessingAutoOpen) {
            AppLog.d(TAG) { "Already processing auto-open, skipping" }
            return
        }
        isProcessingAutoOpen = true
        viewModelScope.launch {
            try {
                if (autoOpenRequested) {
                    val wallets = wallet.wallets.value
                    AppLog.d(TAG) { "Checking wallets: size=${wallets.size}" }
                    if (wallets.size == 1) {
                        val loaded = withContext(Dispatchers.IO) {
                            wallet.openWallet(wallets.first().id, showLoading = false)
                        }
                        AppLog.d(TAG) { "Wallet loaded: $loaded" }
                        if (loaded) {
                            _navigationEvents.send(NavigationEvent.ToHomeThenWalletDetails)
                            return@launch
                        }
                    }
                }
                _navigationEvents.send(NavigationEvent.ToHome)
            } finally {
                isProcessingAutoOpen = false
            }
        }
    }

    /** Wipe loaded wallet keys from memory when returning to Home (security). */
    fun onHomeOpened() {
        wallet.unloadAllWalletKeys()
    }

    private companion object {
        private const val TAG = "AppSessionViewModel"
    }
}
