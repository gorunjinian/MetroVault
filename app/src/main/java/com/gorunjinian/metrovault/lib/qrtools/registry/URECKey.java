package com.gorunjinian.metrovault.lib.qrtools.registry;

import co.nstant.in.cbor.model.*;

public class URECKey extends CryptoECKey {
    public URECKey(Integer curve, Boolean privateKey, byte[] data) {
        super(curve, privateKey, data);
    }

    @Override
    public RegistryType getRegistryType() {
        return RegistryType.ECKEY;
    }

    public static URECKey fromCbor(DataItem item) {
        Integer curve = null;
        Boolean privateKey = null;
        byte[] data = null;

        Map map = (Map)item;
        for(DataItem key : map.getKeys()) {
            UnsignedInteger uintKey = (UnsignedInteger)key;
            int intKey = uintKey.getValue().intValue();
            if(intKey == CURVE) {
                curve = ((UnsignedInteger)map.get(key)).getValue().intValue();
            } else if(intKey == PRIVATE) {
                privateKey = (map.get(key) == SimpleValue.TRUE);
            } else if(intKey == DATA) {
                data = ((ByteString)map.get(key)).getBytes();
            }
        }

        if(data == null) {
            throw new IllegalStateException("Data is null");
        }

        return new URECKey(curve, privateKey, data);
    }
}
