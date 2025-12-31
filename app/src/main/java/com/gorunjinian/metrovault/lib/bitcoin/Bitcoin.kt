package com.gorunjinian.metrovault.lib.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import kotlin.jvm.JvmStatic

const val MaxBlockSize: Int = 1000000

fun <T> List<T>.updated(i: Int, t: T): List<T> = when (i) {
    0 -> listOf(t) + this.drop(1)
    this.lastIndex -> this.dropLast(1) + t
    else -> this.take(i) + t + this.drop(i + 1)
}

sealed class BitcoinError {
    abstract val message: String
    abstract val cause: Throwable?
    override fun toString(): String = when (cause) {
        null -> message
        else -> "$message: ${cause?.message}"
    }

    data object InvalidChainHash : BitcoinError() {
        override val message: String = "invalid chain hash"
        override val cause: Throwable? = null
    }

    data object ChainHashMismatch : BitcoinError() {
        override val message: String = "chain hash mismatch"
        override val cause: Throwable? = null
    }

    data object InvalidScript : BitcoinError() {
        override val message: String = "invalid script"
        override val cause: Throwable? = null
    }

    data object InvalidAddress : BitcoinError() {
        override val message: String = "invalid address"
        override val cause: Throwable? = null
    }

    data object InvalidBech32Address : BitcoinError() {
        override val message: String = "invalid bech32 address"
        override val cause: Throwable? = null
    }

    data class InvalidWitnessVersion(val version: Int) : BitcoinError() {
        override val message: String = "invalid witness version $version"
        override val cause: Throwable? = null
    }

    data class GenericError(override val message: String, override val cause: Throwable?) : BitcoinError()
}

object Bitcoin {
    @JvmStatic
    fun computeP2PkhAddress(pub: PublicKey, chainHash: BlockHash): String = pub.p2pkhAddress(chainHash)

    @JvmStatic
    fun computeBIP44Address(pub: PublicKey, chainHash: BlockHash): String = computeP2PkhAddress(pub, chainHash)

    /**
     * @param pub public key
     * @param chainHash chain hash (i.e. hash of the genesic block of the chain we're on)
     * @return the p2swh-of-p2pkh address for this key. It is a Base58 address that is compatible with most bitcoin wallets
     */
    @JvmStatic
    fun computeP2ShOfP2WpkhAddress(pub: PublicKey, chainHash: BlockHash): String = pub.p2shOfP2wpkhAddress(chainHash)

    @JvmStatic
    fun computeBIP49Address(pub: PublicKey, chainHash: BlockHash): String = computeP2ShOfP2WpkhAddress(pub, chainHash)

    /**
     * @param pub public key
     * @param chainHash chain hash (i.e. hash of the genesis block of the chain we're on)
     * @return the BIP84 address for this key (i.e. the p2wpkh address for this key). It is a Bech32 address that will be
     *         understood only by native segwit wallets
     */
    @JvmStatic
    fun computeP2WpkhAddress(pub: PublicKey, chainHash: BlockHash): String = pub.p2wpkhAddress(chainHash)

    @JvmStatic
    fun computeBIP86Address(pub: XonlyPublicKey, chainHash: BlockHash): String = pub.p2trAddress(chainHash)

    /**
     * Compute an address from a public key script
     * @param chainHash chain hash (i.e. hash of the genesis block of the chain we're on)
     * @param pubkeyScript public key script
     */
    @JvmStatic
    fun addressFromPublicKeyScript(chainHash: BlockHash, pubkeyScript: List<ScriptElt>): Either<BitcoinError, String> {
        try {
            return when {
                Script.isPay2pkh(pubkeyScript) -> {
                    val prefix = when (chainHash) {
                        Block.LivenetGenesisBlock.hash -> Base58.Prefix.PubkeyAddress
                        Block.Testnet4GenesisBlock.hash, Block.Testnet3GenesisBlock.hash, Block.RegtestGenesisBlock.hash, Block.SignetGenesisBlock.hash -> Base58.Prefix.PubkeyAddressTestnet
                        else -> return Either.Left(BitcoinError.InvalidChainHash)
                    }
                    Either.Right(Base58Check.encode(prefix, (pubkeyScript[2] as OP_PUSHDATA).data))
                }

                Script.isPay2sh(pubkeyScript) -> {
                    val prefix = when (chainHash) {
                        Block.LivenetGenesisBlock.hash -> Base58.Prefix.ScriptAddress
                        Block.Testnet4GenesisBlock.hash, Block.Testnet3GenesisBlock.hash, Block.RegtestGenesisBlock.hash, Block.SignetGenesisBlock.hash -> Base58.Prefix.ScriptAddressTestnet
                        else -> return Either.Left(BitcoinError.InvalidChainHash)
                    }
                    Either.Right(Base58Check.encode(prefix, (pubkeyScript[1] as OP_PUSHDATA).data))
                }

                Script.isNativeWitnessScript(pubkeyScript) -> {
                    val hrp = Bech32.hrp(chainHash)
                    val witnessScript = (pubkeyScript[1] as OP_PUSHDATA).data.toByteArray()
                    val versionOp = pubkeyScript[0]

                    // Use Script.isSimpleValue() and Script.simpleValue() to extract witness version
                    when {
                        versionOp == OP_0 -> when {
                            Script.isPay2wpkh(pubkeyScript) || Script.isPay2wsh(pubkeyScript) ->
                                Either.Right(Bech32.encodeWitnessAddress(hrp, 0, witnessScript))
                            else -> return Either.Left(BitcoinError.InvalidScript)
                        }
                        Script.isSimpleValue(versionOp) -> {
                            val version = Script.simpleValue(versionOp).toInt()
                            if (version in 1..16) {
                                Either.Right(Bech32.encodeWitnessAddress(hrp, version.toByte(), witnessScript))
                            } else {
                                return Either.Left(BitcoinError.InvalidScript)
                            }
                        }
                        else -> return Either.Left(BitcoinError.InvalidScript)
                    }
                }

                else -> return Either.Left(BitcoinError.InvalidScript)
            }
        } catch (t: Throwable) {
            return Either.Left(BitcoinError.GenericError("", t))
        }
    }

    @JvmStatic
    fun addressFromPublicKeyScript(chainHash: BlockHash, pubkeyScript: ByteArray): Either<BitcoinError, String> {
        return runCatching { Script.parse(pubkeyScript) }.fold(
            onSuccess = {
                addressFromPublicKeyScript(chainHash, it)
            },
            onFailure = {
                Either.Left(BitcoinError.InvalidScript)
            }
        )
    }

    @JvmStatic
    fun addressToPublicKeyScript(chainHash: BlockHash, address: String): Either<BitcoinError, List<ScriptElt>> {
        return runCatching { Base58Check.decode(address) }.fold(
            onSuccess = {
                when (it.first) {
                    Base58.Prefix.PubkeyAddressTestnet if (chainHash == Block.Testnet4GenesisBlock.hash || chainHash == Block.Testnet3GenesisBlock.hash || chainHash == Block.RegtestGenesisBlock.hash || chainHash == Block.SignetGenesisBlock.hash) ->
                        Either.Right(Script.pay2pkh(it.second))

                    Base58.Prefix.PubkeyAddress if chainHash == Block.LivenetGenesisBlock.hash ->
                        Either.Right(Script.pay2pkh(it.second))

                    Base58.Prefix.ScriptAddressTestnet if (chainHash == Block.Testnet4GenesisBlock.hash || chainHash == Block.Testnet3GenesisBlock.hash || chainHash == Block.RegtestGenesisBlock.hash || chainHash == Block.SignetGenesisBlock.hash) ->
                        Either.Right(listOf(OP_HASH160, OP_PUSHDATA(it.second), OP_EQUAL))

                    Base58.Prefix.ScriptAddress if chainHash == Block.LivenetGenesisBlock.hash ->
                        Either.Right(listOf(OP_HASH160, OP_PUSHDATA(it.second), OP_EQUAL))

                    else -> Either.Left(BitcoinError.ChainHashMismatch)
                }
            },
            onFailure = { _ ->
                runCatching { Bech32.decodeWitnessAddress(address) }.fold(
                    onSuccess = { decoded ->
                        val versionByte = decoded.second
                        // Use Script.fromSimpleValue() to convert witness version to OP code
                        val witnessVersion = if (versionByte in 0..16) {
                            Script.fromSimpleValue(versionByte)
                        } else {
                            return@fold Either.Left(BitcoinError.InvalidWitnessVersion(versionByte.toInt()))
                        }

                        when {
                            decoded.third.size != 20 && decoded.third.size != 32 -> Either.Left(BitcoinError.InvalidBech32Address)
                            decoded.first == "bc" && chainHash == Block.LivenetGenesisBlock.hash -> Either.Right(listOf(witnessVersion, OP_PUSHDATA(decoded.third)))
                            decoded.first == "tb" && chainHash == Block.Testnet4GenesisBlock.hash -> Either.Right(listOf(witnessVersion, OP_PUSHDATA(decoded.third)))
                            decoded.first == "tb" && chainHash == Block.Testnet3GenesisBlock.hash -> Either.Right(listOf(witnessVersion, OP_PUSHDATA(decoded.third)))
                            decoded.first == "tb" && chainHash == Block.SignetGenesisBlock.hash -> Either.Right(listOf(witnessVersion, OP_PUSHDATA(decoded.third)))
                            decoded.first == "bcrt" && chainHash == Block.RegtestGenesisBlock.hash -> Either.Right(listOf(witnessVersion, OP_PUSHDATA(decoded.third)))
                            else -> Either.Left(BitcoinError.ChainHashMismatch)
                        }
                    },
                    onFailure = {
                        Either.Left(BitcoinError.InvalidAddress)
                    }
                )
            }
        )
    }
}
