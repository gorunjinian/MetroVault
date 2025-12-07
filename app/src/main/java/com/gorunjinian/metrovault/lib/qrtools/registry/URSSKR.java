package com.gorunjinian.metrovault.lib.qrtools.registry;

import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;

import java.util.Arrays;

public class URSSKR extends CryptoSskr {
    public URSSKR(byte[] split) {
        super(split);
    }

    @Override
    public RegistryType getRegistryType() {
        return RegistryType.SSKR;
    }

    public static URSSKR fromCbor(DataItem item) {
        byte[] itemBytes = ((ByteString)item).getBytes();
        byte[] normalisedSplit = Arrays.copyOfRange(itemBytes, 1, itemBytes.length);
        return new URSSKR(normalisedSplit);
    }
}
