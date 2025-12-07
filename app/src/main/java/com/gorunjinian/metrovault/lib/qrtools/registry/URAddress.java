package com.gorunjinian.metrovault.lib.qrtools.registry;

import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;

public class URAddress extends CryptoAddress {
    public URAddress(CryptoCoinInfo info, Type type, byte[] data) {
        super(info, type, data);
    }

    @Override
    public RegistryType getRegistryType() {
        return RegistryType.ADDRESS;
    }

    public static URAddress fromCbor(DataItem item) {
        URCoinInfo info = null;
        Type type = null;
        byte[] data = null;

        Map map = (Map)item;
        for(DataItem key : map.getKeys()) {
            UnsignedInteger uintKey = (UnsignedInteger)key;
            int intKey = uintKey.getValue().intValue();
            if(intKey == INFO) {
                info = URCoinInfo.fromCbor(map.get(key));
            } else if(intKey == TYPE) {
                type = Type.values()[((UnsignedInteger)map.get(key)).getValue().intValue()];
            } else if(intKey == DATA) {
                data = ((ByteString)map.get(key)).getBytes();
            }
        }

        if(data == null) {
            throw new IllegalStateException("Data is null");
        }

        return new URAddress(info, type, data);
    }
}
