package com.gorunjinian.metrovault.lib.qrtools.registry;

import co.nstant.in.cbor.model.DataItem;

public interface CborSerializable {
    DataItem toCbor();
}
