package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.Bech32
import com.gorunjinian.metrovault.lib.bitcoin.Bitcoin
import com.gorunjinian.metrovault.lib.bitcoin.Block
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector32
import com.gorunjinian.metrovault.lib.bitcoin.Crypto
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.OP_2
import com.gorunjinian.metrovault.lib.bitcoin.OP_CHECKSIG
import com.gorunjinian.metrovault.lib.bitcoin.OP_CHECKSIGADD
import com.gorunjinian.metrovault.lib.bitcoin.OP_GREATERTHANOREQUAL
import com.gorunjinian.metrovault.lib.bitcoin.OP_PUSHDATA
import com.gorunjinian.metrovault.lib.bitcoin.OutPoint
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.PublicKey
import com.gorunjinian.metrovault.lib.bitcoin.Satoshi
import com.gorunjinian.metrovault.lib.bitcoin.Script
import com.gorunjinian.metrovault.lib.bitcoin.ScriptFlags
import com.gorunjinian.metrovault.lib.bitcoin.ScriptTree
import com.gorunjinian.metrovault.lib.bitcoin.ScriptWitness
import com.gorunjinian.metrovault.lib.bitcoin.SigHash
import com.gorunjinian.metrovault.lib.bitcoin.SigVersion
import com.gorunjinian.metrovault.lib.bitcoin.Transaction
import com.gorunjinian.metrovault.lib.bitcoin.Transaction.Companion.hashForSigningSchnorr
import com.gorunjinian.metrovault.lib.bitcoin.TxIn
import com.gorunjinian.metrovault.lib.bitcoin.TxOut
import com.gorunjinian.metrovault.lib.bitcoin.byteVector
import com.gorunjinian.metrovault.lib.bitcoin.toSatoshi
import fr.acinq.secp256k1.Secp256k1
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaprootTest {

    @Test
    fun taprootSighashSingleWithoutCorrespondingOutputIsRejected() {
        val prevTxHash = com.gorunjinian.metrovault.lib.bitcoin.TxHash(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val tx = Transaction(
            2,
            listOf(
                TxIn(OutPoint(prevTxHash, 0), ByteVector.empty, TxIn.SEQUENCE_FINAL),
                TxIn(OutPoint(prevTxHash, 1), ByteVector.empty, TxIn.SEQUENCE_FINAL),
                TxIn(OutPoint(prevTxHash, 2), ByteVector.empty, TxIn.SEQUENCE_FINAL)
            ),
            listOf(
                TxOut(50_000L.toSatoshi(), ByteVector("76a91489abcdefabbaabbaabbaabbaabbaabbaabbaabba88ac"))
            ),
            0
        )
        val inputs = List(3) {
            TxOut(50_000L.toSatoshi(), ByteVector("76a91489abcdefabbaabbaabbaabbaabbaabbaabbaabba88ac"))
        }
        //here we only assert that some exception is thrown.
        // TODO: when adopting the upstream require() guard, tighten this to check the message.
        assertThrows {
            hashForSigningSchnorr(tx, 2, inputs, SigHash.SIGHASH_SINGLE, SigVersion.SIGVERSION_TAPROOT)
        }
    }

    @Test
    fun checkTaprootSignatures() {
        val (_, master) = DeterministicWallet.ExtendedPrivateKey.decode(
            "tprv8ZgxMBicQKsPeQQADibg4WF7mEasy3piWZUHyThAzJCPNgMHDVYhTCVfev3jFbDhcYm4GimeFMbbi9z1d9rfY1aL5wfJ9mNebQ4thJ62EJb"
        )
        val key = master.derivePrivateKey("86'/1'/0'/0/1")
        val internalKey = key.publicKey.xOnly()
        val script = Script.pay2tr(internalKey, null as ScriptTree?)
        val outputKey = internalKey.outputKey(Crypto.TaprootTweak.NoScriptTweak).first
        assertEquals(
            "tb1phlhs7afhqzkgv0n537xs939s687826vn8l24ldkrckvwsnlj3d7qj6u57c",
            internalKey.p2trAddress(Block.Testnet3GenesisBlock.hash)
        )

        // Funding tx outputs to outputKey
        val tx = Transaction.read(
            "02000000000101590c995983abb86d8196f57357f2aac0e6cc6144d8239fd8a171810b476269d50000000000feffffff02a086010000000000225120bfef0f753700ac863e748f8d02c4b0d1fc7569933fd55fb6c3c598e84ff28b7c13d3abe65a060000160014353b5487959c58f5feafe63800057899f9ece4280247304402200b20c43175358c970850a583fd60d36c06588f1103b82b0968dc21e20e7d7958022027c64923623205c4985541d4a9fc6b5df4111d918fe63803337538b029c17ea20121022f685476d299e7b49d3a6b380e10aec1f93d96819fd7697669fabb533cc052624ff50000"
        )
        assertTrue(Script.isPay2tr(tx.txOut[0].publicKeyScript))
        assertEquals(script, Script.parse(tx.txOut[0].publicKeyScript))
        // pay2trOutputKey extracts the tweaked output X-only key directly from the scriptPubKey.
        assertEquals(outputKey, Script.pay2trOutputKey(tx.txOut[0].publicKeyScript))

        // Spending tx with key-path-only witness (single Schnorr signature)
        val tx1 = Transaction.read(
            "020000000001018cd229daf76b9733dad3f4d183809f6594abb788a1bf07f04d6e889d2040dbc00000000000fdffffff011086010000000000225120bfef0f753700ac863e748f8d02c4b0d1fc7569933fd55fb6c3c598e84ff28b7c01407f330922263a3f281e111bf8583964644ef7f694494d028de546b162cbd68591ab38f9626a8922dc20a84776dc9bd8a21dc5c64ffc5fa6f28f0d42ed2e5ffb7dcef50000"
        )
        val sig = tx1.txIn[0].witness.stack.first()
        val sighashType = if (sig.size() == 65) sig[64].toInt() else 0

        val hash = hashForSigningSchnorr(tx1, 0, listOf(tx.txOut.first()), sighashType, SigVersion.SIGVERSION_TAPROOT)
        assertTrue(Crypto.verifySignatureSchnorr(hash, sig, outputKey))

        val ourSig = Crypto.signSchnorr(hash, key.privateKey, Crypto.TaprootTweak.NoScriptTweak)
        assertTrue(Crypto.verifySignatureSchnorr(hash, ourSig, outputKey))
        assertTrue(Secp256k1.verifySchnorr(ourSig.toByteArray(), hash.toByteArray(), outputKey.value.toByteArray()))

        // Auxiliary random data zero == no aux data
        val ourSig1 = Crypto.signSchnorr(hash, key.privateKey, Crypto.TaprootTweak.NoScriptTweak, ByteVector32.Zeroes)
        assertEquals(ourSig, ourSig1)

        // Aux data non-zero changes signature
        val ourSig2 = Crypto.signSchnorr(hash, key.privateKey, Crypto.TaprootTweak.NoScriptTweak, ByteVector32.One)
        assertNotEquals(ourSig, ourSig2)
    }

    @Test
    fun sendToAndSpendFromTaprootAddress() {
        val privateKey = PrivateKey(
            ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")
        )
        val internalKey = privateKey.publicKey().xOnly()
        assertEquals(
            "tb1p33wm0auhr9kkahzd6l0kqj85af4cswn276hsxg6zpz85xe2r0y8snwrkwy",
            privateKey.publicKey().p2trAddress(Block.Testnet3GenesisBlock.hash)
        )

        val tx = Transaction.read(
            "02000000000101bf77ef36f2c0f32e0822cef0514948254997495a34bfba7dd4a73aabfcbb87900000000000fdffffff02c2c2000000000000160014b5c3dbfeb8e7d0c809c3ba3f815fd430777ef4be50c30000000000002251208c5db7f797196d6edc4dd7df6048f4ea6b883a6af6af032342088f436543790f0140583f758bea307216e03c1f54c3c6088e8923c8e1c89d96679fb00de9e808a79d0fba1cc3f9521cb686e8f43fb37cc6429f2e1480c70cc25ecb4ac0dde8921a01f1f70000"
        )
        assertEquals(
            Script.pay2tr(internalKey, null as ScriptTree?),
            Script.parse(tx.txOut[1].publicKeyScript)
        )

        val outputScript = Bitcoin.addressToPublicKeyScript(
            Block.Testnet3GenesisBlock.hash,
            "tb1pn3g330w4n5eut7d4vxq0pp303267qc6vg8d2e0ctjuqre06gs3yqnc5yx0"
        ).right!!
        val tx1 = Transaction(
            2,
            listOf(TxIn(OutPoint(tx, 1), TxIn.SEQUENCE_FINAL)),
            listOf(TxOut(49258L.toSatoshi(), outputScript)),
            0
        )
        val sig = Transaction.signInputTaprootKeyPath(
            privateKey, tx1, 0, listOf(tx.txOut[1]), sighashType = 0, scriptTree = null
        )
        val tx2 = tx1.updateWitness(0, Script.witnessKeyPathPay2tr(sig))
        Transaction.correctlySpends(tx2, tx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    @Test
    fun spendPayToTaproot1() {
        val tx1 = Transaction.read(
            "01000000000101b9cb0da76784960e000d63f0453221aeeb6df97f2119d35c3051065bc9881eab0000000000fdffffff020000000000000000186a16546170726f6f74204654572120406269746275673432a059010000000000225120339ce7e165e67d93adb3fef88a6d4beed33f01fa876f05a225242b82a631abc00247304402204bf50f2fea3a2fbf4db8f0de602d9f41665fe153840c1b6f17c0c0abefa42f0b0220631fe0968b166b00cb3027c8817f50ce8353e9d5de43c29348b75b6600f231fc012102b14f0e661960252f8f37486e7fe27431c9f94627a617da66ca9678e6a2218ce1ffd30a00"
        )
        val tx2 = Transaction.read(
            "01000000000101d1f1c1f8cdf6759167b90f52c9ad358a369f95284e841d7a2536cef31c0549580100000000fdffffff020000000000000000316a2f49206c696b65205363686e6f7272207369677320616e6420492063616e6e6f74206c69652e204062697462756734329e06010000000000225120a37c3903c8d0db6512e2b40b0dffa05e5a3ab73603ce8c9c4b7771e5412328f90140a60c383f71bac0ec919b1d7dbc3eb72dd56e7aa99583615564f9f99b8ae4e837b758773a5b2e4c51348854c8389f008e05029db7f464a5ff2e01d5e6e626174affd30a00"
        )
        Transaction.correctlySpends(tx2, tx1, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    @Test
    fun spendPayToTaproot2() {
        val tx1 = Transaction.read(
            "020000000001013dc77d529549228b6544c09349c13eb64efa8c99e339bb3f2aa280c1e412e7b00000000000feffffff0540e13300000000002251205f4237bd79e8fe440d102a5e0c20a75160e96d42a8b19825ac90f73f1f6677685008340000000000225120e914be846f7afb29f5c3b24e5f630886ed5cbcc79a28888d91009be90924508d602f340000000000225120d9390cafa11bdeb19de21e0a2bbd541f4d0979473999503408d40814399b7f9100d40a0000000000225120e8d645f42be8700595c7cbb278602fb51471d5bb24ccd27668321b7affd167bfc8a22400000000002251201a8e36e17d0afa16139b900dc85f775d3c0c624a2786fbc05ba7db87f3a55fcd0247304402207d1c9b565cebdbbdcd5973f6f4281eb6d1fceb41f53af3c597d1deacb2086d0202204672e1a9d917456e4b8346910a031898b27f3f08221cd355cfd5ee3367c5086401210291b8fe7a5ffc27834002ccac2f62aeddff9bedb436756c2e511c5c573bb9ba4dffd30a00"
        )
        val tx2 = Transaction.read(
            "020000000001041ee2529c53a3c05c1e35fa853b9209cbc1a17be31aae9f4e7ea42d13f24c65890000000000ffffffff1ee2529c53a3c05c1e35fa853b9209cbc1a17be31aae9f4e7ea42d13f24c65890100000000ffffffff1ee2529c53a3c05c1e35fa853b9209cbc1a17be31aae9f4e7ea42d13f24c65890200000000ffffffff1ee2529c53a3c05c1e35fa853b9209cbc1a17be31aae9f4e7ea42d13f24c65890300000000ffffffff01007ea60000000000225120a457d0c0399b499ed2df571d612ba549ae7f199387edceac175999210f6aa39d0141b23008b3e044d16078fc93ae4f342b6e5ba44241c598503f80269fd66e7ce484e926b2ff58ac5633be79857951b3dc778082fd38a9e06a1139e6eea41a8680c7010141be98ba2a47fce6fbe4f7456e5fe0c2381f38ed3ae3b89d0748fdbfc6936b68019e01ff60343abbea025138e58aed2544dc8d3c0b2ccb35e2073fa2f9feeff5ed010141466d525b97733d4733220694bf747fd6e9d4b0b96ea3b2fb06b7486b4b8e864df0057481a01cf10f7ea06849fb4717d62b902fe5807a1cba03a46bf3a7087e940101418dbfbdd2c164005eceb0de04c317b9cae62b0c97ed33da9dcec6301fa0517939b9024eba99e22098a5b0d86eb7218957883ea9fc13b737e1146ae2b95185fcf90100000000"
        )
        Transaction.correctlySpends(tx2, tx1, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    @Test
    fun spendPayToTapscript1() {
        val tx1 = Transaction.read(
            "02000000000101cabda47f832e48eb5bce9ee03548f46cddb167f1d495310ffa8aac38940cfab90000000000fdffffff02e6800700000000002251205f4237bd7dae576b34abc8a9c6fa4f0e4787c04234ca963e9e96c8f9b67b56d1e6800700000000002251205f4237bd7f93c69403a30c6b641f27ccf5201090152fcf1596474221307831c30247304402206441af273f66f66cfbde93c150c8e163d20358559fd3ec6c201467d4c29d0bbd022008d923c3a70a93808695457e547f69bb3a0e6bcaeb53547d506825cc7cafd0f30121039ddfe17e14a1ae9a417d1cc7614449b3387d8d69ef3e12ce3f1dffce279d4884ffd30a00"
        )
        val tx2 = Transaction.read(
            "020000000001027bc0bba407bc67178f100e352bf6e047fae4cbf960d783586cb5e430b3b700e70000000000feffffff7bc0bba407bc67178f100e352bf6e047fae4cbf960d783586cb5e430b3b700e70100000000feffffff01b4ba0e0000000000160014173fd310e9db2c7e9550ce0f03f1e6c01d833aa90140134896c42cd95680b048845847c8054756861ffab7d4abab72f6508d67d1ec0c590287ec2161dd7884983286e1cd56ce65c08a24ee0476ede92678a93b1b180c03407b5d614a4610bf9196775791fcc589597ca066dcd10048e004cd4c7341bb4bb90cee4705192f3f7db524e8067a5222c7f09baf29ef6b805b8327ecd1e5ab83ca2220f5b059b9a72298ccbefff59d9b943f7e0fc91d8a3b944a95e7b6390cc99eb5f4ac41c0d9dfdf0fe3c83e9870095d67fff59a8056dad28c6dfb944bb71cf64b90ace9a7776b22a1185fb2dc9524f6b178e2693189bf01655d7f38f043923668dc5af45bffd30a00"
        )
        Transaction.correctlySpends(tx2, tx1, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    @Test
    fun spendPayToTapscript2() {
        val tx1 = Transaction.read(
            "0200000000010140b84131c5c582290126bbd8b8e2e5bbd7c2681a4b01314f1b874ea1b5fdf81c0000000000ffffffff014c1d0000000000002251202fcad7470279652cc5f88b8908678d6f4d57af5627183b03fc8404cb4e16d88902473044022066d6939ea701db5d306fb948aea64af196ae52fc34d62c2e7992f62cdabc791402200abdac6766105457ceabcbe55a2d33f064d515210085f7af1248d273442e2b2a012103476f0d6a85ced4a85b08cbabbff28564a1ba31091b38f10b167f4fe1e1c9c4f900d40a00"
        )
        val tx2 = Transaction.read(
            "02000000000101b41b20295ac85fd2ae3e3d02900f1a1e7ddd6139b12e341386189c03d6f5795b0000000000fdffffff0100000000000000003c6a3a546878205361746f7368692120e2889e2f32316d696c20466972737420546170726f6f74206d756c7469736967207370656e64202d426974476f044123b1d4ff27b16af4b0fcb9672df671701a1a7f5a6bb7352b051f461edbc614aa6068b3e5313a174f90f3d95dc4e06f69bebd9cf5a3098fde034b01e69e8e788901400fd4a0d3f36a1f1074cb15838a48f572dc18d412d0f0f0fc1eeda9fa4820c942abb77e4d1a3c2b99ccf4ad29d9189e6e04a017fe611748464449f681bc38cf394420febe583fa77e49089f89b78fa8c116710715d6e40cc5f5a075ef1681550dd3c4ad20d0fa46cb883e940ac3dc5421f05b03859972639f51ed2eccbf3dc5a62e2e1b15ac41c02e44c9e47eaeb4bb313adecd11012dfad435cd72ce71f525329f24d75c5b9432774e148e9209baf3f1656a46986d5f38ddf4e20912c6ac28f48d6bf747469fb100000000"
        )
        Transaction.correctlySpends(tx2, tx1, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    @Test
    fun spendOpCheckSigAdd1() {
        val inputs = listOf(
            Transaction.read(
                "010000000409cc8928f1d3ea4855dedbff8b783e3379735817b072df569776b5c5187d09ca010000006b483045022100a885cea8709cbb93b8311bf2fd5a30ff3e9fc02459652ebb040f47efc70cf51e02202194d53c2fe26cafcdf5748722949a275faf8575d15e2967d1bd3010d652c21b012102682e5ebd58a7d62a87b0490572776e4bb87dc868fb06419d8f33fe41c21160f9ffffffff0fbd54556226c210849929c0c50c00fc472ab4448be0333aa59f335c4e5a088b010000006b483045022100c4c368a8696a200e2d815c0d7cba690e415e3af6f6a0472b28c292ab85ffaa7002207911811c71ac927c48c47797fe8790a0d7ba172a7005ee8036e70e481909a375012102682e5ebd58a7d62a87b0490572776e4bb87dc868fb06419d8f33fe41c21160f9ffffffffa5091f20a2f91e56811e0d979b2dd7126c58dcd2d767d379e25c0a09c3c526fb010000006a473044022046bf081055f3409cee71cedb396a28060f1166195130cb8bedd6a13ecd1f6beb0220602ebd6e0a7b2c39bcfb59b42035dc246ec2a87e2e9f4b71ffa13feb15167615012102682e5ebd58a7d62a87b0490572776e4bb87dc868fb06419d8f33fe41c21160f9ffffffffb2c3b6434a7bda252db8aeb975ea5ca58da36a461545bb634dddadf5e35c6607010000006b483045022100a0466b24f77b68c54748d1c9ac43559eb91f952928b3fe28e452e619f814f23d022003853b255707400301cedd7922256d623ba2fc60d2734f19f79b2c6f0f61c3d4012102682e5ebd58a7d62a87b0490572776e4bb87dc868fb06419d8f33fe41c21160f9ffffffff01273f110000000000225120667bdd93c7c029767fd516d2ea292624b938fefefa175ac9f1220cf508963ff300000000"
            ),
            Transaction.read(
                "02000000000101fe9d111c806dbf9fa4f03869a42ff81972691b86db5c3ef89381456b4422d3be0000000000ffffffff023075000000000000225120667bdd93c7c029767fd516d2ea292624b938fefefa175ac9f1220cf508963ff30000000000000000116a0f676d20746170726f6f7420f09fa59502473044022001ce176bf7357e12a873b4e439d53eb02f1a642a043a6b7e9e5ae46d0d152f8c02204d603e93f49205624eb56c686fc759cc8d11000f4df76c24bda62d790f13d1ff012102e484e53bcce92e801a29454dae07812d6999bf1133aca94c8b03c65b56bdd08d00000000"
            )
        )
        val tx = Transaction.read(
            "010000000001022373cf02ce7df6500ae46a4a0fbbb1b636d2debed8f2df91e2415627397a34090000000000fdffffff88c23d928893cd3509845516cf8411b7cab2738c054cc5ce7e4bde9586997c770000000000fdffffff0200000000000000002b6a29676d20746170726f6f7420f09fa5952068747470733a2f2f626974636f696e6465766b69742e6f72676e9e1100000000001976a91405070d0290da457409a37db2e294c1ffbc52738088ac04410adf90fd381d4a13c3e73740b337b230701189ed94abcb4030781635f035e6d3b50b8506470a68292a2bc74745b7a5732a28254b5f766f09e495929ec308090b01004620c13e6d193f5d04506723bd67abcc5d31b610395c445ac6744cb0a1846b3aabaeac20b0e2e48ad7c3d776cf6f2395c504dc19551268ea7429496726c5d5bf72f9333cba519c21c0000000000000000000000000000000000000000000000000000000000000000104414636070d21adc8280735383102f7a0f5978cea257777a23934dd3b458b79bf388aca218e39e23533a059da173e402c4fc5e3375e1f839efb22e9a5c2a815b07301004620c13e6d193f5d04506723bd67abcc5d31b610395c445ac6744cb0a1846b3aabaeac20b0e2e48ad7c3d776cf6f2395c504dc19551268ea7429496726c5d5bf72f9333cba519c21c0000000000000000000000000000000000000000000000000000000000000000100000000"
        )
        Transaction.correctlySpends(tx, inputs, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    @Test
    fun multisigViaOpCheckSigAdd() {
        val privs = arrayOf(
            PrivateKey(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")),
            PrivateKey(ByteVector32("0101010101010101010101010101010101010101010101010101010101010102")),
            PrivateKey(ByteVector32("0101010101010101010101010101010101010101010101010101010101010103"))
        )

        val script = listOf(
            OP_PUSHDATA(privs[0].xOnlyPublicKey()), OP_CHECKSIG,
            OP_PUSHDATA(privs[1].xOnlyPublicKey()), OP_CHECKSIGADD,
            OP_PUSHDATA(privs[2].xOnlyPublicKey()), OP_CHECKSIGADD,
            OP_2, OP_GREATERTHANOREQUAL
        )
        val scriptTree = ScriptTree.Leaf(script)
        val internalPubkey = PublicKey.fromHex("0250929b74c1a04954b78b4b6035e97a5e078a5a0f28ec96d547bfee9ace803ac0").xOnly()

        val fundingTx = Transaction(
            version = 2,
            txIn = listOf(),
            txOut = listOf(TxOut(Satoshi(1_000_000), Script.pay2tr(internalPubkey, scriptTree))),
            lockTime = 0
        )

        val tmp = Transaction(
            version = 2,
            txIn = listOf(TxIn(OutPoint(fundingTx, 0), TxIn.SEQUENCE_FINAL)),
            txOut = listOf(
                TxOut(
                    fundingTx.txOut[0].amount - Satoshi(5000),
                    Bitcoin.addressToPublicKeyScript(
                        Block.RegtestGenesisBlock.hash,
                        "bcrt1qdtu5cwyngza8hw8s5uk2erlrkh8ceh3msp768v"
                    ).right!!
                )
            ),
            lockTime = 0
        )

        val sigs = privs.map {
            Transaction.signInputTaprootScriptPath(
                it, tmp, 0, listOf(fundingTx.txOut[0]),
                SigHash.SIGHASH_DEFAULT, scriptTree.hash()
            )
        }

        // 3 copies of sig[0] is not enough
        val badTx = tmp.updateWitness(
            0,
            Script.witnessScriptPathPay2tr(internalPubkey, scriptTree, ScriptWitness(listOf(sigs[0], sigs[0], sigs[0])), scriptTree)
        )
        assertThrows {
            Transaction.correctlySpends(badTx, fundingTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }

        val tx1 = tmp.updateWitness(
            0,
            Script.witnessScriptPathPay2tr(internalPubkey, scriptTree, ScriptWitness(listOf(ByteVector.empty, sigs[1], sigs[0])), scriptTree)
        )
        Transaction.correctlySpends(tx1, fundingTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        val tx2 = tmp.updateWitness(
            0,
            Script.witnessScriptPathPay2tr(internalPubkey, scriptTree, ScriptWitness(listOf(sigs[2], ByteVector.empty, sigs[0])), scriptTree)
        )
        Transaction.correctlySpends(tx2, fundingTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        val tx3 = tmp.updateWitness(
            0,
            Script.witnessScriptPathPay2tr(internalPubkey, scriptTree, ScriptWitness(listOf(sigs[2], sigs[1], sigs[0])), scriptTree)
        )
        Transaction.correctlySpends(tx3, fundingTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    @Test
    fun signetPayToScriptKeyPath() {
        val privs = arrayOf(
            PrivateKey(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")),
            PrivateKey(ByteVector32("0101010101010101010101010101010101010101010101010101010101010102")),
            PrivateKey(ByteVector32("0101010101010101010101010101010101010101010101010101010101010103"))
        )
        val scripts = privs.map { listOf(OP_PUSHDATA(it.publicKey().xOnly().value), OP_CHECKSIG) }
        val leaves = scripts.map { ScriptTree.Leaf(it) }
        val scriptTree = ScriptTree.Branch(
            ScriptTree.Branch(leaves[0], leaves[1]),
            leaves[2]
        )
        val blockchain = Block.SignetGenesisBlock.hash
        val internalPubkey = privs[0].publicKey().xOnly()
        val (tweakedKey, _) = internalPubkey.outputKey(scriptTree.hash())

        val script = Script.pay2tr(internalPubkey, scriptTree)
        assertEquals(tweakedKey, Script.pay2trOutputKey(script))
        val bip350Address = Bech32.encodeWitnessAddress(
            Bech32.hrp(blockchain),
            1.toByte(),
            tweakedKey.value.toByteArray()
        )
        assertEquals("tb1p78gx95syx0qz8w5nftk8t7nce78zlpqpsxugcvq5xpfy4tvn6rasd7wk0y", bip350Address)
        val sweepScript = Bitcoin.addressToPublicKeyScript(blockchain, "tb1qxy9hhxkw7gt76qrm4yzw4j06gkk4evryh8ayp7").right!!

        val fundingTx = Transaction.read(
            "020000000001017034061243a7770f791aa2afdb118be900f4f8fc755a36d8632213acc139bab20100000000feffffff0200e1f50500000000225120f1d062d20433c023ba934aec75fa78cf8e2f840181b88c301430524aad93d0fbc192ac1700000000160014b66f2e807b9f4adecb99ad811dde501ca3f0fd5f02473044022046a2fd077e12b1d7ba74f6e7ac469deb3e3755c100216abad667980fc39463dc022018b63cfaf72fde0b5ca10c617aeaa0015013bd06ef08f82eea500c6467d963cc0121030b50ec81d958ae79d34d3579faf72456213d7d581a908e2b64d21b96777882043ab10100"
        )
        assertEquals(fundingTx.txOut[0].publicKeyScript, Script.write(script).byteVector())

        val tmp = Transaction(
            version = 2,
            txIn = listOf(TxIn(OutPoint(fundingTx, 0), TxIn.SEQUENCE_FINAL)),
            txOut = listOf(TxOut(fundingTx.txOut[0].amount - Satoshi(5000), sweepScript)),
            lockTime = 0
        )
        val sig = Transaction.signInputTaprootKeyPath(
            privs[0], tmp, 0, listOf(fundingTx.txOut[0]),
            SigHash.SIGHASH_DEFAULT, scriptTree
        )
        val tx = tmp.updateWitness(0, Script.witnessKeyPathPay2tr(sig))
        assertEquals(
            "02000000000101c1952516d2f512e8ec29ffe576fcb13903987434ce22479f2e18b5060f0184c20000000000ffffffff0178cdf50500000000160014310b7b9acef217ed007ba904eac9fa45ad5cb064014004174022193d585759ce094bbe47ff23eef0238aaa89a89a0d04c80fa321c9b9056623282c49cfa7388409af5ef9a1ab7e3733b72637edcfb15019018d4d7f5a00000000",
            tx.toString()
        )
        Transaction.correctlySpends(tx, fundingTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    @Test
    fun scriptTreeSerializationReferenceVector() {
        val encoded = "02c02220736e572900fe1252589a2143c8f3c79f71a0412d2353af755e9701c782694a02ac02c02220631c5f3b5832b8fbdebfb19704ceeb323c21f40f7a24f43d68ef0cc26b125969ac01c0222044faa49a0338de488c8dfffecdfb6f329f380bd566ef20c8df6d813eab1c4273ac"
        val tree = ScriptTree.read(com.gorunjinian.metrovault.lib.bitcoin.io.ByteArrayInput(fr.acinq.secp256k1.Hex.decode(encoded)))
        val leaves = listOf(
            ScriptTree.Leaf("20736e572900fe1252589a2143c8f3c79f71a0412d2353af755e9701c782694a02ac", 0xc0),
            ScriptTree.Leaf("20631c5f3b5832b8fbdebfb19704ceeb323c21f40f7a24f43d68ef0cc26b125969ac", 0xc0),
            ScriptTree.Leaf("2044faa49a0338de488c8dfffecdfb6f329f380bd566ef20c8df6d813eab1c4273ac", 0xc0)
        )
        assertEquals(
            ScriptTree.Branch(ScriptTree.Branch(leaves[0], leaves[1]), leaves[2]),
            tree
        )
        for (leaf in leaves) {
            assertEquals(leaf, tree.findScript(leaf.script))
            assertEquals(leaf, tree.findScript(leaf.hash()))
        }
        org.junit.Assert.assertNull(tree.findScript(ByteVector.fromHex("deadbeef")))
    }

    @Test
    fun scriptTreeRandomRoundTrip() {
        val random = kotlin.random.Random.Default

        fun randomLeaf(): ScriptTree.Leaf =
            ScriptTree.Leaf(random.nextBytes(random.nextInt(1, 8)).byteVector(), random.nextInt(255))

        fun randomTree(maxLevel: Int): ScriptTree = when {
            maxLevel == 0 -> randomLeaf()
            random.nextBoolean() -> randomLeaf()
            else -> ScriptTree.Branch(randomTree(maxLevel - 1), randomTree(maxLevel - 1))
        }

        repeat(200) {
            val tree = randomTree(10)
            val roundTripped = ScriptTree.read(com.gorunjinian.metrovault.lib.bitcoin.io.ByteArrayInput(tree.write()))
            assertEquals(tree, roundTripped)
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
