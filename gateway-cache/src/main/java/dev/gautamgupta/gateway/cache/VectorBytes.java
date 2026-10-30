package dev.gautamgupta.gateway.cache;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Little-endian float32 blob RediSearch expects for a VECTOR field. */
public final class VectorBytes {

    private VectorBytes() {
    }

    public static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }
}
