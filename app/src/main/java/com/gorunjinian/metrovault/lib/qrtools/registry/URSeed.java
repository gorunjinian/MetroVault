package com.gorunjinian.metrovault.lib.qrtools.registry;

import co.nstant.in.cbor.model.*;

import java.util.Date;

public class URSeed extends CryptoSeed {
    public URSeed(byte[] seed, Date birthdate) {
        super(seed, birthdate);
    }

    public URSeed(byte[] seed, Date birthdate, String name, String note) {
        super(seed, birthdate, name, note);
    }

    @Override
    public RegistryType getRegistryType() {
        return RegistryType.SEED;
    }

    public static URSeed fromCbor(DataItem item) {
        byte[] seed = null;
        Date birthdate = null;
        String name = null;
        String note = null;

        Map map = (Map)item;
        for(DataItem key : map.getKeys()) {
            UnsignedInteger uintKey = (UnsignedInteger)key;
            int intKey = uintKey.getValue().intValue();
            if(intKey == PAYLOAD_KEY) {
                seed = ((ByteString)map.get(key)).getBytes();
            } else if(intKey == BIRTHDATE_KEY) {
                birthdate = new Date(((UnsignedInteger)map.get(key)).getValue().longValue() * 1000 * 60 * 60 * 24);
            } else if(intKey == NAME_KEY) {
                name = ((UnicodeString)map.get(key)).getString();
            } else if(intKey == NOTE_KEY) {
                note = ((UnicodeString)map.get(key)).getString();
            }
        }

        if(seed == null) {
            throw new IllegalStateException("Seed is null");
        }

        return new URSeed(seed, birthdate, name, note);
    }
}
