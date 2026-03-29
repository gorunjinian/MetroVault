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
    private final String name;
    private final String note;

    public UROutputDescriptor(String source) {
        this(source, null, null);
    }

    public UROutputDescriptor(String source, String name, String note) {
        this.source = source;
        this.name = name;
        this.note = note;
    }

    public String getSource() {
        return source;
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
        String name = null;
        String note = null;

        Map map = (Map)item;
        for(DataItem key : map.getKeys()) {
            UnsignedInteger uintKey = (UnsignedInteger)key;
            int intKey = uintKey.getValue().intValue();
            if(intKey == SOURCE) {
                source = ((UnicodeString)map.get(key)).getString();
            } else if(intKey == NAME) {
                name = ((UnicodeString)map.get(key)).getString();
            } else if(intKey == NOTE) {
                note = ((UnicodeString)map.get(key)).getString();
            }
            // KEYS field is skipped - we only need the source descriptor string
        }

        if(source == null) {
            throw new IllegalStateException("Source is null");
        }

        return new UROutputDescriptor(source, name, note);
    }
}
