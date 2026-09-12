package com.gorunjinian.metrovault.feature.wallet.details

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gorunjinian.metrovault.core.qr.AnimatedQRResult
import com.gorunjinian.metrovault.core.qr.AnimatedQRScanner
import com.gorunjinian.metrovault.core.qr.OutputFormat
import com.gorunjinian.metrovault.core.qr.QRCodeUtils
import com.gorunjinian.metrovault.core.qr.QRDensity
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.domain.service.bitcoin.WalletMessageSigner
import com.gorunjinian.metrovault.domain.service.psbt.PSBTDecoder
import com.gorunjinian.vaultovich.MessageSigning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Sign/Verify Message screen.
 * Owns the sign/verify/staged-PSBT flow and the signed-PSBT QR presentation state;
 * all wallet and protocol logic lives in [WalletMessageSigner]. Camera permission and
 * the barcode view lifecycle stay in the screen.
 */
class SignMessageViewModel(application: Application) : AndroidViewModel(application) {

    @SuppressLint("StaticFieldLeak")
    private val context = application.applicationContext

    // Dependencies
    private val wallet: Wallet by lazy { Wallet.getInstance(context) }

    /** What a single-shot scan fills in. PSBT frames are format-detected, independent of this. */
    enum class ScanTarget { ADDRESS, MESSAGE }

    // Shares the Scan PSBT viewfinder's frame joiner; owning it here keeps a
    // partially collected animated scan alive across configuration changes.
    val animatedScanner = AnimatedQRScanner(PSBTDecoder::decode)

    // ========== UI State ==========

    data class UiState(
        val addressInput: String = "",
        val messageInput: String = "",
        val signatureInput: String = "",

        val isProcessing: Boolean = false,
        val errorMessage: String = "",
        val successMessage: String = "",

        // Scanner
        val isScanning: Boolean = false,
        val scanTarget: ScanTarget = ScanTarget.ADDRESS,
        val scanProgress: Int = 0,
        val isAnimatedScan: Boolean = false,

        // SP receive addresses are one-off taproot keys: only BIP-322 binds a signature to them,
        // so the ECDSA formats are disabled for silent-payment wallets.
        val isSilentPayment: Boolean = false,
        val signatureFormat: MessageSigning.SignatureFormat = MessageSigning.SignatureFormat.ELECTRUM,

        // Bare signature QR dialog
        val signatureQRBitmap: Bitmap? = null,
        val showSignatureQR: Boolean = false,

        // A scanned-but-not-yet-signed BIP-322 message PSBT: signing waits for the Sign button.
        // Cleared when the user edits the request fields (the PSBT commits to the scanned values).
        val pendingMessagePsbt: String? = null,

        // The signed message PSBT, displayed via SignedPSBTDisplay for the watching wallet to scan.
        val signedMessagePsbt: String? = null,
        val signedQRResult: AnimatedQRResult? = null,
        val showSignedPsbtDisplay: Boolean = false,
        val currentDisplayFrame: Int = 0,
        val selectedOutputFormat: OutputFormat = OutputFormat.UR_LEGACY,
        val selectedDensity: QRDensity = QRDensity.HIGH,
        val isQRPaused: Boolean = false,
        val isRegeneratingQR: Boolean = false
    ) {
        // Sign if address+message only, Verify if all three
        val canSign: Boolean
            get() = addressInput.isNotBlank() && messageInput.isNotBlank() && signatureInput.isBlank()
        val canVerify: Boolean
            get() = addressInput.isNotBlank() && messageInput.isNotBlank() && signatureInput.isNotBlank()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var prefillApplied = false

    init {
        // The active wallet's derivation path comes from storage — resolve it off the main thread
        viewModelScope.launch {
            val isSilentPayment = withContext(Dispatchers.IO) {
                DerivationPaths.getPurpose(wallet.getActiveWalletDerivationPath()) == 352
            }
            if (isSilentPayment) {
                _uiState.update {
                    it.copy(
                        isSilentPayment = true,
                        signatureFormat = MessageSigning.SignatureFormat.BIP322
                    )
                }
            }
        }
    }

    /** Applies the navigation-supplied address once; recompositions and rotations are no-ops. */
    fun applyPrefilledAddress(address: String?) {
        if (prefillApplied) return
        prefillApplied = true
        if (!address.isNullOrBlank()) {
            _uiState.update { it.copy(addressInput = address) }
        }
    }

    // ========== Input fields ==========

    fun setAddressInput(value: String) {
        _uiState.update {
            it.copy(addressInput = value, pendingMessagePsbt = null, errorMessage = "", successMessage = "")
        }
    }

    fun setMessageInput(value: String) {
        _uiState.update {
            it.copy(messageInput = value, pendingMessagePsbt = null, errorMessage = "", successMessage = "")
        }
    }

    fun setSignatureInput(value: String) {
        _uiState.update { it.copy(signatureInput = value, errorMessage = "", successMessage = "") }
    }

    fun setSignatureFormat(format: MessageSigning.SignatureFormat) {
        _uiState.update { it.copy(signatureFormat = format) }
    }

    fun clearAll() {
        _uiState.update {
            it.copy(
                addressInput = "",
                messageInput = "",
                signatureInput = "",
                pendingMessagePsbt = null,
                errorMessage = "",
                successMessage = ""
            )
        }
    }

    // ========== Scanning ==========

    /** Records what the next single-shot scan should fill in; the screen then asks for camera permission. */
    fun prepareScan(target: ScanTarget) {
        _uiState.update { it.copy(scanTarget = target) }
    }

    /** Called once camera permission is granted: resets the frame joiner and shows the viewfinder. */
    fun startScanning() {
        animatedScanner.reset()
        _uiState.update { it.copy(isScanning = true, scanProgress = 0, isAnimatedScan = false) }
    }

    fun stopScanning() {
        _uiState.update { it.copy(isScanning = false) }
    }

    fun onScanProgress(progress: Int, animated: Boolean) {
        _uiState.update { it.copy(scanProgress = progress, isAnimatedScan = animated) }
    }

    /** A scanned BIP-322 message-signing PSBT: validate and fill the request, don't sign yet. */
    fun onScanComplete(psbtBase64: String) {
        _uiState.update { it.copy(isScanning = false) }
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, errorMessage = "", successMessage = "") }
            try {
                val result = withContext(Dispatchers.Default) {
                    WalletMessageSigner.parseMessagePsbtRequest(wallet, psbtBase64)
                }
                result.fold(
                    onSuccess = { request ->
                        _uiState.update {
                            it.copy(
                                addressInput = request.address,
                                messageInput = request.message,
                                signatureInput = "",
                                signatureFormat = MessageSigning.SignatureFormat.BIP322,
                                pendingMessagePsbt = psbtBase64,
                                successMessage = "Message-signing request."
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(errorMessage = error.message ?: "Could not read the message-signing PSBT")
                        }
                    }
                )
            } finally {
                _uiState.update { it.copy(isProcessing = false) }
            }
        }
    }

    /** A plain (non-PSBT) payload: an address, a message, or a Sparrow-style signmessage QR. */
    fun onPlainScan(result: String) {
        _uiState.update { it.copy(isScanning = false) }
        when (_uiState.value.scanTarget) {
            ScanTarget.ADDRESS -> {
                // Extract address from bitcoin: URI if present
                val address = if (result.startsWith("bitcoin:", ignoreCase = true)) {
                    result.substringAfter(":").substringBefore("?")
                } else {
                    result
                }
                _uiState.update { it.copy(addressInput = address, pendingMessagePsbt = null) }
            }
            ScanTarget.MESSAGE -> {
                // Sparrow-style "signmessage <path> ascii:<message>" QR, or a plain message
                if (result.startsWith("signmessage ", ignoreCase = true)) {
                    viewModelScope.launch {
                        val parsed = withContext(Dispatchers.Default) {
                            WalletMessageSigner.parseSignMessageQr(wallet, result)
                        }
                        if (parsed != null) {
                            _uiState.update {
                                it.copy(
                                    addressInput = parsed.first,
                                    messageInput = parsed.second,
                                    pendingMessagePsbt = null
                                )
                            }
                        } else {
                            _uiState.update {
                                it.copy(errorMessage = "Could not parse signmessage QR or derive address")
                            }
                        }
                    }
                } else {
                    _uiState.update { it.copy(messageInput = result, pendingMessagePsbt = null) }
                }
            }
        }
    }

    // ========== Sign / Verify ==========

    fun signOrVerify() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(isProcessing = true, errorMessage = "", successMessage = "") }
            try {
                if (state.canSign) {
                    val pending = state.pendingMessagePsbt
                    if (pending != null) {
                        // A scanned message-signing PSBT: sign it and show the signed PSBT QR.
                        val result = withContext(Dispatchers.Default) {
                            WalletMessageSigner.signMessagePsbt(wallet, pending)
                        }
                        result.fold(
                            onSuccess = { signed ->
                                val qr = withContext(Dispatchers.Default) {
                                    QRCodeUtils.generateSmartPSBTQR(
                                        signed.signedPsbtBase64,
                                        format = state.selectedOutputFormat,
                                        density = state.selectedDensity
                                    )
                                }
                                _uiState.update {
                                    it.copy(
                                        signatureInput = signed.signature,
                                        successMessage = "Message signed successfully ✓",
                                        signedMessagePsbt = signed.signedPsbtBase64,
                                        signedQRResult = qr,
                                        currentDisplayFrame = 0,
                                        showSignedPsbtDisplay = qr != null,
                                        errorMessage = if (qr == null) "Failed to generate QR code" else ""
                                    )
                                }
                            },
                            onFailure = { error ->
                                _uiState.update {
                                    it.copy(errorMessage = error.message ?: "Failed to sign the message PSBT")
                                }
                            }
                        )
                    } else {
                        val result = withContext(Dispatchers.Default) {
                            WalletMessageSigner.signWithAddress(
                                wallet, state.addressInput, state.messageInput,
                                state.signatureFormat, state.isSilentPayment
                            )
                        }
                        result.fold(
                            onSuccess = { signature ->
                                _uiState.update {
                                    it.copy(signatureInput = signature, successMessage = "Message signed successfully ✓")
                                }
                            },
                            onFailure = { error ->
                                _uiState.update { it.copy(errorMessage = error.message ?: "Failed to sign message") }
                            }
                        )
                    }
                } else if (state.canVerify) {
                    val outcome = withContext(Dispatchers.Default) {
                        WalletMessageSigner.verify(
                            state.addressInput, state.messageInput, state.signatureInput, state.signatureFormat
                        )
                    }
                    if (outcome.isValid) {
                        val formatName = outcome.detectedFormat?.displayName ?: "Unknown"
                        _uiState.update {
                            it.copy(
                                successMessage = "Signature is valid ✓ (Format: $formatName)",
                                signatureFormat = outcome.detectedFormat ?: it.signatureFormat
                            )
                        }
                    } else {
                        _uiState.update { it.copy(errorMessage = "Signature is invalid ✗") }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "An error occurred") }
            } finally {
                _uiState.update { it.copy(isProcessing = false) }
            }
        }
    }

    // ========== Signed-PSBT QR presentation ==========

    /** Regenerate the signed-PSBT QR for the current format/density, keeping the old QR on failure. */
    private fun regenerateSignedQr() {
        val psbt = _uiState.value.signedMessagePsbt ?: return
        _uiState.update { it.copy(currentDisplayFrame = 0, isRegeneratingQR = true) }
        viewModelScope.launch {
            val state = _uiState.value
            val newQR = withContext(Dispatchers.Default) {
                QRCodeUtils.generateSmartPSBTQR(psbt, format = state.selectedOutputFormat, density = state.selectedDensity)
            }
            _uiState.update { it.copy(signedQRResult = newQR ?: it.signedQRResult, isRegeneratingQR = false) }
        }
    }

    fun setOutputFormat(format: OutputFormat) {
        _uiState.update { it.copy(selectedOutputFormat = format) }
        regenerateSignedQr()
    }

    fun setDensity(density: QRDensity) {
        if (density == _uiState.value.selectedDensity) return
        _uiState.update { it.copy(selectedDensity = density) }
        regenerateSignedQr()
    }

    fun setShowSignedPsbtDisplay(show: Boolean) {
        _uiState.update { it.copy(showSignedPsbtDisplay = show) }
    }

    fun setQRPaused(paused: Boolean) {
        _uiState.update { it.copy(isQRPaused = paused) }
    }

    fun advanceDisplayFrame() {
        _uiState.update { state ->
            val totalFrames = state.signedQRResult?.frames?.size ?: return
            state.copy(currentDisplayFrame = (state.currentDisplayFrame + 1) % totalFrames)
        }
    }

    fun previousDisplayFrame() {
        _uiState.update { state ->
            val totalFrames = state.signedQRResult?.frames?.size ?: return
            state.copy(currentDisplayFrame = (state.currentDisplayFrame - 1 + totalFrames) % totalFrames)
        }
    }

    /** Leaves the signed-PSBT display and clears the request; the screen then re-opens the scanner. */
    fun prepareScanAnother() {
        _uiState.update {
            it.copy(showSignedPsbtDisplay = false, signedMessagePsbt = null, signedQRResult = null)
        }
        clearAll()
    }

    // ========== Bare signature QR dialog ==========

    fun showSignatureQr() {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                QRCodeUtils.generateQRCode(_uiState.value.signatureInput)
            }
            _uiState.update { it.copy(signatureQRBitmap = bitmap, showSignatureQR = true) }
        }
    }

    fun dismissSignatureQr() {
        _uiState.update { it.copy(showSignatureQR = false) }
    }
}
