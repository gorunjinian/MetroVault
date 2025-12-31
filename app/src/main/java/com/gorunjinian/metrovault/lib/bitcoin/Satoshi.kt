package com.gorunjinian.metrovault.lib.bitcoin

data class Satoshi(val sat: Long) : Comparable<Satoshi> {
    // @formatter:off
    operator fun plus(other: Satoshi): Satoshi = Satoshi(sat + other.sat)
    operator fun minus(other: Satoshi): Satoshi = Satoshi(sat - other.sat)
    operator fun times(m: Int): Satoshi = Satoshi(sat * m)
    operator fun times(m: Long): Satoshi = Satoshi(sat * m)
    operator fun times(m: Double): Satoshi = Satoshi((sat * m).toLong())
    operator fun div(d: Int): Satoshi = Satoshi(sat / d)
    operator fun div(d: Long): Satoshi = Satoshi(sat / d)
    operator fun unaryMinus(): Satoshi = Satoshi(-sat)
    override fun compareTo(other: Satoshi): Int = sat.compareTo(other.sat)

    fun toLong(): Long = sat

    fun toULong(): ULong = sat.toULong()
    override fun toString(): String = "$sat sat"
    // @formatter:on

    companion object {
        const val COIN: Long = 100_000_000L
        val MAX_MONEY: Satoshi = Satoshi(21_000_000L * COIN)
    }
}

fun Long.toSatoshi(): Satoshi = Satoshi(this)
