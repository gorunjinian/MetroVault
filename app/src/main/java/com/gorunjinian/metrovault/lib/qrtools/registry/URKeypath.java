package com.gorunjinian.metrovault.lib.qrtools.registry;

import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.gorunjinian.metrovault.lib.qrtools.registry.pathcomponent.PathComponent;

import java.util.ArrayList;
import java.util.List;

public class URKeypath extends CryptoKeypath {
    public URKeypath(List<PathComponent> components, byte[] sourceFingerprint) {
        super(components, sourceFingerprint);
    }

    public URKeypath(List<PathComponent> components, byte[] sourceFingerprint, Integer depth) {
        super(components, sourceFingerprint, depth);
    }

    @Override
    public RegistryType getRegistryType() {
        return RegistryType.KEYPATH;
    }

    public static URKeypath fromCbor(DataItem item) {
        List<PathComponent> components = new ArrayList<>();
        byte[] sourceFingerprint = null;
        Integer depth = null;

        Map map = (Map)item;
        for(DataItem key : map.getKeys()) {
            UnsignedInteger uintKey = (UnsignedInteger)key;
            int intKey = uintKey.getValue().intValue();
            if(intKey == COMPONENTS_KEY) {
                components = PathComponent.fromCbor(map.get(key));
            } else if(intKey == SOURCE_FINGERPRINT_KEY) {
                sourceFingerprint = bigIntegerToBytes(((UnsignedInteger)map.get(key)).getValue(), 4);
            } else if(intKey == DEPTH_KEY) {
                depth = ((UnsignedInteger)map.get(key)).getValue().intValue();
            }
        }

        return new URKeypath(components, sourceFingerprint, depth);
    }
}
