package com.gorunjinian.metrovault.lib.qrtools.registry;

import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;

public class URCoinInfo extends CryptoCoinInfo {
    public URCoinInfo(Integer type, Integer network) {
        super(type, network);
    }

    public URCoinInfo(Type type, Network network) {
        super(type, network);
    }

    @Override
    public RegistryType getRegistryType() {
        return RegistryType.COIN_INFO;
    }

    public static URCoinInfo fromCbor(DataItem item) {
        Integer type = null;
        Integer network = null;

        Map map = (Map)item;
        for(DataItem key : map.getKeys()) {
            UnsignedInteger uintKey = (UnsignedInteger)key;
            int intKey = uintKey.getValue().intValue();

            if(intKey == TYPE_KEY) {
                type = ((UnsignedInteger)map.get(key)).getValue().intValue();
            } else if(intKey == NETWORK_KEY) {
                network = ((UnsignedInteger)map.get(key)).getValue().intValue();
            }
        }

        return new URCoinInfo(type, network);
    }
}
