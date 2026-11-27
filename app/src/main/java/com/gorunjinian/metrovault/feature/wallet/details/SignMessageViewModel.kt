package com.gorunjinian.metrovault.feature.wallet.details

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gorunjinian.metrovault.core.qr.AnimatedQRResult
import com.gorunjinian.metrovault.core.qr.OutputFormat
import com.gorunjinian.metrovault.core.qr.QRCodeUtils
import com.gorunjinian.metrovault.core.qr.QRDensity
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.domain.service.bitcoin.SignMessageRequest
import com.gorunjinian.metrovault.domain.service.bitcoin.WalletMessageSigner
import com.gorunjinian.metrovault.domain.service.scan.CompletedScan
import com.gorunjinian.metrovault.domain.service.scan.SignatureEncoding
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
 * all wallet and protocol logic lives in [WalletMessageSigner], and what a scan means for the
 * form in [MessageScanInput].
 */
class SignMessageViewModel(application: Application) : AndroidViewModel(application) {

    @SuppressLint("StaticFieldLeak")
    private val context = application.applicationContext

    // Dependencies
    private val wallet: Wallet by lazy { Wallet.getInstance(context) }

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
        val scanTarget: MessageScanTarget = MessageScanTarget.ADDRESS,

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

        // A scanned text request (signmessage / Coinkite JSON): signing uses the key at its path
        // rather than searching the active account for the address. Cleared when the address
        // changes, since the address shown is the one derived from that path.
        val pendingSignRequest: SignMessageRequest? = null,

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

    /** The active wallet's kind comes from storage — resolve it off the main thread. */
    private val walletKindLookup = viewModelScope.launch {
        val isSilentPayment = withContext(Dispatchers.IO) { wallet.isActiveSilentPayment() }
        if (isSilentPayment) {
            _uiState.update {
                it.copy(
                    isSilentPayment = true,
                    signatureFormat = MessageSigning.SignatureFormat.BIP322
                )
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
            it.copy(
                addressInput = value,
                pendingMessagePsbt = null,
                pendingSignRequest = null,
                errorMessage = "",
                successMessage = ""
            )
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
                pendingSignRequest = null,
                // Verifying a scanned ECDSA signature leaves a format this wallet can't sign in
                signatureFormat = if (it.isSilentPayment) MessageSigning.SignatureFormat.BIP322 else it.signatureFormat,
                errorMessage = "",
                successMessage = ""
            )
        }
    }

    // ========== Scanning ==========

    /** Shows the scanner; [target] is the field a plain address or message fills in. */
    fun startScanning(target: MessageScanTarget) {
        _uiState.update { it.copy(isScanning = true, scanTarget = target) }
    }

    fun stopScanning() {
        _uiState.update { it.copy(isScanning = false) }
    }

    /** A finished scan: closes the scanner and fills in the form, or stages a signing request. */
    fun onScan(scan: CompletedScan) {
        _uiState.update { it.copy(isScanning = false) }
        fillIn(MessageScanInput.from(scan, _uiState.value.scanTarget))
    }

    /** A scan another screen opened this one with. */
    fun onScanHandedOver(scan: CompletedScan) {
        viewModelScope.launch {
            // Arrives as the screen opens, and what a request may do depends on the wallet's kind
            walletKindLookup.join()
            fillIn(MessageScanInput.fromHandoff(scan.payload))
        }
    }

    private fun fillIn(input: MessageScanInput) {
        when (input) {
            is MessageScanInput.MessagePsbt -> stageMessagePsbt(input.base64)
            is MessageScanInput.Request -> stageSignRequest(input.request)
            is MessageScanInput.Address -> _uiState.update {
                it.copy(
                    addressInput = input.address,
                    pendingMessagePsbt = null,
                    pendingSignRequest = null,
                    errorMessage = "",
                    successMessage = ""
                )
            }
            is MessageScanInput.Message -> _uiState.update {
                it.copy(messageInput = input.message, pendingMessagePsbt = null, errorMessage = "", successMessage = "")
            }
            is MessageScanInput.Signature -> _uiState.update {
                it.copy(
                    addressInput = input.address,
                    messageInput = input.message,
                    signatureInput = input.signature,
                    // Verifying tries only the selected protocol, so it has to be the signature's
                    signatureFormat = when (input.encoding) {
                        SignatureEncoding.BIP322_SIMPLE -> MessageSigning.SignatureFormat.BIP322
                        SignatureEncoding.RECOVERABLE_ECDSA -> MessageSigning.SignatureFormat.ELECTRUM
                        null -> it.signatureFormat
                    },
                    pendingMessagePsbt = null,
                    pendingSignRequest = null,
                    errorMessage = "",
                    successMessage = ""
                )
            }
            is MessageScanInput.Unusable -> _uiState.update {
                it.copy(errorMessage = input.reason, successMessage = "")
            }
        }
    }

    /** A scanned BIP-322 message-signing PSBT: validate and fill the request, don't sign yet. */
    private fun stageMessagePsbt(psbtBase64: String) {
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
                                pendingSignRequest = null,
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

    /**
     * Fill in a scanned text signing request: the address (derived from its path) and the message.
     * Like the message-PSBT flow, nothing is signed until the user presses Sign.
     */
    private fun stageSignRequest(request: SignMessageRequest) {
        if (_uiState.value.isSilentPayment) {
            _uiState.update {
                it.copy(
                    errorMessage = "Silent payment wallets sign messages only from the watching " +
                        "wallet's message-signing PSBT QR.",
                    successMessage = ""
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, errorMessage = "", successMessage = "") }
            try {
                val address = withContext(Dispatchers.Default) {
                    WalletMessageSigner.resolveRequestAddress(wallet, request)
                }
                if (address == null) {
                    _uiState.update {
                        it.copy(errorMessage = "This wallet has no key to sign with at ${request.displayPath}")
                    }
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        addressInput = address,
                        messageInput = request.message,
                        signatureInput = "",
                        pendingMessagePsbt = null,
                        pendingSignRequest = request,
                        // These requests expect a classic recoverable signature.
                        signatureFormat = if (it.signatureFormat == MessageSigning.SignatureFormat.BIP322) {
                            MessageSigning.SignatureFormat.ELECTRUM
                        } else {
                            it.signatureFormat
                        },
                        successMessage = "Message-signing request."
                    )
                }
            } finally {
                _uiState.update { it.copy(isProcessing = false) }
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
                    } else if (state.pendingSignRequest != null) {
                        // A scanned text request: sign with the key at its path, then show the
                        // signature QR straight away for the requesting wallet to scan back.
                        val result = withContext(Dispatchers.Default) {
                            WalletMessageSigner.signRequest(
                                wallet, state.pendingSignRequest, state.messageInput, state.signatureFormat
                            )
                        }
                        result.fold(
                            onSuccess = { signature ->
                                val bitmap = withContext(Dispatchers.Default) {
                                    QRCodeUtils.generateQRCode(signature)
                                }
                                _uiState.update {
                                    it.copy(
                                        signatureInput = signature,
                                        successMessage = "Message signed successfully ✓",
                                        signatureQRBitmap = bitmap,
                                        showSignatureQR = bitmap != null
                                    )
                                }
                            },
                            onFailure = { error ->
                                _uiState.update { it.copy(errorMessage = error.message ?: "Failed to sign message") }
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

    /** Leaves the signed-PSBT display, clears the request and opens the scanner for the next one. */
    fun scanAnother() {
        _uiState.update {
            it.copy(showSignedPsbtDisplay = false, signedMessagePsbt = null, signedQRResult = null)
        }
        clearAll()
        startScanning(_uiState.value.scanTarget)
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
