package com.gorunjinian.metrovault.bitcoin

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

        val normalized = CoordinatorExportService.normalizeToStandardXpub(zpub, isTestnet = false)
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
        assertTrue(test.nunchukJson.contains("\"chain\": \"XTN\""))
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
            val json = JSONObject(exported.nunchukJson)
            assertEquals(name, json.getJSONObject(section).getString("name"))
            assertEquals(path.replace("'", "h"), json.getJSONObject(section).getString("deriv"))
        }
    }

    @Test
    fun jsonRoundTripsAndContainsPublicDescriptorAndFirstAddress() {
        val exported = export("m/49'/0'/3'")
        val parsed = JSONObject(exported.nunchukJson)
        val section = parsed.getJSONObject("bip49")

        assertEquals("BTC", parsed.getString("chain"))
        assertEquals(exported.masterFingerprint, parsed.getString("xfp"))
        assertEquals(3, parsed.getInt("account"))
        assertEquals(exported.standardAccountXpub, section.getString("xpub"))
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

        assertFails { CoordinatorExportService.normalizeToStandardXpub(accountXprv, isTestnet = false) }
        assertFails {
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
        assertFails { CoordinatorExportService.buildNunchukSignerRecord("1234", "m/84'/0'/0'", valid.standardAccountXpub) }
        assertFails { CoordinatorExportService.buildNunchukSignerRecord("3CA02B0D", "m/352'/0'/0'", valid.standardAccountXpub) }
        assertFails { CoordinatorExportService.buildNunchukSignerRecord("3CA02B0D", "m/84'/0'/0'", "xpub-not-a-key") }
        assertFails { CoordinatorExportService.buildNunchukSignerRecord("3CA02B0D", "m/84'/1'/0'", valid.standardAccountXpub) }
        assertFails { CoordinatorExportService.normalizeToStandardXpub(valid.standardAccountXpub, isTestnet = true) }
        assertFails { CoordinatorExportService.parseAccountPath("m/84'/0'/2147483648'") }
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

    private fun export(path: String, passphrase: String = "") = run {
        val master = DeterministicWallet.generate(MnemonicCode.toSeed(mnemonic.split(" "), passphrase))
        val account = master.derivePrivateKey(path).extendedPublicKey
        val fingerprint = master.fingerprint().toString(16).padStart(8, '0').uppercase()
        service.buildExport("Test Wallet", fingerprint, path, account)
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
