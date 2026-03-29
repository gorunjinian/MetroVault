package com.gorunjinian.metrovault.lib.qrtools.registry;

import androidx.annotation.NonNull;

public enum RegistryType {
    BYTES("bytes", null),
    CBOR_PNG("cbor-png", null),
    CBOR_SVG("cbor-svg", null),
    COSE_SIGN("cose-sign", 98),
    COSE_SIGN1("cose-sign1", 18),
    COSE_ENCRYPT("cose-encrypt", 96),
    COSE_ENCRYPT0("cose-encrypt0", 16),
    COSE_MAC("cose-mac", 97),
    COSE_MAC0("cose-mac0", 17),
    COSE_KEY("cose-key", null),
    COSE_KEYSET("cose-keyset", null),
    CRYPTO_SEED("crypto-seed", 300),
    CRYPTO_BIP39("crypto-bip39", 301),
    CRYPTO_HDKEY("crypto-hdkey", 303),
    CRYPTO_KEYPATH("crypto-keypath", 304),
    CRYPTO_COIN_INFO("crypto-coininfo", 305),
    CRYPTO_ECKEY("crypto-eckey", 306),
    CRYPTO_ADDRESS("crypto-address", 307),
    CRYPTO_OUTPUT("crypto-output", 308),
    CRYPTO_SSKR("crypto-sskr", 309),
    CRYPTO_PSBT("crypto-psbt", 310),
    CRYPTO_ACCOUNT("crypto-account", 311),
    SEED("seed", 40300),
    HDKEY("hdkey", 40303),
    KEYPATH("keypath", 40304),
    COIN_INFO("coininfo", 40305),
    ECKEY("eckey", 40306),
    ADDRESS("address", 40307),
    OUTPUT_DESCRIPTOR("output-descriptor", 40308),
    SSKR("sskr", 40309),
    PSBT("psbt", 40310),
    ACCOUNT_DESCRIPTOR("account-descriptor", 40311);

    private final String type;
    private final Integer tag;

    RegistryType(String type, Integer tag) {
        this.type = type;
        this.tag = tag;
    }

    public String getType() {
        return type;
    }

    public Integer getTag() {
        return tag;
    }

    @NonNull
    @Override
    public String toString() {
        return type;
    }

    public static RegistryType fromString(String type) {
        for(RegistryType registryType : values()) {
            if(registryType.toString().equals(type.toLowerCase())) {
                return registryType;
            }
        }

        throw new IllegalArgumentException("Unknown UR registry type: " + type);
    }
}
