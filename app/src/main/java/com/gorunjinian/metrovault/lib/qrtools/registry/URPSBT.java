package com.gorunjinian.metrovault.lib.qrtools.registry;

import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;

public class URPSBT extends CryptoPSBT {
    public URPSBT(byte[] psbt) {
        super(psbt);
    }

    @Override
    public RegistryType getRegistryType() {
        return RegistryType.PSBT;
    }

    public static URPSBT fromCbor(DataItem item) {
        return new URPSBT(((ByteString)item).getBytes());
    }
}
