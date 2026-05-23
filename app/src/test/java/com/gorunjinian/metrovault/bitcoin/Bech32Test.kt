package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.Bech32
import fr.acinq.secp256k1.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Bech32Test {

    @Test
    fun validChecksums() {
        val inputs = listOf(
            "A12UEL5L",
            "a12uel5l",
            "an83characterlonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1tt5tgs",
            "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw",
            "11qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqc8247j",
            "split1checkupstagehandshakeupstreamerranterredcaperred2y9e3w",
            "?1ezyfcl",
            // Bech32m
            "A1LQFN3A",
            "a1lqfn3a",
            "an83characterlonghumanreadablepartthatcontainsthetheexcludedcharactersbioandnumber11sg7hg6",
            "abcdef1l7aum6echk45nj3s0wdvt2fg8x9yrzpqzd3ryx",
            "11llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllludsr8",
            "split1checkupstagehandshakeupstreamerranterredcaperredlc445v",
            "?1v759aa"
        )
        for (input in inputs) {
            val (hrp1, data1, enc1) = Bech32.decode(input)
            val (hrp2, data2, enc2) = Bech32.decode(input.dropLast(6), noChecksum = true)
            assertEquals(hrp1, hrp2)
            assertArrayEquals(data1, data2)
            assertEquals(Bech32.Encoding.Beck32WithoutChecksum, enc2)
            assertEquals(input.lowercase(), Bech32.encode(hrp1, data1, enc1))
            assertEquals(input.lowercase().dropLast(6), Bech32.encode(hrp2, data2, enc2))
        }
    }

    @Test
    fun invalidChecksumsAreRejected() {
        val inputs = listOf(
            " 1nwldj5",
            "1axkwrx",
            "1eym55h",
            "an84characterslonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1569pvx",
            "pzry9x0s0muk",
            "1pzry9x0s0muk",
            "x1b4n0q5v",
            "li1dgmt3",
            "de1lg7wtÿ",
            "A1G7SGD8",
            "10a06t8",
            "1qzzfhee",
            // Bech32m
            " 1xj0phk",
            "1g6xzxy",
            "1vctc34",
            "an84characterslonghumanreadablepartthatcontainsthetheexcludedcharactersbioandnumber11d6pts4",
            "qyrz8wqd2c9m",
            "1qyrz8wqd2c9m",
            "y1b0jsk6g",
            "lt1igcx5c0",
            "in1muywd",
            "mm1crxm3i",
            "au1s5cgom",
            "M1VUXWEZ",
            "16plkw9",
            "1p2gdwpf"
        )
        for (input in inputs) {
            assertThrows { Bech32.decodeWitnessAddress(input) }
        }
    }

    @Test
    fun decodeStandardSegwitAddresses() {
        val inputs = listOf(
            "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4" to "0014751e76e8199196d454941c45d1b3a323f1433bd6",
            "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7" to "00201863143c14c5166804bd19203356da136c985678cd4d27a1b8c6329604903262",
            "bc1pw508d6qejxtdg4y5r3zarvary0c5xw7kw508d6qejxtdg4y5r3zarvary0c5xw7kt5nd6y" to "5128751e76e8199196d454941c45d1b3a323f1433bd6751e76e8199196d454941c45d1b3a323f1433bd6",
            "BC1SW50QGDZ25J" to "6002751e",
            "bc1zw508d6qejxtdg4y5r3zarvaryvaxxpcs" to "5210751e76e8199196d454941c45d1b3a323",
            "tb1qqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvsesrxh6hy" to "0020000000c4a5cad46221b2a187905e5266362b99d5e91c6ce24d165dab93e86433",
            "tb1pqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvsesf3hn0c" to "5120000000c4a5cad46221b2a187905e5266362b99d5e91c6ce24d165dab93e86433",
            "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0" to "512079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        )
        for ((address, expectedScriptHex) in inputs) {
            val (_, _, program) = Bech32.decodeWitnessAddress(address)
            // Strip the 2-byte witness version + push-length prefix (4 hex chars) for comparison.
            assertEquals(expectedScriptHex.substring(4), Hex.encode(program))
        }
    }

    @Test
    fun createSegwitAddresses() {
        assertEquals(
            "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4".lowercase(),
            Bech32.encodeWitnessAddress("bc", 0, Hex.decode("751e76e8199196d454941c45d1b3a323f1433bd6"))
        )
        assertEquals(
            "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0",
            Bech32.encodeWitnessAddress(
                "bc",
                1,
                Hex.decode("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
            )
        )
        assertEquals(
            "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7",
            Bech32.encodeWitnessAddress(
                "tb",
                0,
                Hex.decode("1863143c14c5166804bd19203356da136c985678cd4d27a1b8c6329604903262")
            )
        )
    }

    @Test
    fun rejectInvalidAddresses() {
        val addresses = listOf(
            // Bech32
            "tc1qw508d6qejxtdg4y5r3zarvary0c5xw7kg3g4ty",
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5",
            "BC13W508D6QEJXTDG4Y5R3ZARVARY0C5XW7KN40WF2",
            "bc1rw5uspcuh",
            "bc10w508d6qejxtdg4y5r3zarvary0c5xw7kw508d6qejxtdg4y5r3zarvary0c5xw7kw5rljs90",
            "bca0w508d6qejxtdg4y5r3zarvary0c5xw7kw508d6qejxtdg4y5r3zarvary0c5xw7kw5rljs90234567789035",
            "BC1QR508D6QEJXTDG4Y5R3ZARVARYV98GJ9P",
            "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sL5k7",
            "bc1zw508d6qejxtdg4y5r3zarvaryvqyzf3du",
            "tb1pw508d6qejxtdg4y5r3zarqfsj6c3",
            "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3pjxtptv",
            "bc1gmk9yu",
            // Bech32m mistakes
            "tc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq5zuyut",
            "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqh2y7hd",
            "tb1z0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqglt7rf",
            "BC1S0XLXVLHEMJA6C4DQV22UAPCTQUPFHLXM9H8Z3K2E72Q4K9HCZ7VQ54WELL",
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kemeawh",
            "tb1q0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq24jc47",
            "bc1p38j9r5y49hruaue7wxjce0updqjuyyx0kh56v8s25huc6995vvpql3jow4",
            "BC130XLXVLHEMJA6C4DQV22UAPCTQUPFHLXM9H8Z3K2E72Q4K9HCZ7VQ7ZWS8R",
            "bc1pw5dgrnzv",
            "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7v8n0nx0muaewav253zgeav"
        )
        for (address in addresses) {
            assertThrows { Bech32.decodeWitnessAddress(address) }
        }
    }

    @Test
    fun rejectInvalidCharacter() {
        val encoded = "lno1zcss88lll8vlpqqqqqqclllllllvwvcqpq8qllllgqrqqgqq8s(q8888"
        var threw: Throwable? = null
        try {
            Bech32.decode(encoded)
        } catch (t: Throwable) {
            threw = t
        }
        assertTrue("expected exception", threw != null)
        assertEquals("invalid character", threw?.message)
    }

    @Test
    fun encodeAndDecodeArbitraryData() {
        val bin = listOf(
            Hex.decode("00"),
            Hex.decode("ff"),
            Hex.decode("0102030405"),
            Hex.decode("01ff02a12abc"),
            Hex.decode("20000000c4a5cad46221b2a187905e5266362b99d5e91c6ce24d165dab93e86433"),
            Hex.decode("28751e76e8199196d454941c45d1b3a323f1433bd6751e76e8199196d454941c45d1b3a323f1433bd6")
        )
        for (data in bin) {
            val encoded = Bech32.encodeBytes("hrp", data, Bech32.Encoding.Beck32WithoutChecksum)
            val (hrp, decoded, encoding) = Bech32.decodeBytes(encoded, noChecksum = true)
            assertEquals("hrp", hrp)
            assertEquals(Bech32.Encoding.Beck32WithoutChecksum, encoding)
            assertArrayEquals(data, decoded)
        }
    }

    private inline fun assertThrows(block: () -> Unit) {
        var threw = false
        try {
            block()
        } catch (_: Throwable) {
            threw = true
        }
        assertTrue("expected an exception", threw)
    }
}
