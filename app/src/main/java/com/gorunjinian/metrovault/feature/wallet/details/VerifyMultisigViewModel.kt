package com.gorunjinian.metrovault.feature.wallet.details

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.data.model.Result
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.domain.service.multisig.BSMS
import com.gorunjinian.metrovault.domain.service.multisig.MultisigAddressService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the "Verify & Register Multisig" ceremony: loads the wallet's descriptor, derives the
 * first receive address and descriptor checksum for cross-checking against the coordinator, and
 * persists the verified/registered state on confirmation.
 */
class VerifyMultisigViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "VerifyMultisigVM"
    }

    @SuppressLint("StaticFieldLeak")
    private val context = application.applicationContext
    private val wallet: Wallet by lazy { Wallet.getInstance(context) }
    private val addressService = MultisigAddressService()

    enum class Stage { LOADING, REVIEW, RECEIPT, ERROR }

    data class UiState(
        val stage: Stage = Stage.LOADING,
        val walletName: String = "",
        val config: MultisigConfig? = null,
        val firstReceiveAddress: String = "",
        val descriptorChecksum: String = "",
        val alreadyRegistered: Boolean = false,
        val isProcessing: Boolean = false,
        val errorMessage: String = ""
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var walletId: String = ""

    /** Load the wallet by id. Safe to call repeatedly (e.g. on recomposition). */
    fun load(walletId: String) {
        if (this.walletId == walletId && _uiState.value.stage != Stage.LOADING) return
        this.walletId = walletId
        viewModelScope.launch {
            val metadata = wallet.wallets.value.firstOrNull { it.id == walletId }
            val config = metadata?.multisigConfig
            if (metadata == null || config == null) {
                _uiState.update { it.copy(stage = Stage.ERROR, errorMessage = "Multisig wallet not found.") }
                return@launch
            }
            val alreadyRegistered = wallet.isMultisigRegistered(metadata)
            val (address, checksum) = withContext(Dispatchers.Default) {
                val addr = when (val r = addressService.generateMultisigAddress(
                    config = config, index = 0, isChange = false, isTestnet = config.isTestnet()
                )) {
                    is Result.Success -> r.value.address
                    is Result.Error -> ""
                }
                addr to BSMS.descriptorChecksum(config.rawDescriptor)
            }
            _uiState.update {
                it.copy(
                    // Already-registered wallets open straight to the receipt (view / re-verify).
                    stage = if (alreadyRegistered) Stage.RECEIPT else Stage.REVIEW,
                    walletName = metadata.name,
                    config = config,
                    firstReceiveAddress = address,
                    descriptorChecksum = checksum,
                    alreadyRegistered = alreadyRegistered
                )
            }
        }
    }

    /** Re-open the review step for an already-registered wallet (re-verify). */
    fun reReview() {
        _uiState.update { it.copy(stage = Stage.REVIEW) }
    }

    /** Persist the verified/registered state, binding it to the current descriptor checksum. */
    fun confirmRegistration() {
        val id = walletId
        if (id.isEmpty()) return
        _uiState.update { it.copy(isProcessing = true) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) { wallet.markMultisigVerified(id) }
            _uiState.update {
                if (ok) {
                    AppLog.d(TAG) { "Multisig wallet registered" }
                    it.copy(isProcessing = false, stage = Stage.RECEIPT, alreadyRegistered = true)
                } else {
                    it.copy(isProcessing = false, stage = Stage.ERROR, errorMessage = "Failed to register wallet.")
                }
            }
        }
    }
}
