package com.gorunjinian.metrovault.lib.qrtools.registry;

import java.util.Arrays;

import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;

public class CryptoSskr extends RegistryItem {

    private final byte[] split;

    public CryptoSskr(byte[] split) {
        this.split = split;
    }

    public byte[] getSplit() {
        return split;
    }

    public DataItem toCbor() {
        return new ByteString(split);
    }

    @Override
    public RegistryType getRegistryType() {
        return RegistryType.CRYPTO_SSKR;
    }

    public static CryptoSskr fromCbor(DataItem item) {
        byte[] itemBytes = ((ByteString)item).getBytes();
        byte[] normalisedSplit = Arrays.copyOfRange(itemBytes, 1, itemBytes.length);
        return new CryptoSskr(normalisedSplit);
    }
}
