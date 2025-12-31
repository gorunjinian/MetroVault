package com.gorunjinian.metrovault.lib.bitcoin

import kotlin.jvm.JvmField

/**
 * Double SHA-256 hash of a serialized block header.
 * Used as network identifier - the genesis block hash identifies which chain we're on.
 */
data class BlockHash(@JvmField val value: ByteVector32) {
    constructor(hash: ByteArray) : this(hash.byteVector32())
    constructor(hash: String) : this(ByteVector32(hash))

    override fun toString(): String = value.toString()
}

/**
 * Pre-computed genesis block hashes for supported Bitcoin networks.
 * Used for address generation (determining prefixes) and chain validation.
 *
 * Hash values are stored in little-endian format (as returned by double SHA-256).
 */
object Block {
    /** Bitcoin Mainnet genesis block hash */
    @JvmField
    val LivenetGenesisBlock = GenesisBlockHash(
        // Block 0: 000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f
        "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000"
    )

    /** Bitcoin Testnet3 genesis block hash */
    @JvmField
    val Testnet3GenesisBlock = GenesisBlockHash(
        // Block 0: 000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943
        "43497fd7f826957108f4a30fd9cec3aeba79972084e90ead01ea330900000000"
    )

    /** Bitcoin Testnet4 genesis block hash */
    @JvmField
    val Testnet4GenesisBlock = GenesisBlockHash(
        // Block 0: 00000000da84f2bafbbc53dee25a72ae507ff4914b867c565be350b0da8bf043
        "43f08bdab050e35b567c864b91f47f50ae722ae5de53bcfbbaf284da00000000"
    )

    /** Bitcoin Regtest genesis block hash */
    @JvmField
    val RegtestGenesisBlock = GenesisBlockHash(
        // Block 0: 0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206
        "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"
    )

    /** Bitcoin Signet genesis block hash */
    @JvmField
    val SignetGenesisBlock = GenesisBlockHash(
        // Block 0: 00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3264d9f94a5
        "a5949f4d26a380a477a063af32b2bbc97c9ff9f01f2c4225e973988108000000"
    )

    /**
     * Minimal wrapper providing just the hash property needed for chain identification.
     * Replaces full Block construction which is unnecessary for an offline signing device.
     */
    class GenesisBlockHash(hashHex: String) {
        @JvmField
        val hash: BlockHash = BlockHash(hashHex)
    }
}
