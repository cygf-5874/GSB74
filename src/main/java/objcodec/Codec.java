package objcodec;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 编解码入口。
 *
 * <p>把 {@link Message} 按 {@link Schema} 编码成二进制，或把二进制解回 {@link Message}。
 * 对外语义以 README 的「对外契约」一节为准；这里是按当前代码走出来的行为。
 */
public final class Codec {

    /** 嵌套 MESSAGE 的深度上限（顶层消息深度为 0）。 */
    static final int MAX_DEPTH = 32;

    private final Schema schema;

    public Codec(Schema schema) {
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    public Schema schema() {
        return schema;
    }

    // ------------------------------------------------------------------ 编码

    /** 把 {@code msg} 编码成字节。 */
    public byte[] encode(Message msg) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodeMessage(msg, schema, out);
        return out.toByteArray();
    }

    private void encodeMessage(Message msg, Schema schema, ByteArrayOutputStream out) {
        List<Field> fields = new ArrayList<>(schema.fields());
        fields.sort(Comparator.comparingInt(Field::tag));
        List<UnknownFields.Entry> unknowns = new ArrayList<>(msg.unknown().entries());
        unknowns.sort(Comparator.comparingInt(UnknownFields.Entry::tag));
        int ui = 0;
        for (Field f : fields) {
            while (ui < unknowns.size() && unknowns.get(ui).tag() < f.tag()) {
                writeUnknown(out, unknowns.get(ui++));
            }
            if (!msg.has(f.name())) {
                continue;
            }
            Object value = msg.get(f.name());
            if (value == null) {
                continue;
            }
            int wireType = Wire.wireTypeOf(f.type());
            Wire.writeVarint(out, ((long) f.tag() << 3) | wireType);
            writeBody(out, f, value);
        }
        while (ui < unknowns.size()) {
            writeUnknown(out, unknowns.get(ui++));
        }
    }

    private static void writeUnknown(ByteArrayOutputStream out, UnknownFields.Entry entry) {
        Wire.writeVarint(out, ((long) entry.tag() << 3) | entry.wireType());
        byte[] body = entry.body();
        out.write(body, 0, body.length);
    }

    private void writeBody(ByteArrayOutputStream out, Field field, Object value) {
        switch (field.type()) {
            case INT32:
            case INT64:
                Wire.writeVarint(out, ((Number) value).longValue());
                break;
            case BOOL:
                Wire.writeVarint(out, ((Boolean) value) ? 1L : 0L);
                break;
            case STRING:
                writeLengthDelimited(out, ((String) value).getBytes(StandardCharsets.UTF_8));
                break;
            case BYTES:
                writeLengthDelimited(out, (byte[]) value);
                break;
            case MESSAGE: {
                ByteArrayOutputStream nested = new ByteArrayOutputStream();
                encodeMessage((Message) value, field.messageSchema(), nested);
                writeLengthDelimited(out, nested.toByteArray());
                break;
            }
            default:
                throw new CodecException("未知字段类型：" + field.type());
        }
    }

    private static void writeLengthDelimited(ByteArrayOutputStream out, byte[] body) {
        Wire.writeVarint(out, body.length);
        out.write(body, 0, body.length);
    }

    // ------------------------------------------------------------------ 解码

    /** 从字节数组解码出一个消息。 */
    public Message decode(byte[] data) {
        return decode(ByteBuffer.wrap(data));
    }

    /** 从一个 {@link ByteBuffer} 的当前位置起解码出一个消息；失败时 position 恢复到调用前。 */
    public Message decode(ByteBuffer in) {
        int start = in.position();
        try {
            return decodeMessage(in, schema, 0);
        } catch (CodecException e) {
            in.position(start);
            throw e;
        } catch (RuntimeException e) {
            in.position(start);
            throw new CodecException("解码失败：" + e, e);
        }
    }

    private static Message decodeMessage(ByteBuffer in, Schema schema, int depth) {
        Message msg = new Message();
        while (in.hasRemaining()) {
            long key = Wire.readVarint(in);
            long tagBits = key >>> 3;
            if (tagBits == 0 || tagBits > Integer.MAX_VALUE) {
                throw new CodecException("非法的 tag：" + tagBits);
            }
            int tag = (int) tagBits;
            int wireType = (int) (key & 0x7L);
            Field field = schema.fieldByTag(tag);
            if (field == null) {
                readUnknown(in, tag, wireType, msg.unknown());
                continue;
            }
            if (Wire.wireTypeOf(field.type()) != wireType) {
                throw new CodecException("字段 " + field.name() + " 的 wireType 不符：期望 "
                        + Wire.wireTypeOf(field.type()) + " 实际 " + wireType);
            }
            msg.set(field.name(), readValue(in, field, depth));
        }
        return msg;
    }

    private static Object readValue(ByteBuffer in, Field field, int depth) {
        switch (field.type()) {
            case INT32:
                return (int) Wire.readVarint(in);
            case INT64:
                return Wire.readVarint(in);
            case BOOL:
                return Wire.readVarint(in) != 0L;
            case STRING:
                return new String(readLengthDelimited(in), StandardCharsets.UTF_8);
            case BYTES:
                return readLengthDelimited(in);
            case MESSAGE: {
                if (depth + 1 > MAX_DEPTH) {
                    throw new CodecException("嵌套深度超过上限 " + MAX_DEPTH);
                }
                byte[] body = readLengthDelimited(in);
                return decodeMessage(ByteBuffer.wrap(body), field.messageSchema(), depth + 1);
            }
            default:
                throw new CodecException("未知字段类型：" + field.type());
        }
    }

    private static byte[] readLengthDelimited(ByteBuffer in) {
        long length = Wire.readVarint(in);
        if (length < 0 || length > in.remaining()) {
            throw new CodecException("长度前缀非法或输入截断：" + length
                    + "（剩余 " + in.remaining() + " 字节）");
        }
        byte[] body = new byte[(int) length];
        in.get(body);
        return body;
    }

    private static void readUnknown(ByteBuffer in, int tag, int wireType, UnknownFields unknown) {
        int bodyStart = in.position();
        switch (wireType) {
            case Wire.VARINT:
                Wire.readVarint(in);
                break;
            case Wire.FIXED64:
                requireRemaining(in, 8);
                in.position(in.position() + 8);
                break;
            case Wire.LEN: {
                long length = Wire.readVarint(in);
                if (length < 0 || length > in.remaining()) {
                    throw new CodecException("长度前缀非法或输入截断：" + length
                            + "（剩余 " + in.remaining() + " 字节）");
                }
                in.position(in.position() + (int) length);
                break;
            }
            case Wire.FIXED32:
                requireRemaining(in, 4);
                in.position(in.position() + 4);
                break;
            default:
                throw new CodecException("不支持的 wireType：" + wireType);
        }
        ByteBuffer body = in.duplicate();
        body.limit(in.position()).position(bodyStart);
        byte[] bytes = new byte[body.remaining()];
        body.get(bytes);
        unknown.add(new UnknownFields.Entry(tag, wireType, bytes));
    }

    private static void requireRemaining(ByteBuffer in, int n) {
        if (in.remaining() < n) {
            throw new CodecException("输入截断：需要 " + n + " 字节，仅剩 " + in.remaining());
        }
    }

    // --------------------------------------------------------------- 消息值对象

    /** 一个消息：字段名到值的映射，外加解码时保留下来的未知字段。 */
    public static final class Message {

        private final Map<String, Object> values = new LinkedHashMap<>();
        private final UnknownFields unknown = new UnknownFields();

        private Message() {
        }

        public static Message of() {
            return new Message();
        }

        /** 设置一个字段值；{@code value} 为 {@code null} 时等价于清除该字段。 */
        public Message set(String name, Object value) {
            Objects.requireNonNull(name, "name");
            if (value == null) {
                values.remove(name);
            } else {
                values.put(name, value);
            }
            return this;
        }

        /** 该字段是否已设置。 */
        public boolean has(String name) {
            return values.containsKey(name);
        }

        /** 原始值，未设置为 {@code null}。 */
        public Object get(String name) {
            return values.get(name);
        }

        public int getInt(String name) {
            Object v = values.get(name);
            return v == null ? 0 : ((Number) v).intValue();
        }

        public long getLong(String name) {
            Object v = values.get(name);
            return v == null ? 0L : ((Number) v).longValue();
        }

        public boolean getBool(String name) {
            Object v = values.get(name);
            return v != null && (Boolean) v;
        }

        public String getString(String name) {
            Object v = values.get(name);
            return v == null ? "" : (String) v;
        }

        public byte[] getBytes(String name) {
            Object v = values.get(name);
            return v == null ? new byte[0] : (byte[]) v;
        }

        /** 嵌套消息；未设置为 {@code null}。 */
        public Message getMessage(String name) {
            Object v = values.get(name);
            return (Message) v;
        }

        public UnknownFields unknown() {
            return unknown;
        }
    }
}
