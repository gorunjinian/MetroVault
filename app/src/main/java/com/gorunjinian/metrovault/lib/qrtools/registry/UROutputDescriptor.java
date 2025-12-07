package com.gorunjinian.metrovault.lib.qrtools.registry;

import co.nstant.in.cbor.model.*;

import java.util.ArrayList;
import java.util.List;

public class UROutputDescriptor extends RegistryItem {
    public static final long SOURCE = 1;
    public static final long KEYS = 2;
    public static final long NAME = 3;
    public static final long NOTE = 4;

    private final String source;
    private final List<RegistryItem> keys;
    private final String name;
    private final String note;

    public UROutputDescriptor(String source) {
        this(source, null);
    }

    public UROutputDescriptor(String source, List<RegistryItem> keys) {
        this(source, keys, null, null);
    }

    public UROutputDescriptor(String source, List<RegistryItem> keys, String name, String note) {
        this.source = source;
        this.keys = keys;
        this.name = name;
        this.note = note;

        if(keys != null && !keys.stream().allMatch(item -> item instanceof URHDKey || item instanceof URECKey || item instanceof URAddress)) {
            throw new IllegalArgumentException("All keys must be one of HDKey, ECKey or Address");
        }
    }

    public String getSource() {
        return source;
    }

    public List<RegistryItem> getKeys() {
        return keys;
    }

    public String getName() {
        return name;
    }

    public String getNote() {
        return note;
    }

    public DataItem toCbor() {
        Map map = new Map();
        map.put(new UnsignedInteger(SOURCE), new UnicodeString(source));

        if(keys != null && !keys.isEmpty()) {
            Array array = new Array();
            for(RegistryItem key : keys) {
                DataItem keyItem = key.toCbor();
                if(key instanceof URHDKey) {
                    keyItem.setTag(RegistryType.HDKEY.getTag());
                } else if(key instanceof URECKey) {
                    keyItem.setTag(RegistryType.ECKEY.getTag());
                } else if(key instanceof URAddress) {
                    keyItem.setTag(RegistryType.ADDRESS.getTag());
                }
                array.add(keyItem);
            }
            map.put(new UnsignedInteger(KEYS), array);
        }

        if(name != null) {
            map.put(new UnsignedInteger(NAME), new UnicodeString(name));
        }

        if(note != null) {
            map.put(new UnsignedInteger(NOTE), new UnicodeString(note));
        }

        return map;
    }

    @Override
    public RegistryType getRegistryType() {
        return RegistryType.OUTPUT_DESCRIPTOR;
    }

    public static UROutputDescriptor fromCbor(DataItem item) {
        String source = null;
        List<RegistryItem> keys = null;
        String name = null;
        String note = null;

        Map map = (Map)item;
        for(DataItem key : map.getKeys()) {
            UnsignedInteger uintKey = (UnsignedInteger)key;
            int intKey = uintKey.getValue().intValue();
            if(intKey == SOURCE) {
                source = ((UnicodeString)map.get(key)).getString();
            } else if(intKey == KEYS) {
                Array keyArray = (Array)map.get(key);
                keys = new ArrayList<>(keyArray.getDataItems().size());
                for(DataItem keyItem : keyArray.getDataItems()) {
                    if(keyItem.getTag().getValue() == RegistryType.HDKEY.getTag()) {
                        keys.add(URHDKey.fromCbor(keyItem));
                    } else if(keyItem.getTag().getValue() == RegistryType.ECKEY.getTag()) {
                        keys.add(URECKey.fromCbor(keyItem));
                    } else if(keyItem.getTag().getValue() == RegistryType.ADDRESS.getTag()) {
                        keys.add(URAddress.fromCbor(keyItem));
                    } else {
                        throw new IllegalArgumentException("All keys must be one of HDKey, ECKey or Address");
                    }
                }
            } else if(intKey == NAME) {
                name = ((UnicodeString)map.get(key)).getString();
            } else if(intKey == NOTE) {
                note = ((UnicodeString)map.get(key)).getString();
            }
        }

        if(source == null) {
            throw new IllegalStateException("Source is null");
        }

        return new UROutputDescriptor(source, keys, name, note);
    }
}
