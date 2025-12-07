package com.gorunjinian.metrovault.lib.qrtools.registry;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class URAccountDescriptor extends RegistryItem {
    public static final long MASTER_FINGERPRINT_KEY = 1;
    public static final long OUTPUT_DESCRIPTORS_KEY = 2;

    private final byte[] masterFingerprint;
    private final List<UROutputDescriptor> outputDescriptors;

    public URAccountDescriptor(byte[] masterFingerprint, List<UROutputDescriptor> outputDescriptors) {
        this.masterFingerprint = Arrays.copyOfRange(masterFingerprint, masterFingerprint.length - 4, masterFingerprint.length);
        this.outputDescriptors = outputDescriptors;
    }

    public byte[] getMasterFingerprint() {
        return masterFingerprint;
    }

    public List<UROutputDescriptor> getOutputDescriptors() {
        return outputDescriptors;
    }

    public DataItem toCbor() {
        Map map = new Map();
        map.put(new UnsignedInteger(MASTER_FINGERPRINT_KEY), new UnsignedInteger(new BigInteger(1, masterFingerprint)));
        Array array = new Array();
        for(UROutputDescriptor outputDescriptor : outputDescriptors) {
            DataItem dataItem = outputDescriptor.toCbor();
            DataItem tag = dataItem.getTag() == null ? dataItem : dataItem.getTag();
            while(tag.getTag() != null) {
                tag = tag.getTag();
            }
            tag.setTag(RegistryType.OUTPUT_DESCRIPTOR.getTag());
            array.add(dataItem);
        }
        map.put(new UnsignedInteger(OUTPUT_DESCRIPTORS_KEY), array);
        return map;
    }

    @Override
    public RegistryType getRegistryType() {
        return RegistryType.ACCOUNT_DESCRIPTOR;
    }

    public static URAccountDescriptor fromCbor(DataItem cbor) {
        Map accountMap = (Map)cbor;

        UnsignedInteger uintMasterFingerprint = (UnsignedInteger)accountMap.get(new UnsignedInteger(MASTER_FINGERPRINT_KEY));
        byte[] masterFingerprint = bigIntegerToBytes(uintMasterFingerprint.getValue(), 4);
        Array outputDescriptorArray = (Array)accountMap.get(new UnsignedInteger(OUTPUT_DESCRIPTORS_KEY));
        List<UROutputDescriptor> outputDescriptors = new ArrayList<>(outputDescriptorArray.getDataItems().size());
        for(DataItem item : outputDescriptorArray.getDataItems()) {
            outputDescriptors.add(UROutputDescriptor.fromCbor(item));
        }

        return new URAccountDescriptor(masterFingerprint, outputDescriptors);
    }
}
