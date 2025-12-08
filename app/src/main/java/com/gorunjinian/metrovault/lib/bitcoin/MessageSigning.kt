package com.gorunjinian.metrovault.lib.bitcoin

import android.util.Base64
import kotlin.jvm.JvmStatic

/**
 * Bitcoin Message Signing (BIP-137 style)
 * 
 * Implements the standard "Bitcoin Signed Message" format used by Bitcoin Core,
 * Electrum, and other wallets for signing arbitrary messages with private keys.
 * 
 * The signature format is a 65-byte recoverable ECDSA signature:
 * - Byte 0: Recovery ID header (27-34)
 * - Bytes 1-32: r component
 * - Bytes 33-64: s component
 * 
 * The header byte encodes:
 * - 27-30: uncompressed public key
 * - 31-34: compressed public key
 * 
 * This implementation uses compressed public keys (header 31-34).
 */
object MessageSigning {
    private const val MAGIC = "Bitcoin Signed Message:\n"
    
    /**
     * Signature format types.
     * - ELECTRUM: Standard format used by Electrum, uses header 31-34 for all address types (compressed P2PKH style)
     * - BIP137: Uses different header ranges based on address type (35-38 for P2SH-P2WPKH, 39-42 for P2WPKH)
     */
    enum class SignatureFormat(val displayName: String) {
        ELECTRUM("Electrum"),
        BIP137("BIP-137")
    }
    
    // Header byte base values for BIP137
    private const val HEADER_UNCOMPRESSED_P2PKH = 27
    private const val HEADER_COMPRESSED_P2PKH = 31
    private const val HEADER_P2SH_P2WPKH = 35
    private const val HEADER_P2WPKH = 39
    
    /**
     * Format a message for signing according to Bitcoin message signing standard.
     * 
     * Format: varint(len(magic)) + magic + varint(len(message)) + message
     * Then double SHA256 hash the result.
     * 
     * @param message The message to format
     * @return The double SHA256 hash ready for signing
     */
    @JvmStatic
    fun formatMessageForSigning(message: String): ByteArray {
        val magicBytes = MAGIC.toByteArray(Charsets.UTF_8)
        val messageBytes = message.toByteArray(Charsets.UTF_8)
        
        // Build the formatted message with varint lengths
        val buffer = mutableListOf<Byte>()
        
        // Add magic length as varint and magic bytes
        writeVarInt(buffer, magicBytes.size)
        buffer.addAll(magicBytes.toList())
        
        // Add message length as varint and message bytes
        writeVarInt(buffer, messageBytes.size)
        buffer.addAll(messageBytes.toList())
        
        // Double SHA256 hash
        return Crypto.hash256(buffer.toByteArray())
    }
    
    /**
     * Sign a message with a private key.
     * 
     * @param message The message to sign
     * @param privateKey The private key to sign with
     * @param format The signature format to use (Electrum or BIP137)
     * @param addressType Optional address type for BIP137 format (null = P2PKH, used for header byte selection)
     * @return Base64-encoded signature (65 bytes: 1 header + 32 r + 32 s)
     */
    @JvmStatic
    fun signMessage(
        message: String, 
        privateKey: PrivateKey,
        format: SignatureFormat = SignatureFormat.ELECTRUM,
        addressType: AddressType = AddressType.P2WPKH
    ): String {
        val hash = formatMessageForSigning(message)
        
        // Sign and get the compact signature
        val signature = Crypto.sign(hash, privateKey)
        
        // We need to find the recovery ID by trying both and seeing which recovers our public key
        val expectedPubKey = privateKey.publicKey()
        
        var recoveryId = -1
        for (recid in 0..3) {
            try {
                val recovered = Crypto.recoverPublicKey(signature, hash, recid)
                if (recovered == expectedPubKey) {
                    recoveryId = recid
                    break
                }
            } catch (_: Exception) {
                // Try next recovery id
            }
        }
        
        require(recoveryId >= 0) { "Could not find recovery ID for signature" }
        
        // Build the 65-byte signature: header + r + s
        // Header byte depends on format and address type
        val headerBase = when (format) {
            SignatureFormat.ELECTRUM -> HEADER_COMPRESSED_P2PKH  // Always 31-34 for Electrum
            SignatureFormat.BIP137 -> when (addressType) {
                AddressType.P2PKH -> HEADER_COMPRESSED_P2PKH    // 31-34 for P2PKH
                AddressType.P2SH_P2WPKH -> HEADER_P2SH_P2WPKH   // 35-38 for nested SegWit
                AddressType.P2WPKH -> HEADER_P2WPKH             // 39-42 for native SegWit
                AddressType.P2TR -> HEADER_P2WPKH               // Use P2WPKH range for Taproot (not standardized)
            }
        }
        
        val header = (headerBase + recoveryId).toByte()
        val sigBytes = ByteArray(65)
        sigBytes[0] = header
        System.arraycopy(signature.toByteArray(), 0, sigBytes, 1, 64)
        
        return Base64.encodeToString(sigBytes, Base64.NO_WRAP)
    }
    
    /**
     * Address types for BIP137 header byte selection.
     */
    enum class AddressType {
        P2PKH,        // Legacy (1...)
        P2SH_P2WPKH,  // Nested SegWit (3...)
        P2WPKH,       // Native SegWit (bc1q...)
        P2TR          // Taproot (bc1p...)
    }
    
    /**
     * Verify a signed message.
     * 
     * @param message The original message
     * @param signatureBase64 The Base64-encoded signature
     * @param address The expected Bitcoin address
     * @param chainHash The chain (mainnet, testnet, etc.)
     * @return true if the signature is valid for the given address
     */
    @JvmStatic
    fun verifyMessage(
        message: String,
        signatureBase64: String,
        address: String,
        chainHash: BlockHash
    ): Boolean {
        return try {
            val sigBytes = Base64.decode(signatureBase64, Base64.DEFAULT)
            
            if (sigBytes.size != 65) {
                return false
            }
            
            val header = sigBytes[0].toInt() and 0xFF
            
            // Determine if compressed and extract recovery ID
            val (compressed, recoveryId) = when (header) {
                in 27..30 -> false to (header - 27)
                in 31..34 -> true to (header - 31)
                else -> return false // Invalid header
            }
            
            // Extract r and s components
            val compactSig = ByteVector64(sigBytes.sliceArray(1..64))
            
            // Hash the message
            val hash = formatMessageForSigning(message)
            
            // Recover the public key
            val recoveredPubKey = Crypto.recoverPublicKey(compactSig, hash, recoveryId)
            
            // Get the address from the recovered public key based on address format
            val derivedAddress = when {
                // Native SegWit (bc1q...)
                address.startsWith("bc1q") || address.startsWith("tb1q") || address.startsWith("bcrt1q") -> {
                    recoveredPubKey.p2wpkhAddress(chainHash)
                }
                // Taproot (bc1p...) - not typically used for message signing, but support it
                address.startsWith("bc1p") || address.startsWith("tb1p") || address.startsWith("bcrt1p") -> {
                    recoveredPubKey.xOnly().p2trAddress(chainHash)
                }
                // Nested SegWit (3... or 2...)
                address.startsWith("3") || address.startsWith("2") -> {
                    recoveredPubKey.p2shOfP2wpkhAddress(chainHash)
                }
                // Legacy P2PKH (1... or m/n...)
                else -> {
                    recoveredPubKey.p2pkhAddress(chainHash)
                }
            }
            
            derivedAddress == address
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Write a Bitcoin-style variable-length integer to a buffer.
     */
    private fun writeVarInt(buffer: MutableList<Byte>, value: Int) {
        when {
            value < 0xFD -> {
                buffer.add(value.toByte())
            }
            value <= 0xFFFF -> {
                buffer.add(0xFD.toByte())
                buffer.add((value and 0xFF).toByte())
                buffer.add(((value shr 8) and 0xFF).toByte())
            }
            else -> {
                buffer.add(0xFE.toByte())
                buffer.add((value and 0xFF).toByte())
                buffer.add(((value shr 8) and 0xFF).toByte())
                buffer.add(((value shr 16) and 0xFF).toByte())
                buffer.add(((value shr 24) and 0xFF).toByte())
            }
        }
    }
}
