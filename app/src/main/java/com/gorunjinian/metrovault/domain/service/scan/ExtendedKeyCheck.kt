package com.gorunjinian.metrovault.domain.service.scan

import com.gorunjinian.vaultovich.Base58Check
import com.gorunjinian.vaultovich.DeterministicWallet
import com.gorunjinian.vaultovich.DeterministicWallet.hardened
import com.gorunjinian.vaultovich.KeyPath
import com.gorunjinian.vaultovich.ScriptType

/**
 * The open single-sig wallet, as the extended-key check needs it.
 *
 * @property master The master key as loaded, so with the session passphrase if there is one
 * @property accountPath The open account (`m/84'/0'/0'`)
 * @property scriptType The address type the wallet derives
 * @property isTestnet Whether the wallet is on testnet
 */
class KeyCheckWallet(
    val master: DeterministicWallet.ExtendedPrivateKey,
    val accountPath: KeyPath,
    val scriptType: ScriptType,
    val isTestnet: Boolean
)

/**
 * What a scanned extended public key is to the open wallet. A match is always proven by deriving
 * the key from the wallet's seed; the key's origin only says where to look first.
 */
sealed interface KeyOwnership {
    /**
     * The open account's key. [issues] are what would still make a watch-only wallet built from
     * this scan disagree with this one.
     */
    data class WalletAccount(val issues: Set<KeyIssue>) : KeyOwnership

    /**
     * Derived from this wallet's seed at [path], which isn't the open account: another account, a
     * multisig cosigner key, or the master key itself.
     */
    data class SameSeed(val path: KeyPath, val issues: Set<KeyIssue>) : KeyOwnership

    /**
     * Its origin names this wallet's master fingerprint, but [path] gives a different key, and so
     * does every other path checked. A different passphrase doesn't explain it, since that changes
     * the fingerprint too: the origin was mistyped or forged.
     */
    data class ClaimMismatch(val path: KeyPath) : KeyOwnership

    /** Not derived from this wallet's seed at any path checked. */
    data object NotOurs : KeyOwnership
}

/** Why a key that is ours would still not give the addresses or PSBTs this wallet expects. */
enum class KeyIssue {
    /** Its version bytes are for the other network, so it would give that network's addresses. */
    NETWORK,

    /** Its descriptor or SLIP-132 version names another address type than the path it is at. */
    ADDRESS_TYPE,

    /**
     * Its origin names another fingerprint or path than the one it is at, so PSBTs built from it
     * would point signers at the wrong key.
     */
    ORIGIN,

    /** Its descriptor derives addresses outside the receive (0) and change (1) branches. */
    BRANCH
}

data class KeyCheck(val key: ScannedExtendedKey, val ownership: KeyOwnership)


object ExtendedKeyChecker {

    fun check(keys: List<ScannedExtendedKey>, wallet: KeyCheckWallet): List<KeyCheck> {
        val search = Search(wallet)
        return keys.map { KeyCheck(it, search.ownership(it)) }
    }

    /** One check's derivations, kept for the keys after it: an account export repeats paths. */
    private class Search(private val wallet: KeyCheckWallet) {
        private val masterFingerprint = wallet.master.fingerprint()
        private val derived = HashMap<KeyPath, DeterministicWallet.ExtendedPublicKey>()

        private fun publicKeyAt(path: KeyPath) =
            derived.getOrPut(path) { wallet.master.derivePrivateKey(path).extendedPublicKey }

        fun ownership(key: ScannedExtendedKey): KeyOwnership {
            val scanned = decode(key.xpub) ?: return KeyOwnership.NotOurs
            val origin = origin(key)

            if (scanned.matches(publicKeyAt(wallet.accountPath))) {
                val issues = issues(key, scanned, origin, wallet.accountPath, wallet.scriptType.addressKind())
                return KeyOwnership.WalletAccount(issues)
            }

            val path = candidatePaths(scanned, origin)
                .filter { it != wallet.accountPath }
                .firstOrNull { scanned.matches(publicKeyAt(it)) }
            if (path != null) {
                return KeyOwnership.SameSeed(path, issues(key, scanned, origin, path, standardKind(path)))
            }

            if (origin != null && origin.fingerprint == masterFingerprint) {
                return KeyOwnership.ClaimMismatch(origin.path)
            }
            return KeyOwnership.NotOurs
        }

        private fun issues(
            key: ScannedExtendedKey,
            scanned: DecodedKey,
            origin: Origin?,
            foundAt: KeyPath,
            expectedKind: AddressKind?
        ): Set<KeyIssue> = buildSet {
            if (key.network != expectedNetwork(foundAt)) add(KeyIssue.NETWORK)
            val claimedKind = key.descriptorScript?.addressKind() ?: slip132Kind(scanned.prefix)
            if (expectedKind != null && claimedKind != null && claimedKind != expectedKind) add(KeyIssue.ADDRESS_TYPE)
            if (origin != null && (origin.fingerprint != masterFingerprint || origin.path != foundAt)) add(KeyIssue.ORIGIN)
            val childPath = key.childPath
            if (childPath != null && childPath !in STANDARD_BRANCHES) add(KeyIssue.BRANCH)
        }

        /** The coin type in a BIP-44-style path decides; any other path is on the wallet's network. */
        private fun expectedNetwork(path: KeyPath): ScanNetwork = when (path.path.getOrNull(1)) {
            hardened(0) -> ScanNetwork.MAINNET
            hardened(1) -> ScanNetwork.TESTNET
            else -> if (wallet.isTestnet) ScanNetwork.TESTNET else ScanNetwork.MAINNET
        }
    }

    /** The origin a key claims; a fingerprint with no path claims the master key itself. */
    private class Origin(val fingerprint: Long, val path: KeyPath)

    private fun origin(key: ScannedExtendedKey): Origin? {
        val fingerprint = key.fingerprint?.toLongOrNull(16) ?: return null
        val path = key.derivationPath?.let { text ->
            runCatching { KeyPath(text.replace('H', 'h')) }.getOrNull() ?: return null
        } ?: KeyPath.empty
        return Origin(fingerprint, path)
    }

    private fun candidatePaths(scanned: DecodedKey, origin: Origin?): Sequence<KeyPath> = sequence {
        origin?.let { yield(it.path) }
        val child = scanned.childNumber
        when (scanned.depth) {
            0 -> yield(KeyPath.empty)
            3 -> if (DeterministicWallet.isHardened(child)) {
                for (purpose in SINGLE_SIG_PURPOSES) {
                    for (coin in COIN_TYPES) yield(KeyPath(listOf(hardened(purpose), hardened(coin), child)))
                }
            }
            4 -> if (child == hardened(1) || child == hardened(2)) {
                for (coin in COIN_TYPES) {
                    for (account in 0 until BIP48_ACCOUNTS_SEARCHED) {
                        yield(KeyPath(listOf(hardened(48), hardened(coin), hardened(account.toLong()), child)))
                    }
                }
            }
        }
    }.distinct()

    /** The serialized fields of a scanned key; the classifier has already checked its checksum. */
    private class DecodedKey(
        val prefix: Int,
        val depth: Int,
        val childNumber: Long,
        private val chainCode: ByteArray,
        private val publicKey: ByteArray
    ) {
        fun matches(key: DeterministicWallet.ExtendedPublicKey): Boolean =
            publicKey.contentEquals(key.publickeybytes.toByteArray()) &&
                chainCode.contentEquals(key.chaincode.toByteArray())
    }

    private fun decode(xpub: String): DecodedKey? {
        val (prefix, data) = runCatching { Base58Check.decodeWithIntPrefix(xpub) }.getOrNull() ?: return null
        if (data.size != EXTENDED_KEY_PAYLOAD_SIZE) return null
        val childNumber = (5..8).fold(0L) { acc, i -> (acc shl 8) or (data[i].toLong() and 0xFF) }
        return DecodedKey(
            prefix = prefix,
            depth = data[0].toInt() and 0xFF,
            childNumber = childNumber,
            chainCode = data.copyOfRange(9, 41),
            publicKey = data.copyOfRange(41, 74)
        )
    }

    /** The address a key is for, to compare what a scan claims with where its key sits. */
    private enum class AddressKind { P2PKH, P2SH_P2WPKH, P2WPKH, P2TR, P2SH_P2WSH, P2WSH }

    private fun ScriptType.addressKind(): AddressKind = when (this) {
        ScriptType.P2PKH -> AddressKind.P2PKH
        ScriptType.P2SH_P2WPKH -> AddressKind.P2SH_P2WPKH
        ScriptType.P2WPKH -> AddressKind.P2WPKH
        ScriptType.P2TR -> AddressKind.P2TR
    }

    /** `xpub` and `tpub` name no address type, so they never disagree with one. */
    private fun slip132Kind(prefix: Int): AddressKind? = when (prefix) {
        DeterministicWallet.ypub, DeterministicWallet.upub -> AddressKind.P2SH_P2WPKH
        DeterministicWallet.zpub, DeterministicWallet.vpub -> AddressKind.P2WPKH
        DeterministicWallet.Ypub, DeterministicWallet.Upub -> AddressKind.P2SH_P2WSH
        DeterministicWallet.Zpub, DeterministicWallet.Vpub -> AddressKind.P2WSH
        else -> null
    }

    /** The address type a standard path is for, or null for a path no standard assigns one. */
    private fun standardKind(path: KeyPath): AddressKind? = when (path.path.firstOrNull()) {
        hardened(44) -> AddressKind.P2PKH
        hardened(49) -> AddressKind.P2SH_P2WPKH
        hardened(84) -> AddressKind.P2WPKH
        hardened(86) -> AddressKind.P2TR
        hardened(48) -> when (path.path.getOrNull(3)) {
            hardened(1) -> AddressKind.P2SH_P2WSH
            hardened(2) -> AddressKind.P2WSH
            else -> null
        }
        else -> null
    }

    /** Version bytes excluded: depth, parent, child number, chain code and key. */
    private const val EXTENDED_KEY_PAYLOAD_SIZE = 74

    /** BIP-48 accounts tried for a depth-4 key with no origin. */
    const val BIP48_ACCOUNTS_SEARCHED = 10

    private val SINGLE_SIG_PURPOSES = listOf(84L, 86L, 49L, 44L)
    private val COIN_TYPES = listOf(0L, 1L)
    private val STANDARD_BRANCHES = setOf("0/*", "1/*", "<0;1>/*")
}
