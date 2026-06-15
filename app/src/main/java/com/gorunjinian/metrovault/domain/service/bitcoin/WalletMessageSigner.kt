package com.gorunjinian.metrovault.domain.service.bitcoin

import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.lib.bitcoin.Bip322
import com.gorunjinian.metrovault.lib.bitcoin.Block
import com.gorunjinian.metrovault.lib.bitcoin.BlockHash
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.MessageSigning
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.Psbt
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either

/**
 * Wallet-level message signing and verification — the orchestration behind the Sign/Verify
 * Message screen.
 *
 * This is deliberately a separate layer from the protocol primitives in `lib/bitcoin`:
 * [MessageSigning] (ECDSA Electrum/BIP-137) and [Bip322] operate on bare keys and know nothing
 * about wallets. This object owns everything that requires the active [Wallet]: locating an
 * address on the BIP-32 tree and resolving its private key, dispatching to the right protocol for
 * the selected format, and the BIP-322 *message PSBT* flow — how a watching wallet (Sparrow)
 * requests a message signature from an air-gapped signer, and the only signing path for
 * silent-payment addresses (their one-off key tweak never exists on this device).
 */
object WalletMessageSigner {

    /** Result of signing a scanned BIP-322 message-signing PSBT. */
    data class SignedMessagePsbt(
        val address: String,
        val message: String,
        val signature: String,
        val signedPsbtBase64: String,
    )

    /** Outcome of a signature verification, with the format that matched (for display). */
    data class VerifyOutcome(
        val isValid: Boolean,
        val detectedFormat: MessageSigning.SignatureFormat?,
    )

    /**
     * Sign [message] with the key behind [address], using the selected [format].
     *
     * Silent-payment wallets cannot sign from an address alone: the per-payment key is
     * `d = b_spend + t_k`, and the tweak `t_k` exists only in the watching wallet — the user is
     * pointed to message-PSBT QR (handled by [signMessagePsbt]) instead.
     */
    fun signWithAddress(
        wallet: Wallet,
        address: String,
        message: String,
        format: MessageSigning.SignatureFormat,
        isSilentPayment: Boolean,
    ): Result<String> {
        if (isSilentPayment) {
            return Result.failure(
                Exception(
                    "Silent payment addresses can't be signed from the address alone — the per-payment " +
                    "key tweak only exists in the watching wallet. Open Sign/Verify Message " +
                    "and choose Show QR, then scan that QR here."
                )
            )
        }

        val trimmedAddress = address.trim()
        val addressKey = resolveAddressKey(wallet, trimmedAddress).getOrElse { return Result.failure(it) }

        return try {
            val signature = if (format == MessageSigning.SignatureFormat.BIP322) {
                Bip322.signSimple(trimmedAddress, message, addressKey, chainHashForAddress(trimmedAddress))
            } else {
                MessageSigning.signMessage(message, addressKey, format, addressTypeOf(trimmedAddress))
            }
            Result.success(signature)
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Failed to sign message"))
        }
    }

    /**
     * Verify [signature] over [message] for [address]. BIP-322 verifies against that protocol
     * alone; the ECDSA path auto-detects between Electrum and BIP-137.
     */
    fun verify(
        address: String,
        message: String,
        signature: String,
        format: MessageSigning.SignatureFormat,
    ): VerifyOutcome {
        val trimmedAddress = address.trim()
        val chainHash = chainHashForAddress(trimmedAddress)
        return if (format == MessageSigning.SignatureFormat.BIP322) {
            val valid = Bip322.verifySimple(trimmedAddress, message, signature.trim(), chainHash)
            VerifyOutcome(valid, MessageSigning.SignatureFormat.BIP322.takeIf { valid })
        } else {
            val result = MessageSigning.verifyMessage(message, signature.trim(), trimmedAddress, chainHash)
            VerifyOutcome(result.isValid, result.detectedFormat)
        }
    }

    /**
     * Decode and validate a scanned BIP-322 message-signing PSBT *without signing it*, so the UI
     * can show the request (address + message) and wait for the user's explicit consent to sign.
     * The validation proves the input spends only the virtual `to_spend` recomputed from the
     * declared address and message — nothing real can be spent.
     */
    fun parseMessagePsbtRequest(wallet: Wallet, psbtBase64: String): Result<Bip322.MessagePsbtRequest> {
        val psbtBytes = try {
            android.util.Base64.decode(psbtBase64, android.util.Base64.DEFAULT)
        } catch (_: Exception) {
            return Result.failure(Exception("Could not decode the scanned PSBT"))
        }
        val parsed = (Psbt.read(psbtBytes) as? Either.Right)?.value
            ?: return Result.failure(Exception("Could not parse the scanned PSBT"))

        val request = try {
            Bip322.parseMessagePsbt(parsed, walletChainHash(wallet))
        } catch (e: IllegalArgumentException) {
            return Result.failure(Exception(e.message ?: "Invalid message-signing PSBT"))
        } ?: return Result.failure(
            Exception("The scanned PSBT is a transaction, not a message-signing request — use Sign PSBT instead.")
        )
        return Result.success(request)
    }

    /**
     * Sign a validated BIP-322 message-signing PSBT (Sparrow's Sign Message QR for air-gapped
     * signers) through the regular wallet signing pipeline (which routes SP-tweaked inputs to the
     * receive signer). Returns the extracted BIP-322 signature plus the signed PSBT to show back
     * to the watching wallet.
     */
    fun signMessagePsbt(wallet: Wallet, psbtBase64: String): Result<SignedMessagePsbt> {
        val request = parseMessagePsbtRequest(wallet, psbtBase64).getOrElse { return Result.failure(it) }
        val chainHash = walletChainHash(wallet)

        val signResult = wallet.signPsbt(psbtBase64)
        if (signResult !is Wallet.PsbtSigningResult.Success) {
            val message = (signResult as? Wallet.PsbtSigningResult.Failure)?.message
            return Result.failure(Exception(message ?: "Failed to sign the message PSBT"))
        }

        val signedParsed = (Psbt.read(android.util.Base64.decode(signResult.signedPsbt, android.util.Base64.DEFAULT)) as? Either.Right)?.value
            ?: return Result.failure(Exception("Could not parse the signed PSBT"))
        val signature = Bip322.signatureFromSignedMessagePsbt(signedParsed)
            ?: return Result.failure(Exception("Signing produced no message signature"))
        if (!Bip322.verifySimple(request.address, request.message, signature, chainHash)) {
            return Result.failure(Exception("The produced signature failed verification"))
        }

        return Result.success(SignedMessagePsbt(request.address, request.message, signature, signResult.signedPsbt))
    }

    /**
     * Parse a Sparrow-style signmessage QR code (`signmessage m/84h/0h/0h/0/0 ascii:<message>`),
     * deriving the address from the path with the active wallet's keys.
     *
     * @return Pair of (address, message) or null if parsing fails.
     */
    fun parseSignMessageQr(wallet: Wallet, qrContent: String): Pair<String, String>? {
        return try {
            val content = qrContent.removePrefix("signmessage ").removePrefix("SIGNMESSAGE ")
            val parts = content.split(" ", limit = 2)
            if (parts.size < 2) return null

            val pathString = parts[0]          // e.g., "m/84h/0h/0h/0/0"
            val messageWithFormat = parts[1]   // e.g., "ascii:test"

            val message = when {
                messageWithFormat.startsWith("ascii:", ignoreCase = true) ->
                    messageWithFormat.removePrefix("ascii:").removePrefix("ASCII:")
                messageWithFormat.startsWith("utf8:", ignoreCase = true) ->
                    messageWithFormat.removePrefix("utf8:").removePrefix("UTF8:")
                messageWithFormat.startsWith("hex:", ignoreCase = true) -> {
                    val hex = messageWithFormat.removePrefix("hex:").removePrefix("HEX:")
                    String(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                }
                else -> messageWithFormat // Assume plain text
            }

            val address = deriveAddressFromPath(pathString, wallet) ?: return null
            Pair(address, message)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolve the private key behind one of the active wallet's own addresses by locating it on
     * the BIP-32 tree (receive/change branches up to the gap limit).
     */
    private fun resolveAddressKey(wallet: Wallet, address: String): Result<PrivateKey> {
        val addressCheck = wallet.checkAddressBelongsToWallet(address)
            ?: return Result.failure(Exception("Could not check address"))
        if (!addressCheck.belongs) {
            return Result.failure(Exception("Address does not belong to this wallet"))
        }
        val index = addressCheck.index
            ?: return Result.failure(Exception("Could not determine address index"))
        val isChange = addressCheck.isChange ?: false

        val walletState = wallet.getActiveWalletState()
            ?: return Result.failure(Exception("No active wallet"))
        val masterPrivateKey = walletState.getMasterPrivateKey()
            ?: return Result.failure(Exception("Master private key not available"))

        val addressPrivateKey = masterPrivateKey
            .derivePrivateKey(walletState.derivationPath)
            .derivePrivateKey(if (isChange) 1L else 0L)
            .derivePrivateKey(index.toLong())
        return Result.success(addressPrivateKey.privateKey)
    }

    /**
     * Derive a Bitcoin address from a derivation path string ("m/84h/0h/0h/0/0" or "m/84'/0'/0'/0/0"),
     * choosing the address type from the BIP purpose.
     */
    private fun deriveAddressFromPath(pathString: String, wallet: Wallet): String? {
        return try {
            val walletState = wallet.getActiveWalletState() ?: return null
            val masterPrivateKey = walletState.getMasterPrivateKey() ?: return null

            val normalized = pathString
                .replace("'", "h")
                .removePrefix("m/")

            val pathComponents = normalized.split("/").mapNotNull { component ->
                val isHardened = component.endsWith("h")
                val index = component.removeSuffix("h").toLongOrNull() ?: return@mapNotNull null
                if (isHardened) DeterministicWallet.hardened(index) else index
            }
            if (pathComponents.isEmpty()) return null

            val publicKey = masterPrivateKey.derivePrivateKey(pathComponents).publicKey
            val chainHash = Block.LivenetGenesisBlock.hash

            when (pathComponents.getOrNull(0)) {
                DeterministicWallet.hardened(84) -> publicKey.p2wpkhAddress(chainHash)
                DeterministicWallet.hardened(49) -> publicKey.p2shOfP2wpkhAddress(chainHash)
                DeterministicWallet.hardened(44) -> publicKey.p2pkhAddress(chainHash)
                DeterministicWallet.hardened(86) -> publicKey.xOnly().p2trAddress(chainHash)
                else -> publicKey.p2wpkhAddress(chainHash) // Default to P2WPKH
            }
        } catch (_: Exception) {
            null
        }
    }

    /** The chain of the active wallet, from its derivation path coin type. */
    private fun walletChainHash(wallet: Wallet): BlockHash =
        if (DerivationPaths.isTestnet(wallet.getActiveWalletDerivationPath())) Block.Testnet4GenesisBlock.hash
        else Block.LivenetGenesisBlock.hash

    /** Infer the chain from the address prefix (testnet: tb1…, 2…, m…, n…). */
    private fun chainHashForAddress(address: String): BlockHash = when {
        address.startsWith("tb1") ||
        address.startsWith("2") ||
        address.startsWith("m") ||
        address.startsWith("n") -> Block.Testnet4GenesisBlock.hash
        else -> Block.LivenetGenesisBlock.hash
    }

    /** Address type for the BIP-137 header byte. */
    private fun addressTypeOf(address: String): MessageSigning.AddressType = when {
        address.startsWith("bc1q") || address.startsWith("tb1q") -> MessageSigning.AddressType.P2WPKH
        address.startsWith("bc1p") || address.startsWith("tb1p") -> MessageSigning.AddressType.P2TR
        address.startsWith("3") || address.startsWith("2") -> MessageSigning.AddressType.P2SH_P2WPKH
        else -> MessageSigning.AddressType.P2PKH
    }
}
