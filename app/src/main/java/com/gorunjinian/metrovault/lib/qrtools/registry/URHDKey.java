package com.gorunjinian.metrovault.lib.qrtools.registry;

import co.nstant.in.cbor.model.*;

public class URHDKey extends CryptoHDKey {
    public URHDKey(byte[] key, byte[] chainCode) {
        super(key, chainCode);
    }

    public URHDKey(Boolean privateKey, byte[] key, byte[] chainCode, URCoinInfo useInfo, URKeypath origin, URKeypath children, byte[] parentFingerprint) {
        super(privateKey, key, chainCode, useInfo, origin, children, parentFingerprint);
    }

    public URHDKey(Boolean privateKey, byte[] key, byte[] chainCode, URCoinInfo useInfo, URKeypath origin, URKeypath children, byte[] parentFingerprint, String name, String note) {
        super(privateKey, key, chainCode, useInfo, origin, children, parentFingerprint, name, note);
    }

    public DataItem toCbor() {
        Map map = (Map)super.toCbor();

        DataItem useinfo = map.get(new UnsignedInteger(USE_INFO_KEY));
        if(useinfo != null) {
            useinfo.setTag(RegistryType.COIN_INFO.getTag());
        }

        DataItem origin = map.get(new UnsignedInteger(ORIGIN_KEY));
        if(origin != null) {
            origin.setTag(RegistryType.KEYPATH.getTag());
        }

        DataItem children = map.get(new UnsignedInteger(CHILDREN_KEY));
        if(children != null) {
            children.setTag(RegistryType.KEYPATH.getTag());
        }

        return map;
    }

    @Override
    public RegistryType getRegistryType() {
        return RegistryType.HDKEY;
    }

    public static URHDKey fromCbor(DataItem item) {
        boolean isMasterKey = false;
        Boolean isPrivateKey = null;
        byte[] keyData = null;
        byte[] chainCode = null;
        URCoinInfo useInfo = null;
        URKeypath origin = null;
        URKeypath children = null;
        byte[] parentFingerprint = null;
        String name = null;
        String note = null;

        Map map = (Map)item;
        for(DataItem key : map.getKeys()) {
            UnsignedInteger uintKey = (UnsignedInteger)key;
            int intKey = uintKey.getValue().intValue();
            if(intKey == IS_MASTER_KEY) {
                isMasterKey = (map.get(uintKey) == SimpleValue.TRUE);
            } else if(intKey == IS_PRIVATE_KEY) {
                isPrivateKey = (map.get(uintKey) == SimpleValue.TRUE);
            } else if(intKey == KEY_DATA_KEY) {
                keyData = ((ByteString)map.get(uintKey)).getBytes();
            } else if(intKey == CHAIN_CODE_KEY) {
                chainCode = ((ByteString)map.get(uintKey)).getBytes();
            } else if(intKey == USE_INFO_KEY) {
                useInfo = URCoinInfo.fromCbor(map.get(uintKey));
            } else if(intKey == ORIGIN_KEY) {
                origin = URKeypath.fromCbor(map.get(uintKey));
            } else if(intKey == CHILDREN_KEY) {
                children = URKeypath.fromCbor(map.get(uintKey));
            } else if(intKey == PARENT_FINGERPRINT_KEY) {
                parentFingerprint = bigIntegerToBytes(((UnsignedInteger)map.get(uintKey)).getValue(), 4);
            } else if(intKey == NAME_KEY) {
                name = ((UnicodeString)map.get(uintKey)).getString();
            } else if(intKey == NOTE_KEY) {
                note = ((UnicodeString)map.get(uintKey)).getString();
            }
        }

        if(keyData == null) {
            throw new IllegalStateException("Key data is null");
        }

        if(isMasterKey) {
            if(chainCode == null) {
                throw new IllegalStateException("Chain code data is null");
            }

            return new URHDKey(keyData, chainCode);
        } else {
            return new URHDKey(isPrivateKey, keyData, chainCode, useInfo, origin, children, parentFingerprint, name, note);
        }
    }
}
