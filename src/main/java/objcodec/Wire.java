package objcodec;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * 线格式常量与 varint 读写。
 *
 * <p>对外语义以 README 的「对外契约」一节为准；这里是按当前代码走出来的行为。
 */
public final class Wire {

    /** varint 编码（INT32 / INT64 / BOOL）。 */
    public static final int VARINT = 0;

    /** 8 字节定长（保留的 unknown wireType）。 */
    public static final int FIXED64 = 1;

    /** 长度前缀 + 内容（STRING / BYTES / MESSAGE）。 */
    public static final int LEN = 2;

    /** 4 字节定长（保留的 unknown wireType）。 */
    public static final int FIXED32 = 5;

    private Wire() {
    }

    /** 字段类型对应的 wireType。 */
    public static int wireTypeOf(Field.Type type) {
        switch (type) {
            case INT32:
            case INT64:
            case BOOL:
                return VARINT;
            case STRING:
            case BYTES:
            case MESSAGE:
                return LEN;
            default:
                throw new CodecException("未知字段类型：" + type);
        }
    }

    /** 把 {@code value} 写成 varint 追加到 {@code out}。 */
    public static void writeVarint(ByteArrayOutputStream out, long value) {
        long v = value;
        if (v < 0L) {
            v &= 0xFFFF_FFFFL;
        }
        while ((v & ~0x7FL) != 0L) {
            out.write((int) ((v & 0x7FL) | 0x80L));
            v >>>= 7;
        }
        out.write((int) v);
    }

    /** 从 {@code in} 读一个 varint（按无符号解释为 long）。 */
    static long readVarint(ByteBuffer in) {
        long result = 0L;
        int shift = 0;
        while (true) {
            int b = in.get() & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
    }
}
