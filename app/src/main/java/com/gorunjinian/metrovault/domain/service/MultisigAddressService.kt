package com.gorunjinian.metrovault.domain.service

import android.util.Log
import com.gorunjinian.metrovault.data.model.BitcoinAddress
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.data.model.MultisigScriptType
import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.lib.bitcoin.*
import com.gorunjinian.metrovault.lib.bitcoin.io.readNBytes

/**
 * Service for generating and verifying multisig addresses.
 * 
 * Generates P2WSH (and P2SH-P2WSH) addresses from multisig configurations.
 * Uses sortedmulti semantics: public keys are sorted lexicographically before
 * building the witness script.
 */
class MultisigAddressService {

    companion object {
        private const val TAG = "MultisigAddressService"
        private const val ADDRESS_SCAN_GAP = 500
    }

    /**
     * Result of address generation.
     */
    sealed class MultisigAddressResult {
        data class Success(val address: BitcoinAddress) : MultisigAddressResult()
        data class Error(val message: String) : MultisigAddressResult()
    }

    /**
     * Generates a multisig address at the specified index.
     * 
     * @param config The multisig configuration with all cosigner xpubs
     * @param index Address index
     * @param isChange Whether this is a change address (1) or receive address (0)
     * @param isTestnet Whether to generate testnet addresses
     * @return MultisigAddressResult with the generated address or error
     */
    fun generateMultisigAddress(
        config: MultisigConfig,
        index: Int,
        isChange: Boolean,
        isTestnet: Boolean
    ): MultisigAddressResult {
        return try {
            val chainHash = BitcoinUtils.getChainHash(isTestnet)
            val changeIndex = if (isChange) 1L else 0L
            
            // Derive child public keys from each cosigner's xpub
            val childPubKeys = config.cosigners.mapNotNull { cosigner ->
                deriveChildPublicKey(cosigner.xpub, changeIndex, index.toLong(), isTestnet)
            }
            
            if (childPubKeys.size != config.n) {
                return MultisigAddressResult.Error(
                    "Failed to derive keys from all cosigners (got ${childPubKeys.size}/${config.n})"
                )
            }
            
            // Sort public keys lexicographically (sortedmulti semantics)
            val sortedPubKeys = childPubKeys.sortedBy { it.toHex() }
            
            // Build witness script: OP_m <pubkey1> <pubkey2> ... OP_n OP_CHECKMULTISIG
            val witnessScript = buildMultisigScript(config.m, sortedPubKeys)
            
            // Generate address based on script type
            val address = when (config.scriptType) {
                MultisigScriptType.P2WSH -> {
                    // Native SegWit: P2WSH address from witness script hash
                    computeP2wshAddress(witnessScript, chainHash)
                }
                MultisigScriptType.P2SH_P2WSH -> {
                    // Nested SegWit: P2SH address wrapping P2WSH
                    computeP2shP2wshAddress(witnessScript, chainHash)
                }
                MultisigScriptType.P2SH -> {
                    // Legacy multisig: P2SH address from redeem script
                    computeP2shMultisigAddress(config.m, sortedPubKeys, chainHash)
                }
            }
            
            if (address == null) {
                return MultisigAddressResult.Error("Failed to compute multisig address")
            }
            
            val scriptType = when (config.scriptType) {
                MultisigScriptType.P2WSH -> ScriptType.P2WPKH // Closest match for display
                MultisigScriptType.P2SH_P2WSH -> ScriptType.P2SH_P2WPKH
                MultisigScriptType.P2SH -> ScriptType.P2PKH
            }
            
            MultisigAddressResult.Success(
                BitcoinAddress(
                    address = address,
                    derivationPath = "multisig/$changeIndex/$index",
                    index = index,
                    isChange = isChange,
                    publicKey = "", // Multisig doesn't have single public key
                    scriptType = scriptType
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate multisig address: ${e.message}", e)
            MultisigAddressResult.Error("Failed to generate address: ${e.message}")
        }
    }

    /**
     * Checks if an address belongs to the multisig wallet.
     */
    fun checkAddressBelongsToMultisig(
        address: String,
        config: MultisigConfig,
        isTestnet: Boolean,
        scanRange: Int = ADDRESS_SCAN_GAP
    ): AddressCheckResult {
        // Check receive addresses
        for (i in 0 until scanRange) {
            val result = generateMultisigAddress(config, i, isChange = false, isTestnet)
            if (result is MultisigAddressResult.Success && result.address.address == address) {
                return AddressCheckResult(true, result.address.derivationPath, i, false)
            }
        }
        
        // Check change addresses
        for (i in 0 until scanRange) {
            val result = generateMultisigAddress(config, i, isChange = true, isTestnet)
            if (result is MultisigAddressResult.Success && result.address.address == address) {
                return AddressCheckResult(true, result.address.derivationPath, i, true)
            }
        }
        
        return AddressCheckResult(false, null, null, null)
    }

    // ==================== Private Helpers ====================

    /**
     * Derives a child public key from an extended public key string.
     * The xpub should already be at the account level (e.g., m/48'/0'/0'/2').
     * We then derive: xpub / changeIndex / addressIndex
     * 
     * Note: This uses raw BIP32 derivation to avoid ExtendedPublicKey constructor validation
     * issues with descriptor xpubs that have inconsistent depth metadata.
     */
    private fun deriveChildPublicKey(
        xpubString: String,
        changeIndex: Long,
        addressIndex: Long,
        @Suppress("UNUSED_PARAMETER") isTestnet: Boolean
    ): PublicKey? {
        return try {
            // Extract raw key data from xpub
            val keyData = decodeXpubRaw(xpubString) ?: return null
            
            // Derive: xpub / changeIndex / addressIndex using raw BIP32
            val level1 = deriveChildKeyRaw(keyData.publicKeyBytes, keyData.chaincode, changeIndex)
                ?: return null
            val level2 = deriveChildKeyRaw(level1.first, level1.second, addressIndex)
                ?: return null
            
            PublicKey(level2.first.byteVector())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to derive child key from xpub: ${e.message}")
            null
        }
    }
    
    /**
     * Raw xpub data without the validation-problematic ExtendedPublicKey wrapper.
     */
    private data class RawXpubData(
        val publicKeyBytes: ByteArray,
        val chaincode: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as RawXpubData

            if (!publicKeyBytes.contentEquals(other.publicKeyBytes)) return false
            if (!chaincode.contentEquals(other.chaincode)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = publicKeyBytes.contentHashCode()
            result = 31 * result + chaincode.contentHashCode()
            return result
        }
    }

    /**
     * Decodes an xpub string to raw public key bytes and chaincode.
     * This bypasses ExtendedPublicKey constructor validation entirely.
     */
    private fun decodeXpubRaw(xpubString: String): RawXpubData? {
        val cleanedXpub = xpubString.trim()
        return try {
            val (prefix, bin) = Base58Check.decodeWithIntPrefix(cleanedXpub)
            // Validate it's an xpub-type prefix
            // Includes both single-sig (lowercase) and BIP48 multisig (uppercase) prefixes
            if (!Bip48MultisigPrefixes.isValidXpubPrefix(prefix)) {
                Log.e(TAG, "Invalid xpub prefix: $prefix (hex: ${prefix.toLong().toString(16)})")
                return null
            }
            
            // Parse xpub structure (78 bytes after prefix):
            // depth (1) + parent (4) + childNumber (4) + chaincode (32) + publicKey (33) = 74 bytes
            if (bin.size < 74) {
                Log.e(TAG, "xpub data too short: ${bin.size}")
                return null
            }
            
            val bis = com.gorunjinian.metrovault.lib.bitcoin.io.ByteArrayInput(bin)
            bis.read() // depth - skip
            bis.readNBytes(4) // parent - skip
            bis.readNBytes(4) // childNumber - skip
            val chaincode = bis.readNBytes(32) ?: return null
            val publicKeyBytes = bis.readNBytes(33) ?: return null
            
            // Validate public key is valid
            if (!Crypto.isPubKeyValid(publicKeyBytes)) {
                Log.e(TAG, "Invalid public key in xpub")
                return null
            }
            
            RawXpubData(publicKeyBytes, chaincode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode xpub raw: ${e.message} (len=${cleanedXpub.length})")
            null
        }
    }
    
    /**
     * Performs raw BIP32 public key derivation without using ExtendedPublicKey.
     * Implements the child key derivation formula from BIP32.
     * 
     * @return Pair of (childPublicKey, childChaincode) or null on failure
     */
    private fun deriveChildKeyRaw(
        parentPublicKey: ByteArray,
        parentChaincode: ByteArray,
        index: Long
    ): Pair<ByteArray, ByteArray>? {
        return try {
            // Cannot derive hardened keys from public keys
            if (DeterministicWallet.isHardened(index)) {
                Log.e(TAG, "Cannot derive hardened key from public key")
                return null
            }
            
            // I = HMAC-SHA512(Key = cpar, Data = serP(Kpar) || ser32(i))
            val data = parentPublicKey + com.gorunjinian.metrovault.lib.bitcoin.crypto.Pack.writeInt32BE(index.toInt())
            val I = Crypto.hmac512(parentChaincode, data)
            
            val IL = I.take(32).toByteArray()
            val IR = I.takeLast(32).toByteArray()
            
            // Validate IL is a valid private key
            if (!Crypto.isPrivKeyValid(IL)) {
                Log.e(TAG, "IL is not a valid private key")
                return null
            }
            
            // Ki = point(parse256(IL)) + Kpar
            val ilPoint = PrivateKey(IL.byteVector32()).publicKey()
            val parentPoint = PublicKey(parentPublicKey.byteVector())
            val childPoint = ilPoint + parentPoint
            
            val childPublicKey = childPoint.value.toByteArray()
            
            // Validate result
            if (!Crypto.isPubKeyValid(childPublicKey)) {
                Log.e(TAG, "Derived child key is invalid")
                return null
            }
            
            Pair(childPublicKey, IR)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to derive child key: ${e.message}")
            null
        }
    }

    /**
     * Maps an integer (1-16) to the corresponding OP_N script element.
     */
    private fun opPushNum(n: Int): ScriptElt {
        return when (n) {
            0 -> OP_0
            1 -> OP_1
            2 -> OP_2
            3 -> OP_3
            4 -> OP_4
            5 -> OP_5
            6 -> OP_6
            7 -> OP_7
            8 -> OP_8
            9 -> OP_9
            10 -> OP_10
            11 -> OP_11
            12 -> OP_12
            13 -> OP_13
            14 -> OP_14
            15 -> OP_15
            16 -> OP_16
            else -> throw IllegalArgumentException("opPushNum only supports 0-16, got $n")
        }
    }

    /**
     * Builds a multisig script: OP_m <pubkey1> <pubkey2> ... OP_n OP_CHECKMULTISIG
     */
    private fun buildMultisigScript(m: Int, sortedPubKeys: List<PublicKey>): List<ScriptElt> {
        val n = sortedPubKeys.size
        return buildList {
            add(opPushNum(m))
            sortedPubKeys.forEach { pubKey ->
                add(OP_PUSHDATA(pubKey.value))
            }
            add(opPushNum(n))
            add(OP_CHECKMULTISIG)
        }
    }

    /**
     * Computes a P2WSH address from a witness script.
     */
    private fun computeP2wshAddress(witnessScript: List<ScriptElt>, chainHash: BlockHash): String? {
        return try {
            // P2WSH: witness program is SHA256 of witness script
            val scriptBytes = Script.write(witnessScript)
            val witnessProgram = Crypto.sha256(scriptBytes)
            
            // Encode as bech32 address
            val hrp = if (chainHash == Block.LivenetGenesisBlock.hash) "bc" else "tb"
            Bech32.encodeWitnessAddress(hrp, 0, witnessProgram)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute P2WSH address: ${e.message}")
            null
        }
    }

    /**
     * Computes a P2SH-P2WSH address (nested SegWit multisig).
     */
    private fun computeP2shP2wshAddress(witnessScript: List<ScriptElt>, chainHash: BlockHash): String? {
        return try {
            // First create P2WSH output script
            val scriptBytes = Script.write(witnessScript)
            val witnessProgram = Crypto.sha256(scriptBytes)
            val p2wshScript = Script.pay2wsh(witnessProgram.byteVector32())
            
            // Then wrap in P2SH
            val redeemScriptBytes = Script.write(p2wshScript)
            val scriptHash = Crypto.hash160(redeemScriptBytes)
            
            val prefix = if (chainHash == Block.LivenetGenesisBlock.hash) {
                Base58.Prefix.ScriptAddress
            } else {
                Base58.Prefix.ScriptAddressTestnet
            }
            
            Base58Check.encode(prefix, scriptHash)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute P2SH-P2WSH address: ${e.message}")
            null
        }
    }

    /**
     * Computes a legacy P2SH multisig address.
     */
    private fun computeP2shMultisigAddress(
        m: Int, 
        sortedPubKeys: List<PublicKey>, 
        chainHash: BlockHash
    ): String? {
        return try {
            // Build redeem script (same as witness script for legacy)
            val redeemScript = buildMultisigScript(m, sortedPubKeys)
            val redeemScriptBytes = Script.write(redeemScript)
            val scriptHash = Crypto.hash160(redeemScriptBytes)
            
            val prefix = if (chainHash == Block.LivenetGenesisBlock.hash) {
                Base58.Prefix.ScriptAddress
            } else {
                Base58.Prefix.ScriptAddressTestnet
            }
            
            Base58Check.encode(prefix, scriptHash)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute P2SH address: ${e.message}")
            null
        }
    }
}
