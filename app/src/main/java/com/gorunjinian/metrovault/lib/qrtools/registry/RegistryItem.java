package com.gorunjinian.metrovault.lib.qrtools.registry;

import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import com.gorunjinian.bcur.UR;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Arrays;

public abstract class RegistryItem implements CborSerializable {
    public abstract RegistryType getRegistryType();

    public UR toUR() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            CborEncoder encoder = new CborEncoder(baos);
            encoder.encode(toCbor());
            return new UR(getRegistryType().toString(), baos.toByteArray());
        } catch (CborException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * <p>
     * The regular {@link BigInteger#toByteArray()} includes the sign bit of the
     * number and
     * might result in an extra byte addition. This method removes this extra byte.
     * </p>
     *
     * @param b the integer to format into a byte array
     * @return numBytes byte long array.
     */
    protected static byte[] bigIntegerToBytes(BigInteger b) {
        if (b.signum() < 0) {
            throw new IllegalArgumentException("b must be positive or zero");
        }
        byte[] src = b.toByteArray();
        byte[] dest = new byte[4];
        boolean isFirstByteOnlyForSign = src[0] == 0;
        int length = isFirstByteOnlyForSign ? src.length - 1 : src.length;
        if (length > 4) {
            throw new IllegalArgumentException("The given number does not fit in " + 4);
        }
        int srcPos = isFirstByteOnlyForSign ? 1 : 0;
        int destPos = 4 - length;
        System.arraycopy(src, srcPos, dest, destPos, length);
        Arrays.fill(src, (byte) 0);
        return dest;
    }
}
