@file:Suppress("ClassName", "LocalVariableName")

package com.gorunjinian.metrovault.lib.bitcoin

import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic

sealed class ScriptElt {
    abstract val code: Int

    fun isPush(size: Int): Boolean = isPush(this, size)

    fun isPush(): Boolean = isPush(this)

    companion object {
        @JvmStatic
        fun isPush(op: ScriptElt): Boolean {
            return when {
                op is OP_PUSHDATA -> true
                else -> false
            }
        }

        @JvmStatic
        fun isPush(op: ScriptElt, size: Int): Boolean {
            return when {
                op is OP_PUSHDATA && op.data.size() == size -> true
                else -> false
            }
        }
    }
}

// @formatter:off
data object OP_0 : ScriptElt() {
    override val code: Int get() = 0x00
}
data object OP_PUSHDATA1 : ScriptElt() {
    override val code: Int get() = 0x4c
}
data object OP_PUSHDATA2 : ScriptElt() {
    override val code: Int get() = 0x4d
}
data object OP_PUSHDATA4 : ScriptElt() {
    override val code: Int get() = 0x4e
}
data object OP_1NEGATE : ScriptElt() {
    override val code: Int get() = 0x4f
}
data object OP_RESERVED : ScriptElt() {
    override val code: Int get() = 0x50
}
data object OP_1 : ScriptElt() {
    override val code: Int get() = 0x51
}
data object OP_2 : ScriptElt() {
    override val code: Int get() = 0x52
}
data object OP_3 : ScriptElt() {
    override val code: Int get() = 0x53
}
data object OP_4 : ScriptElt() {
    override val code: Int get() = 0x54
}
data object OP_5 : ScriptElt() {
    override val code: Int get() = 0x55
}
data object OP_6 : ScriptElt() {
    override val code: Int get() = 0x56
}
data object OP_7 : ScriptElt() {
    override val code: Int get() = 0x57
}
data object OP_8 : ScriptElt() {
    override val code: Int get() = 0x58
}
data object OP_9 : ScriptElt() {
    override val code: Int get() = 0x59
}
data object OP_10 : ScriptElt() {
    override val code: Int get() = 0x5a
}
data object OP_11 : ScriptElt() {
    override val code: Int get() = 0x5b
}
 data object OP_12 : ScriptElt() {
    override val code: Int get() = 0x5c
}
 data object OP_13 : ScriptElt() {
    override val code: Int get() = 0x5d
}
 data object OP_14 : ScriptElt() {
    override val code: Int get() = 0x5e
}
 data object OP_15 : ScriptElt() {
    override val code: Int get() = 0x5f
}
 data object OP_16 : ScriptElt() {
    override val code: Int get() = 0x60
}
 data object OP_NOP : ScriptElt() {
    override val code: Int get() = 0x61
}
data object OP_VER : ScriptElt() {
    override val code: Int get() = 0x62
}
data object OP_IF : ScriptElt() {
    override val code: Int get() = 0x63
}
 data object OP_NOTIF : ScriptElt() {
    override val code: Int get() = 0x64
}
 data object OP_VERIF : ScriptElt() {
    override val code: Int get() = 0x65
}
 data object OP_VERNOTIF : ScriptElt() {
    override val code: Int get() = 0x66
}
 data object OP_ELSE : ScriptElt() {
    override val code: Int get() = 0x67
}
data object OP_ENDIF : ScriptElt() {
    override val code: Int get() = 0x68
}
data object OP_VERIFY : ScriptElt() {
    override val code: Int get() = 0x69
}
data object OP_RETURN : ScriptElt() {
    override val code: Int get() = 0x6a
}
data object OP_TOALTSTACK : ScriptElt() {
    override val code: Int get() = 0x6b
}
data object OP_FROMALTSTACK : ScriptElt() {
    override val code: Int get() = 0x6c
}
data object OP_2DROP : ScriptElt() {
    override val code: Int get() = 0x6d
}
data object OP_2DUP : ScriptElt() {
    override val code: Int get() = 0x6e
}
data object OP_3DUP : ScriptElt() {
    override val code: Int get() = 0x6f
}
data object OP_2OVER : ScriptElt() {
    override val code: Int get() = 0x70
}
data object OP_2ROT : ScriptElt() {
    override val code: Int get() = 0x71
}
data object OP_2SWAP : ScriptElt() {
    override val code: Int get() = 0x72
}
data object OP_IFDUP : ScriptElt() {
    override val code: Int get() = 0x73
}
data object OP_DEPTH : ScriptElt() {
    override val code: Int get() = 0x74
}
data object OP_DROP : ScriptElt() {
    override val code: Int get() = 0x75
}
data object OP_DUP : ScriptElt() {
    override val code: Int get() = 0x76
}
data object OP_NIP : ScriptElt() {
    override val code: Int get() = 0x77
}
data object OP_OVER : ScriptElt() {
    override val code: Int get() = 0x78
}
data object OP_PICK : ScriptElt() {
    override val code: Int get() = 0x79
}
data object OP_ROLL : ScriptElt() {
    override val code: Int get() = 0x7a
}
data object OP_ROT : ScriptElt() {
    override val code: Int get() = 0x7b
}
 data object OP_SWAP : ScriptElt() {
    override val code: Int get() = 0x7c
}
data object OP_TUCK : ScriptElt() {
    override val code: Int get() = 0x7d
}
data object OP_CAT : ScriptElt() {
    override val code: Int get() = 0x7e
}
data object OP_SUBSTR : ScriptElt() {
    override val code: Int get() = 0x7f
}
data object OP_LEFT : ScriptElt() {
    override val code: Int get() = 0x80
}
data object OP_RIGHT : ScriptElt() {
    override val code: Int get() = 0x81
}
data object OP_SIZE : ScriptElt() {
    override val code: Int get() = 0x82
}
data object OP_INVERT : ScriptElt() {
    override val code: Int get() = 0x83
}
data object OP_AND : ScriptElt() {
    override val code: Int get() = 0x84
}
data object OP_OR : ScriptElt() {
    override val code: Int get() = 0x85
}
data object OP_XOR : ScriptElt() {
    override val code: Int get() = 0x86
}
 data object OP_EQUAL : ScriptElt() {
    override val code: Int get() = 0x87
}
 data object OP_EQUALVERIFY : ScriptElt() {
    override val code: Int get() = 0x88
}
 data object OP_RESERVED1 : ScriptElt() {
    override val code: Int get() = 0x89
}
 data object OP_RESERVED2 : ScriptElt() {
    override val code: Int get() = 0x8a
}
 data object OP_1ADD : ScriptElt() {
    override val code: Int get() = 0x8b
}
 data object OP_1SUB : ScriptElt() {
    override val code: Int get() = 0x8c
}
 data object OP_2MUL : ScriptElt() {
    override val code: Int get() = 0x8d
}
 data object OP_2DIV : ScriptElt() {
    override val code: Int get() = 0x8e
}
 data object OP_NEGATE : ScriptElt() {
    override val code: Int get() = 0x8f
}
 data object OP_ABS : ScriptElt() {
    override val code: Int get() = 0x90
}
 data object OP_NOT : ScriptElt() {
    override val code: Int get() = 0x91
}
 data object OP_0NOTEQUAL : ScriptElt() {
    override val code: Int get() = 0x92
}
 data object OP_ADD : ScriptElt() {
    override val code: Int get() = 0x93
}
 data object OP_SUB : ScriptElt() {
    override val code: Int get() = 0x94
}
 data object OP_MUL : ScriptElt() {
    override val code: Int get() = 0x95
}
 data object OP_DIV : ScriptElt() {
    override val code: Int get() = 0x96
}
 data object OP_MOD : ScriptElt() {
    override val code: Int get() = 0x97
}
 data object OP_LSHIFT : ScriptElt() {
    override val code: Int get() = 0x98
}
 data object OP_RSHIFT : ScriptElt() {
    override val code: Int get() = 0x99
}
 data object OP_BOOLAND : ScriptElt() {
    override val code: Int get() = 0x9a
}
 data object OP_BOOLOR : ScriptElt() {
    override val code: Int get() = 0x9b
}
 data object OP_NUMEQUAL : ScriptElt() {
    override val code: Int get() = 0x9c
}
 data object OP_NUMEQUALVERIFY : ScriptElt() {
    override val code: Int get() = 0x9d
}
 data object OP_NUMNOTEQUAL : ScriptElt() {
    override val code: Int get() = 0x9e
}
 data object OP_LESSTHAN : ScriptElt() {
    override val code: Int get() = 0x9f
}
 data object OP_GREATERTHAN : ScriptElt() {
    override val code: Int get() = 0xa0
}
 data object OP_LESSTHANOREQUAL : ScriptElt() {
    override val code: Int get() = 0xa1
}
 data object OP_GREATERTHANOREQUAL : ScriptElt() {
    override val code: Int get() = 0xa2
}
 data object OP_MIN : ScriptElt() {
    override val code: Int get() = 0xa3
}
 data object OP_MAX : ScriptElt() {
    override val code: Int get() = 0xa4
}
 data object OP_WITHIN : ScriptElt() {
    override val code: Int get() = 0xa5
}
 data object OP_RIPEMD160 : ScriptElt() {
    override val code: Int get() = 0xa6
}
 data object OP_SHA1 : ScriptElt() {
    override val code: Int get() = 0xa7
}
 data object OP_SHA256 : ScriptElt() {
    override val code: Int get() = 0xa8
}
 data object OP_HASH160 : ScriptElt() {
    override val code: Int get() = 0xa9
}
 data object OP_HASH256 : ScriptElt() {
    override val code: Int get() = 0xaa
}
 data object OP_CODESEPARATOR : ScriptElt() {
    override val code: Int get() = 0xab
}
 data object OP_CHECKSIG : ScriptElt() {
    override val code: Int get() = 0xac
}
 data object OP_CHECKSIGVERIFY : ScriptElt() {
    override val code: Int get() = 0xad
}
 data object OP_CHECKMULTISIG : ScriptElt() {
    override val code: Int get() = 0xae
}
 data object OP_CHECKMULTISIGVERIFY : ScriptElt() {
    override val code: Int get() = 0xaf
}
 data object OP_NOP1 : ScriptElt() {
    override val code: Int get() = 0xb0
}
 data object OP_CHECKLOCKTIMEVERIFY : ScriptElt() {
    override val code: Int get() = 0xb1
}
 data object OP_CHECKSEQUENCEVERIFY : ScriptElt() {
    override val code: Int get() = 0xb2
}
 data object OP_NOP4 : ScriptElt() {
    override val code: Int get() = 0xb3
}
 data object OP_NOP5 : ScriptElt() {
    override val code: Int get() = 0xb4
}
 data object OP_NOP6 : ScriptElt() {
    override val code: Int get() = 0xb5
}
 data object OP_NOP7 : ScriptElt() {
    override val code: Int get() = 0xb6
}
 data object OP_NOP8 : ScriptElt() {
    override val code: Int get() = 0xb7
}
 data object OP_NOP9 : ScriptElt() {
    override val code: Int get() = 0xb8
}
 data object OP_NOP10 : ScriptElt() {
    override val code: Int get() = 0xb9
}
// Opcode added by BIP 342 (Tapscript)
 data object OP_CHECKSIGADD: ScriptElt() {
    override val code: Int get() = 0xba
}

 data object OP_INVALIDOPCODE : ScriptElt() {
    override val code: Int get() = 0xff
}
// @formatter:on

 data class OP_PUSHDATA(@JvmField val data: ByteVector, @JvmField val opCode: Int) : ScriptElt() {
    override val code: Int get() = opCode

     constructor(data: ByteArray, code: Int) : this(data.byteVector(), code)

     constructor(data: ByteArray) : this(data, codeFromDataLength(data.count()))

     constructor(data: ByteVector) : this(data, codeFromDataLength(data.size()))

     constructor(data: ByteVector32) : this(data, codeFromDataLength(data.size()))

     constructor(Key: PublicKey) : this(Key.value)

    constructor(Key: XonlyPublicKey) : this(Key.value)

    companion object {
        @JvmStatic
         fun codeFromDataLength(length: Int): Int {
            val code = when {
                length < 0x4c -> length
                length < 0xff -> 0x4c
                length < 0xffff -> 0x4d
                else -> 0x4e
            }
            return code
        }

        @JvmStatic
         fun isMinimal(data: ByteArray, code: Int): Boolean {
            return when {
                data.isEmpty() -> code == OP_0.code
                data.size == 1 && data[0] >= 1 && data[0] <= 16 -> code == (OP_1.code).plus(data[0] - 1)
                data.size == 1 && data[0] == 0x81.toByte() -> code == OP_1NEGATE.code
                data.size <= 75 -> code == data.size
                data.size <= 255 -> code == OP_PUSHDATA1.code
                data.size <= 65535 -> code == OP_PUSHDATA2.code
                else -> {
                    true
                }
            }
        }
    }
}

data class OP_INVALID(override val code: Int) : ScriptElt()

 object ScriptEltMapping {
     val elements: List<ScriptElt> = listOf(
        OP_0,
        OP_PUSHDATA1,
        OP_PUSHDATA2,
        OP_PUSHDATA4,
        OP_1NEGATE,
        OP_RESERVED,
        OP_1,
        OP_2,
        OP_3,
        OP_4,
        OP_5,
        OP_6,
        OP_7,
        OP_8,
        OP_9,
        OP_10,
        OP_11,
        OP_12,
        OP_13,
        OP_14,
        OP_15,
        OP_16,
        OP_NOP,
        OP_VER,
        OP_IF,
        OP_NOTIF,
        OP_VERIF,
        OP_VERNOTIF,
        OP_ELSE,
        OP_ENDIF,
        OP_VERIFY,
        OP_RETURN,
        OP_TOALTSTACK,
        OP_FROMALTSTACK,
        OP_2DROP,
        OP_2DUP,
        OP_3DUP,
        OP_2OVER,
        OP_2ROT,
        OP_2SWAP,
        OP_IFDUP,
        OP_DEPTH,
        OP_DROP,
        OP_DUP,
        OP_NIP,
        OP_OVER,
        OP_PICK,
        OP_ROLL,
        OP_ROT,
        OP_SWAP,
        OP_TUCK,
        OP_CAT,
        OP_SUBSTR,
        OP_LEFT,
        OP_RIGHT,
        OP_SIZE,
        OP_INVERT,
        OP_AND,
        OP_OR,
        OP_XOR,
        OP_EQUAL,
        OP_EQUALVERIFY,
        OP_RESERVED1,
        OP_RESERVED2,
        OP_1ADD,
        OP_1SUB,
        OP_2MUL,
        OP_2DIV,
        OP_NEGATE,
        OP_ABS,
        OP_NOT,
        OP_0NOTEQUAL,
        OP_ADD,
        OP_SUB,
        OP_MUL,
        OP_DIV,
        OP_MOD,
        OP_LSHIFT,
        OP_RSHIFT,
        OP_BOOLAND,
        OP_BOOLOR,
        OP_NUMEQUAL,
        OP_NUMEQUALVERIFY,
        OP_NUMNOTEQUAL,
        OP_LESSTHAN,
        OP_GREATERTHAN,
        OP_LESSTHANOREQUAL,
        OP_GREATERTHANOREQUAL,
        OP_MIN,
        OP_MAX,
        OP_WITHIN,
        OP_RIPEMD160,
        OP_SHA1,
        OP_SHA256,
        OP_HASH160,
        OP_HASH256,
        OP_CODESEPARATOR,
        OP_CHECKSIG,
        OP_CHECKSIGVERIFY,
        OP_CHECKMULTISIG,
        OP_CHECKMULTISIGVERIFY,
        OP_NOP1,
        OP_CHECKLOCKTIMEVERIFY,
        OP_CHECKSEQUENCEVERIFY,
        OP_NOP4,
        OP_NOP5,
        OP_NOP6,
        OP_NOP7,
        OP_NOP8,
        OP_NOP9,
        OP_NOP10,
        OP_CHECKSIGADD,
        OP_INVALIDOPCODE
    )
    // code -> ScriptElt
    @JvmField
    val code2elt: Map<Int, ScriptElt> = elements.associateBy { it.code }

    fun name(elt: ScriptElt): String = elt.toString().removePrefix("OP_")

    val name2code: Map<String, Int> = elements.associate { name(it) to it.code }
}