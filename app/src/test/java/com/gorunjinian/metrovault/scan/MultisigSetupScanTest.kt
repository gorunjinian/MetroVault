package com.gorunjinian.metrovault.scan

import com.gorunjinian.bbqr.FileType
import com.gorunjinian.bbqr.SplitResult
import com.gorunjinian.bcur.Cbor
import com.gorunjinian.bcur.UR
import com.gorunjinian.bcur.UREncoder
import com.gorunjinian.bcur.registry.CryptoCoinInfo
import com.gorunjinian.bcur.registry.CryptoHDKey
import com.gorunjinian.bcur.registry.CryptoKeypath
import com.gorunjinian.bcur.registry.CryptoOutput
import com.gorunjinian.bcur.registry.MultiKey
import com.gorunjinian.bcur.registry.ScriptExpression
import com.gorunjinian.bcur.registry.URCoinInfo
import com.gorunjinian.bcur.registry.URHDKey
import com.gorunjinian.bcur.registry.URKeypath
import com.gorunjinian.bcur.registry.UROutputDescriptor
import com.gorunjinian.bcur.registry.pathcomponent.IndexPathComponent
import com.gorunjinian.bcur.registry.pathcomponent.PairPathComponent
import com.gorunjinian.bcur.registry.pathcomponent.WildcardPathComponent
import com.gorunjinian.metrovault.domain.service.multisig.MultisigSetupFormat
import com.gorunjinian.metrovault.domain.service.scan.ScanSession
import com.gorunjinian.metrovault.domain.service.scan.ScannedPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Import Multisig's scan path, frames to setup text: every transport must unwrap to the original
 * content, and UR:OUTPUT-DESCRIPTOR must surface its embedded wallet name.
 */
class MultisigSetupScanTest {

    // Content validity is the parser's concern, not the scanner's — placeholders suffice.
    private val descriptor =
        "wsh(sortedmulti(2,[aaaaaaaa/48'/0'/0'/2']xpubPlaceholderKey1/<0;1>/*,[bbbbbbbb/48'/0'/0'/2']xpubPlaceholderKey2/<0;1>/*))"

    private fun outputDescriptorUr(source: String, name: String? = null): String {
        val cbor = UROutputDescriptor(source = source, name = name).toCbor().encode()
        return UREncoder.encode(UR("output-descriptor", cbor))
    }

    private fun scan(vararg frames: String): ScannedPayload.MultisigSetup {
        val session = ScanSession()
        val update = frames.map { session.receive(it) }.last()
        assertTrue("scan should be complete after ${frames.size} frame(s): $update", update is ScanSession.Update.Complete)
        val payload = (update as ScanSession.Update.Complete).scan.payload
        assertTrue("expected a multisig setup, got $payload", payload is ScannedPayload.MultisigSetup)
        return payload as ScannedPayload.MultisigSetup
    }

    @Test
    fun outputDescriptorUrCarriesWalletName() {
        val setup = scan(outputDescriptorUr(descriptor, name = "Family Vault"))

        assertEquals(descriptor, setup.content)
        assertEquals("Family Vault", setup.walletName)
    }

    @Test
    fun outputDescriptorUrWithoutNameYieldsNullName() {
        val setup = scan(outputDescriptorUr(descriptor))

        assertEquals(descriptor, setup.content)
        assertNull(setup.walletName)
    }

    @Test
    fun outputDescriptorUrBlankNameYieldsNullName() {
        assertNull(scan(outputDescriptorUr(descriptor, name = "   ")).walletName)
    }

    @Test
    fun plainDescriptorFrameHasNoName() {
        val setup = scan(descriptor)

        assertEquals(descriptor, setup.content)
        assertEquals(MultisigSetupFormat.DESCRIPTOR, setup.format)
        assertNull(setup.walletName)
    }

    /** Setup-file text over UR:BYTES — how BSMS and ColdCard setups arrive via UR. */
    @Test
    fun urBytesUnwrapsToOriginalText() {
        val setupFile = "Name: My_Wallet\nPolicy: 2 of 2\nFormat: P2WSH\n" +
            "Derivation: m/48'/0'/0'/2'\naaaaaaaa: xpubKey1\nbbbbbbbb: xpubKey2"
        val ur = UREncoder.encode(UR("bytes", Cbor.encodeByteString(setupFile.toByteArray(Charsets.UTF_8))))

        val setup = scan(ur)

        assertEquals(setupFile, setup.content)
        assertEquals(MultisigSetupFormat.SETUP_FILE, setup.format)
        assertNull(setup.walletName)
    }

    /** Setup-file text over BBQr type U with default (zlib) encoding — the ColdCard wrapping. */
    @Test
    fun bbqrUnicodeUnwrapsToOriginalText() {
        val setupFile = "Name: BBQr_Wallet\nPolicy: 2 of 3\nFormat: P2WSH\n" +
            "Derivation: m/48'/0'/0'/2'\naaaaaaaa: xpubKey1\nbbbbbbbb: xpubKey2\ncccccccc: xpubKey3"
        val split = SplitResult.fromData(setupFile.toByteArray(Charsets.UTF_8), FileType.UnicodeText)

        val setup = scan(*split.parts.toTypedArray())

        assertEquals(setupFile, setup.content)
        assertNull(setup.walletName)
    }

    /** Anything the parser can read must reach it, however the descriptor is wrapped. */
    @Test
    fun everyDescriptorFormTheParserReadsIsASetup() {
        val bare = descriptor.removePrefix("wsh(").removeSuffix(")")
        val commented = "# Exported by a coordinator\n$descriptor"
        val json = """{"label":"Vault","blockheight":0,"descriptor":"$descriptor"}"""

        for (content in listOf(bare, commented, json, "sh($descriptor)", "BSMS 1.0\n$descriptor\n/0/*,/1/*")) {
            assertEquals(content, scan(content).content)
        }
    }

    /**
     * Pins the descriptor rebuilt from a UR:CRYPTO-OUTPUT multisig, byte for byte. The rebuilt
     * xpubs feed the stored descriptor (and its checksum), so this must not drift.
     */
    @Test
    fun cryptoOutputMultisigRebuildsDescriptor() {
        val output = CryptoOutput(
            listOf(ScriptExpression.WITNESS_SCRIPT_HASH, ScriptExpression.SORTED_MULTISIG),
            MultiKey(
                threshold = 2,
                ecKeys = emptyList(),
                hdKeys = listOf(
                    cosignerKey(GENERATOR_POINT, fingerprint = "aaaaaaaa", chainCodeByte = 1),
                    cosignerKey(DOUBLE_GENERATOR_POINT, fingerprint = "bbbbbbbb", chainCodeByte = 2)
                )
            )
        )

        assertEquals(CRYPTO_OUTPUT_DESCRIPTOR, scan(UREncoder.encode(output.toUR())).content)
    }

    /** BCR-2023-010 sources may name keys by position instead of spelling them out. */
    @Test
    fun outputDescriptorPlaceholdersAreFilledIn() {
        val record = UROutputDescriptor(
            source = "wsh(sortedmulti(2,@0,@1))",
            keys = listOf(
                urCosignerKey(GENERATOR_POINT, fingerprint = "aaaaaaaa", chainCodeByte = 1),
                urCosignerKey(DOUBLE_GENERATOR_POINT, fingerprint = "bbbbbbbb", chainCodeByte = 2)
            )
        )

        assertEquals(CRYPTO_OUTPUT_DESCRIPTOR, scan(UREncoder.encode(record.toUR())).content)
    }

    private fun cosignerKey(pubKeyHex: String, fingerprint: String, chainCodeByte: Int) = CryptoHDKey(
        privateKey = false,
        key = pubKeyHex.hexToByteArray(),
        chainCode = ByteArray(32) { chainCodeByte.toByte() },
        useInfo = CryptoCoinInfo(CryptoCoinInfo.Type.BITCOIN, CryptoCoinInfo.Network.MAINNET),
        origin = CryptoKeypath(
            listOf(
                IndexPathComponent(48, true),
                IndexPathComponent(0, true),
                IndexPathComponent(0, true),
                IndexPathComponent(2, true)
            ),
            fingerprint.hexToByteArray()
        ),
        children = CryptoKeypath(
            listOf(
                PairPathComponent(IndexPathComponent(0, false), IndexPathComponent(1, false)),
                WildcardPathComponent(false)
            ),
            null
        ),
        parentFingerprint = null
    )

    /** The same key as [cosignerKey] in the newer registry type output descriptors carry. */
    private fun urCosignerKey(pubKeyHex: String, fingerprint: String, chainCodeByte: Int): URHDKey {
        val key = cosignerKey(pubKeyHex, fingerprint, chainCodeByte)
        return URHDKey(
            privateKey = false,
            key = key.key,
            chainCode = key.chainCode,
            useInfo = URCoinInfo(CryptoCoinInfo.Type.BITCOIN, CryptoCoinInfo.Network.MAINNET),
            origin = URKeypath(key.origin!!.components, key.origin!!.sourceFingerprint),
            children = URKeypath(key.children!!.components, null),
            parentFingerprint = null
        )
    }

    private companion object {
        // Any valid compressed points do: G and 2G of secp256k1.
        const val GENERATOR_POINT = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        const val DOUBLE_GENERATOR_POINT = "02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"

        // Captured from the original DescriptorQRScanner, which rebuilt it before URDescriptors did.
        const val CRYPTO_OUTPUT_DESCRIPTOR =
            "wsh(sortedmulti(2," +
                "[aaaaaaaa/48'/0'/0'/2']xpub6EnfK2MDiHtcGUqdMPt2bEM34rJAohLUcj7Wa3t6AmCfjVTqJ1JVkXo3tkhmvL" +
                "vHjhBmxU3PyXLo1UFVF68j312HekKWgcaZHfKUWLTpCLp/<0;1>/*," +
                "[bbbbbbbb/48'/0'/0'/2']xpub6EuwJUaoUtbcW39yj62DE7pQY5dRqKyJdVn554X21nxXf2nbg6ng5i53TFSZU8" +
                "YzENf3MWzA4eRDEhu5fvHixsXmTqDujKKaoR2BTioJmB7/<0;1>/*))"
    }
}
