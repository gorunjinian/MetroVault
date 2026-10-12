package com.gorunjinian.metrovault.data.repository

import com.gorunjinian.metrovault.data.model.WalletMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The observable wallet list: every mutation publishes a fresh snapshot through `wallets`, keeps
 * display order, and stays atomic when several threads write at once.
 */
class WalletListStoreTest {

    private val store = WalletListStore()

    private fun wallet(id: String, name: String = "Wallet $id") = WalletMetadata(
        id = id,
        name = name,
        derivationPath = "m/84'/0'/0'",
        masterFingerprint = "73c5da0a",
        hasPassphrase = false,
        createdAt = 0L
    )

    private fun ids() = store.wallets.value.map { it.id }

    @Test
    fun startsEmpty() {
        assertTrue(store.wallets.value.isEmpty())
        assertTrue(store.current.isEmpty())
        assertNull(store.find("a"))
    }

    @Test
    fun replaceAllPublishesACopyOfTheGivenList() {
        val source = mutableListOf(wallet("a"), wallet("b"))

        store.replaceAll(source)
        source.add(wallet("c"))

        assertEquals(listOf("a", "b"), ids())
    }

    @Test
    fun addAppendsInDisplayOrder() {
        store.add(wallet("a"))
        store.add(wallet("b"))

        assertEquals(listOf("a", "b"), ids())
        assertEquals("Wallet b", store.find("b")?.name)
    }

    @Test
    fun replaceSwapsInTheUpdatedWalletAtTheSamePosition() {
        store.replaceAll(listOf(wallet("a"), wallet("b"), wallet("c")))

        store.replace(wallet("b", name = "Renamed"))

        assertEquals(listOf("a", "b", "c"), ids())
        assertEquals("Renamed", store.find("b")?.name)
    }

    @Test
    fun replaceOfAnUnlistedWalletChangesNothing() {
        store.replaceAll(listOf(wallet("a")))
        val before = store.wallets.value

        store.replace(wallet("missing"))

        assertEquals(before, store.wallets.value)
        assertNull(store.find("missing"))
    }

    @Test
    fun removeDropsOnlyThatWallet() {
        store.replaceAll(listOf(wallet("a"), wallet("b"), wallet("c")))

        store.remove("b")

        assertEquals(listOf("a", "c"), ids())
    }

    @Test
    fun swapExchangesTwoPositions() {
        store.replaceAll(listOf(wallet("a"), wallet("b"), wallet("c")))

        assertTrue(store.swap(0, 2))

        assertEquals(listOf("c", "b", "a"), ids())
    }

    @Test
    fun swapOutOfRangeReportsFalseAndChangesNothing() {
        store.replaceAll(listOf(wallet("a"), wallet("b")))
        val before = store.wallets.value

        assertFalse(store.swap(0, 2))
        assertFalse(store.swap(-1, 0))

        assertSame(before, store.wallets.value)
    }

    @Test
    fun clearEmptiesTheList() {
        store.replaceAll(listOf(wallet("a"), wallet("b")))

        store.clear()

        assertTrue(store.wallets.value.isEmpty())
    }

    @Test
    fun aSnapshotIsUnaffectedByLaterMutations() {
        store.replaceAll(listOf(wallet("a")))
        val snapshot = store.current

        store.add(wallet("b"))

        assertEquals(listOf("a"), snapshot.map { it.id })
    }

    @Test
    fun concurrentWritersNeverLoseAnUpdate() {
        val threads = 8
        val perThread = 250
        store.replaceAll(List(threads) { wallet("seed-$it", name = "0") })
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)

        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i ->
                    store.add(wallet("t$t-$i"))
                    // Read-modify-write on this thread's own entry; a lost update would leave a gap
                    val current = store.find("seed-$t")!!
                    store.replace(current.copy(name = (current.name.toInt() + 1).toString()))
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        assertEquals(threads + threads * perThread, store.current.size)
        repeat(threads) { t ->
            assertEquals(perThread.toString(), store.find("seed-$t")?.name)
        }
    }
}
