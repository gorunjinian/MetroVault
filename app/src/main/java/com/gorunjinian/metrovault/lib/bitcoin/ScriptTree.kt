package com.gorunjinian.metrovault.lib.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.io.ByteArrayOutput
import com.gorunjinian.metrovault.lib.bitcoin.io.Input
import com.gorunjinian.metrovault.lib.bitcoin.io.Output
import kotlin.jvm.JvmStatic

/** Simple binary tree structure containing taproot spending scripts. */
sealed class ScriptTree {
    abstract fun write(output: Output, level: Int)

    /**
     * @return the tree serialized with the format defined in BIP 371
     */
    fun write(): ByteArray {
        val output = ByteArrayOutput()
        write(output, 0)
        return output.toByteArray()
    }

    /**
     * Multiple spending scripts can be placed in the leaves of a taproot tree. When using one of those scripts to spend
     * funds, we only need to reveal that specific script and a merkle proof that it is a leaf of the tree.
     *
     * @param script serialized spending script.
     * @param leafVersion tapscript version.
     */
    data class Leaf(val script: ByteVector, val leafVersion: Int) : ScriptTree() {
        constructor(script: List<ScriptElt>) : this(script, Script.TAPROOT_LEAF_TAPSCRIPT)
        constructor(script: List<ScriptElt>, leafVersion: Int) : this(Script.write(script).byteVector(), leafVersion)
        constructor(script: String, leafVersion: Int) : this(ByteVector.fromHex(script), leafVersion)

        override fun write(output: Output, level: Int) {
            output.write(level)
            output.write(leafVersion)
            BtcSerializer.writeScript(script, output)
        }
    }

    data class Branch(val left: ScriptTree, val right: ScriptTree) : ScriptTree() {
        override fun write(output: Output, level: Int) {
            left.write(output, level + 1)
            right.write(output, level + 1)
        }
    }

    /** Compute the merkle root of the script tree. */
    fun hash(): ByteVector32 = when (this) {
        is Leaf -> {
            val buffer = ByteArrayOutput()
            buffer.write(this.leafVersion)
            BtcSerializer.writeScript(this.script, buffer)
            Crypto.taggedHash(buffer.toByteArray(), "TapLeaf")
        }
        is Branch -> {
            val h1 = this.left.hash()
            val h2 = this.right.hash()
            val toHash = if (LexicographicalOrdering.isLessThan(h1, h2)) h1 + h2 else h2 + h1
            Crypto.taggedHash(toHash.toByteArray(), "TapBranch")
        }
    }

    /** Return the first leaf with a matching script, if any. */
    fun findScript(script: ByteVector): Leaf? = when (this) {
        is Leaf -> if (this.script == script) this else null
        is Branch -> this.left.findScript(script) ?: this.right.findScript(script)
    }

    /** Return the first leaf with a matching leaf hash, if any. */
    fun findScript(leafHash: ByteVector32): Leaf? = when (this) {
        is Leaf -> if (this.hash() == leafHash) this else null
        is Branch -> this.left.findScript(leafHash) ?: this.right.findScript(leafHash)
    }

    /**
     * Compute a merkle proof for the given script leaf.
     * This merkle proof is encoded for creating control blocks in taproot script path witnesses.
     * If the leaf doesn't belong to the script tree, this function will return null.
     */
    fun merkleProof(leafHash: ByteVector32): ByteArray? {
        fun loop(tree: ScriptTree, proof: ByteArray): ByteArray? = when (tree) {
            is Leaf -> if (tree.hash() == leafHash) proof else null
            is Branch -> loop(tree.left, tree.right.hash().toByteArray() + proof) ?: loop(tree.right, tree.left.hash().toByteArray() + proof)
        }
        return loop(this, ByteArray(0))
    }

    companion object {
        private fun readLeaves(input: Input): ArrayList<Pair<Int, ScriptTree>> {
            val leaves = arrayListOf<Pair<Int, ScriptTree>>()
            while (input.availableBytes > 0) {
                val depth = input.read()
                val leafVersion = input.read()
                val script = BtcSerializer.script(input).byteVector()
                leaves.add(Pair(depth, Leaf(script, leafVersion)))
            }
            return leaves
        }

        private fun merge(nodes: ArrayList<Pair<Int, ScriptTree>>) {
            if (nodes.size > 1) {
                var i = 0
                while (i < nodes.size - 1) {
                    if (nodes[i].first == nodes[i + 1].first) {
                        // merge 2 consecutive nodes that are on the same level
                        val node = Pair(nodes[i].first - 1, Branch(nodes[i].second, nodes[i + 1].second))
                        nodes[i] = node
                        nodes.removeAt(i + 1)
                        // and start again from the beginning (the node at the bottom-left of the tree)
                        i = 0
                    } else i++
                }
            }
        }

        @JvmStatic
        fun read(input: Input): ScriptTree {
            val leaves = readLeaves(input)
            merge(leaves)
            require(leaves.size == 1) { "invalid serialized script tree" }
            return leaves[0].second
        }
    }
}
