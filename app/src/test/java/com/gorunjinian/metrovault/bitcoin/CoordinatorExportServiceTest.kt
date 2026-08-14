package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.domain.service.bitcoin.AddressService
import com.gorunjinian.metrovault.domain.service.bitcoin.CoordinatorExportService
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoordinatorExportServiceTest {
    private val service = CoordinatorExportService()
    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun regressionVectorNormalizesZpubAndBuildsExactSignerRecord() {
        val zpub = "zpub6rrMp2mfVfcoBokpHhjHrqnPNT6XH24HEPfiaxzLKNbv7eVvAvSVXCiQ2n5fJekMYQu5KqDLZzgMjj86gfLw1M1R4rF6bxiifJmRdP2smxV"
        val expectedXpub = "xpub6DBqChRqCJXqVDNaczA3SfbP2WodPn5HQAdH2BCZZMrA1SsTfc7NH5Q7zNAVJqSWj8fTpt2DefyFy9tyFGWuQseDLArFS95k7re8rGrtGeD"
        val expectedQr = "[3CA02B0D/84h/0h/0h]$expectedXpub/<0;1>/*"

        // The zpub and the xpub are the same account key under different version bytes.
        val (_, decoded) = DeterministicWallet.ExtendedPublicKey.decode(zpub)
        val normalized = decoded.encode(DeterministicWallet.xpub)
        assertEquals(expectedXpub, normalized)
        assertEquals(
            expectedQr,
            CoordinatorExportService.buildNunchukSignerRecord("3ca02b0d", "m/84'/0'/0'", normalized)
        )
    }

    @Test
    fun normalizesApostropheAndUppercaseHPaths() {
        assertEquals("m/84h/0h/7h", CoordinatorExportService.parseAccountPath("m/84'/0H/7h").normalized)
    }

    @Test
    fun supportsMainnetXpubTestnetTpubAndNonZeroAccounts() {
        val main = export("m/84'/0'/0'")
        val test = export("m/84'/1'/17'")

        assertTrue(main.standardAccountXpub.startsWith("xpub"))
        assertEquals(0, main.accountNumber)
        assertTrue(test.standardAccountXpub.startsWith("tpub"))
        assertEquals(17, test.accountNumber)
        assertEquals("Bitcoin testnet", test.networkName)
        assertTrue(test.coldcardJson.contains("\"chain\": \"XTN\""))
    }

    @Test
    fun routesAllSupportedSingleSignaturePurposes() {
        val vectors = listOf(
            Triple("m/44'/0'/0'", "bip44", "p2pkh"),
            Triple("m/49'/0'/0'", "bip49", "p2sh-p2wpkh"),
            Triple("m/84'/0'/0'", "bip84", "p2wpkh"),
            Triple("m/86'/0'/0'", "bip86", "p2tr")
        )
        vectors.forEach { (path, section, name) ->
            val exported = export(path)
            val json = JSONObject(exported.coldcardJson)
            assertEquals(name, json.getJSONObject(section).getString("name"))
            // Coldcard writes hardened markers as apostrophes in `deriv`, unlike the descriptor.
            assertEquals(path, json.getJSONObject(section).getString("deriv"))
        }
    }

    @Test
    fun jsonRoundTripsAndContainsPublicDescriptorAndFirstAddress() {
        val exported = export("m/49'/0'/3'")
        val parsed = JSONObject(exported.coldcardJson)
        val section = parsed.getJSONObject("bip49")

        assertEquals("BTC", parsed.getString("chain"))
        assertEquals(exported.masterFingerprint, parsed.getString("xfp"))
        assertEquals(3, parsed.getInt("account"))
        assertEquals("m/49'/0'/3'", section.getString("deriv"))
        assertEquals(exported.standardAccountXpub, section.getString("xpub"))

        // The section xfp is the account node's own fingerprint, not the master repeated. It is
        // derivable from the exported xpub, and must not collide with the top-level value.
        val (_, accountKey) = DeterministicWallet.ExtendedPublicKey.decode(exported.standardAccountXpub)
        val expectedAccountXfp = accountKey.fingerprint().toString(16).padStart(8, '0').uppercase()
        assertEquals(expectedAccountXfp, section.getString("xfp"))
        assertNotEquals(parsed.getString("xfp"), section.getString("xfp"))
        assertEquals(exported.slip132AccountXpub, section.getString("_pub"))
        assertEquals(exported.descriptor, section.getString("desc"))
        assertEquals(exported.firstReceiveAddress, section.getString("first"))
        assertTrue(exported.descriptor.contains("[${exported.masterFingerprint.lowercase()}/49h/0h/3h]"))
        assertTrue(exported.descriptor.contains("/<0;1>/*"))
        assertTrue(exported.descriptor.matches(Regex(".*#[a-z0-9]{8}$")))

        val (_, accountPublicKey) = DeterministicWallet.ExtendedPublicKey.decode(exported.standardAccountXpub)
        val independentlyDerived = AddressService().generateAddress(
            accountPublicKey, 0, false,
            CoordinatorExportService.parseAccountPath(exported.derivationPath).scriptType,
            isTestnet = false
        )?.address
        assertEquals(independentlyDerived, exported.firstReceiveAddress)

        val reparsed = JSONObject(parsed.toString())
        assertEquals(section.getString("desc"), reparsed.getJSONObject("bip49").getString("desc"))
    }

    @Test
    fun acceptsPublicKeyWhoseBase58BodyContainsPrivatePrefixText() {
        // This is a valid depth-three xpub whose Base58 body contains "tpRv". Text scanning for
        // private-key prefixes rejected it even though its decoded version bytes identify an xpub.
        val xpub = "xpub6DBqChRqCJXqV7hiPpcSQ1fn988mqgmQtJ3qoWZSKBAigmcLKaCD2yQR8mAkifMAtT1JqtpRvSjBWBSMagz9ufv3YJEfpghjFFzidLRMcB8"

        val signerRecord = CoordinatorExportService.buildNunchukSignerRecord(
            "3CA02B0D",
            "m/84'/0'/0'",
            xpub
        )

        assertTrue(signerRecord.contains(xpub))
    }

    @Test
    fun rejectsExtendedPrivateKeysByDecodedVersionBytes() {
        val master = DeterministicWallet.generate(MnemonicCode.toSeed(mnemonic.split(" "), ""))
        val accountXprv = master.derivePrivateKey("m/84'/0'/0'").encode(DeterministicWallet.xprv)

        assertRejects {
            CoordinatorExportService.buildNunchukSignerRecord(
                "3CA02B0D",
                "m/84'/0'/0'",
                accountXprv
            )
        }
    }

    @Test
    fun rejectsMalformedFingerprintPathAndKey() {
        val valid = export("m/84'/0'/0'")
        assertRejects { CoordinatorExportService.buildNunchukSignerRecord("1234", "m/84'/0'/0'", valid.standardAccountXpub) }
        assertRejects { CoordinatorExportService.buildNunchukSignerRecord("3CA02B0D", "m/352'/0'/0'", valid.standardAccountXpub) }
        assertRejects { CoordinatorExportService.buildNunchukSignerRecord("3CA02B0D", "m/84'/0'/0'", "xpub-not-a-key") }
        assertRejects { CoordinatorExportService.buildNunchukSignerRecord("3CA02B0D", "m/84'/1'/0'", valid.standardAccountXpub) }
        assertRejects { CoordinatorExportService.parseAccountPath("m/84'/0'/2147483648'") }
        assertRejects { CoordinatorExportService.parseAccountPath("m/84'/2'/0'") }
    }

    @Test
    fun passphraseWalletUsesPassphraseDerivedFingerprintAndAccountXpub() {
        val withoutPassphrase = export("m/84'/0'/0'", passphrase = "")
        val withPassphrase = export("m/84'/0'/0'", passphrase = "correct horse battery staple")

        assertNotEquals(withoutPassphrase.masterFingerprint, withPassphrase.masterFingerprint)
        assertNotEquals(withoutPassphrase.standardAccountXpub, withPassphrase.standardAccountXpub)
        assertTrue(withPassphrase.nunchukSignerRecord.startsWith("[${withPassphrase.masterFingerprint}/84h/0h/0h]${withPassphrase.standardAccountXpub}"))
        assertTrue(withPassphrase.descriptor.contains(withPassphrase.masterFingerprint.lowercase()))
        assertTrue(withPassphrase.descriptor.contains(withPassphrase.standardAccountXpub))
    }

    @Test
    fun combinedExportContainsAllSectionsAndOmitsBip45AndBip48Descs() {
        val parsed = JSONObject(combinedExport())

        assertEquals("BTC", parsed.getString("chain"))
        assertEquals(0, parsed.getInt("account"))
        assertTrue(parsed.getString("xfp").matches(Regex("^[0-9A-F]{8}$")))
        assertTrue(!parsed.has("bip45"))
        assertTrue(!parsed.has("xpub"))

        listOf(
            Triple("bip44", "p2pkh", "m/44'/0'/0'"),
            Triple("bip49", "p2sh-p2wpkh", "m/49'/0'/0'"),
            Triple("bip84", "p2wpkh", "m/84'/0'/0'"),
            Triple("bip86", "p2tr", "m/86'/0'/0'")
        ).forEach { (sectionKey, name, deriv) ->
            val section = parsed.getJSONObject(sectionKey)
            assertEquals(name, section.getString("name"))
            assertEquals(deriv, section.getString("deriv"))
            assertTrue(section.getString("xpub").startsWith("xpub"))
            val origin = deriv.removePrefix("m/").replace("'", "h")
            val desc = section.getString("desc")
            assertTrue(desc.contains("[${parsed.getString("xfp").lowercase()}/$origin]"))
            assertTrue(desc.contains("/<0;1>/*"))
            assertTrue(desc.matches(Regex(".*#[a-z0-9]{8}$")))
            assertTrue(section.getString("first").isNotEmpty())
            // The section xfp is the account node's own fingerprint, never the master repeated.
            val (_, accountKey) = DeterministicWallet.ExtendedPublicKey.decode(section.getString("xpub"))
            val expectedXfp = accountKey.fingerprint().toString(16).padStart(8, '0').uppercase()
            assertEquals(expectedXfp, section.getString("xfp"))
            assertNotEquals(parsed.getString("xfp"), section.getString("xfp"))
        }
        // SLIP-132 alias only where it differs from the standard encoding.
        assertTrue(!parsed.getJSONObject("bip44").has("_pub"))
        assertTrue(!parsed.getJSONObject("bip86").has("_pub"))
        assertTrue(parsed.getJSONObject("bip49").getString("_pub").startsWith("ypub"))
        assertTrue(parsed.getJSONObject("bip84").getString("_pub").startsWith("zpub"))

        listOf(
            Triple("bip48_1", "p2sh-p2wsh", "Ypub"),
            Triple("bip48_2", "p2wsh", "Zpub")
        ).forEachIndexed { index, (sectionKey, name, slip132Prefix) ->
            val section = parsed.getJSONObject(sectionKey)
            assertEquals(name, section.getString("name"))
            assertEquals("m/48'/0'/0'/${index + 1}'", section.getString("deriv"))
            assertTrue(section.getString("xpub").startsWith("xpub"))
            assertTrue(section.getString("_pub").startsWith(slip132Prefix))
            // Coldcard writes placeholder descriptors here; we deliberately write none.
            assertTrue(!section.has("desc"))
            assertTrue(!section.has("first"))
        }
    }

    @Test
    fun combinedExportSectionsMatchTheSingleAccountExport() {
        val parsed = JSONObject(combinedExport())
        val single = export("m/84'/0'/0'")
        val section = parsed.getJSONObject("bip84")

        assertEquals(single.masterFingerprint, parsed.getString("xfp"))
        assertEquals(single.standardAccountXpub, section.getString("xpub"))
        assertEquals(single.slip132AccountXpub, section.getString("_pub"))
        assertEquals(single.descriptor, section.getString("desc"))
        assertEquals(single.firstReceiveAddress, section.getString("first"))
    }

    @Test
    fun combinedExportSupportsTestnetAndNonZeroAccounts() {
        val parsed = JSONObject(combinedExport(accountNumber = 5, isTestnet = true))

        assertEquals("XTN", parsed.getString("chain"))
        assertEquals(5, parsed.getInt("account"))
        assertEquals("m/84'/1'/5'", parsed.getJSONObject("bip84").getString("deriv"))
        assertTrue(parsed.getJSONObject("bip84").getString("xpub").startsWith("tpub"))
        assertTrue(parsed.getJSONObject("bip49").getString("_pub").startsWith("upub"))
        assertEquals("m/48'/1'/5'/2'", parsed.getJSONObject("bip48_2").getString("deriv"))
        assertTrue(parsed.getJSONObject("bip48_2").getString("xpub").startsWith("tpub"))
        assertTrue(parsed.getJSONObject("bip48_2").getString("_pub").startsWith("Vpub"))
    }

    @Test
    fun buildsExactNunchukBip48SignerRecord() {
        val master = DeterministicWallet.generate(MnemonicCode.toSeed(mnemonic.split(" "), ""))
        val xpub = master.derivePrivateKey("m/48'/0'/0'/2'").extendedPublicKey
            .encode(DeterministicWallet.xpub)

        val record = CoordinatorExportService.buildNunchukBip48SignerRecord(
            "3ca02b0d", 0, DerivationPaths.Bip48ScriptType.P2WSH, xpub, isTestnet = false
        )

        assertEquals("[3CA02B0D/48h/0h/0h/2h]$xpub/<0;1>/*", record)
    }

    @Test
    fun rejectsMismatchedNunchukBip48SignerRecordInputs() {
        val master = DeterministicWallet.generate(MnemonicCode.toSeed(mnemonic.split(" "), ""))
        val p2wshXpub = master.derivePrivateKey("m/48'/0'/0'/2'").extendedPublicKey
            .encode(DeterministicWallet.xpub)
        val depthThreeXpub = master.derivePrivateKey("m/84'/0'/0'").extendedPublicKey
            .encode(DeterministicWallet.xpub)

        // Key derived for the other script type, wrong depth, wrong network, bad fingerprint.
        assertRejects {
            CoordinatorExportService.buildNunchukBip48SignerRecord(
                "3CA02B0D", 0, DerivationPaths.Bip48ScriptType.P2SH_P2WSH, p2wshXpub, isTestnet = false
            )
        }
        assertRejects {
            CoordinatorExportService.buildNunchukBip48SignerRecord(
                "3CA02B0D", 0, DerivationPaths.Bip48ScriptType.P2WSH, depthThreeXpub, isTestnet = false
            )
        }
        assertRejects {
            CoordinatorExportService.buildNunchukBip48SignerRecord(
                "3CA02B0D", 0, DerivationPaths.Bip48ScriptType.P2WSH, p2wshXpub, isTestnet = true
            )
        }
        assertRejects {
            CoordinatorExportService.buildNunchukBip48SignerRecord(
                "1234", 0, DerivationPaths.Bip48ScriptType.P2WSH, p2wshXpub, isTestnet = false
            )
        }
    }

    private fun export(path: String, passphrase: String = "") = run {
        val master = DeterministicWallet.generate(MnemonicCode.toSeed(mnemonic.split(" "), passphrase))
        val account = master.derivePrivateKey(path).extendedPublicKey
        val fingerprint = master.fingerprint().toString(16).padStart(8, '0').uppercase()
        service.buildExport("Test Wallet", fingerprint, path, account)
    }

    private fun combinedExport(accountNumber: Int = 0, isTestnet: Boolean = false): String {
        val master = DeterministicWallet.generate(MnemonicCode.toSeed(mnemonic.split(" "), ""))
        val coin = if (isTestnet) 1 else 0
        fun key(path: String) = master.derivePrivateKey(path).extendedPublicKey
        val fingerprint = master.fingerprint().toString(16).padStart(8, '0').uppercase()
        return service.buildCombinedExport(
            masterFingerprint = fingerprint,
            accountNumber = accountNumber,
            isTestnet = isTestnet,
            keys = CoordinatorExportService.CombinedAccountKeys(
                bip44 = key("m/44'/$coin'/$accountNumber'"),
                bip49 = key("m/49'/$coin'/$accountNumber'"),
                bip84 = key("m/84'/$coin'/$accountNumber'"),
                bip86 = key("m/86'/$coin'/$accountNumber'"),
                bip48P2shP2wsh = key("m/48'/$coin'/$accountNumber'/1'"),
                bip48P2wsh = key("m/48'/$coin'/$accountNumber'/2'")
            )
        )
    }

    private fun assertRejects(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
