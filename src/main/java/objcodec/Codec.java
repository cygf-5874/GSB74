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
        List<UnknownFields.Entry> unknown = msg.unknown().entries();
        int fi = 0;
        int ui = 0;
        while (fi < fields.size() || ui < unknown.size()) {
            int fieldTag = fi < fields.size() ? fields.get(fi).tag() : Integer.MAX_VALUE;
            int unknownTag = ui < unknown.size() ? unknown.get(ui).tag() : Integer.MAX_VALUE;
            if (unknownTag < fieldTag) {
                UnknownFields.Entry e = unknown.get(ui++);
                Wire.writeVarint(out, ((long) e.tag() << 3) | e.wireType());
                byte[] body = e.body();
                out.write(body, 0, body.length);
                continue;
            }
            Field f = fields.get(fi++);
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

    /**
     * 从一个 {@link ByteBuffer} 的当前位置起解码出一个消息。
     *
     * <p>失败时抛 {@link CodecException}，并把 {@code in} 的 {@code position} 恢复到调用前的值。
     */
    public Message decode(ByteBuffer in) {
        int start = in.position();
        try {
            return decodeMessage(in, 0);
        } catch (CodecException e) {
            in.position(start);
            throw e;
        } catch (RuntimeException e) {
            in.position(start);
            throw new CodecException("解码失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private Message decodeMessage(ByteBuffer in, int depth) {
        Message msg = new Message();
        while (in.hasRemaining()) {
            long key = Wire.readVarint(in);
            long tagBits = key >>> 3;
            int wireType = (int) (key & 0x7L);
            if (tagBits < Field.MIN_TAG || tagBits > Field.MAX_TAG) {
                throw new CodecException("非法 tag：" + tagBits);
            }
            int tag = (int) tagBits;
            Field field = schema.fieldByTag(tag);
            if (field == null) {
                msg.unknown().add(readUnknown(in, tag, wireType));
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

    private Object readValue(ByteBuffer in, Field field, int depth) {
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
                int length = readLength(in);
                ByteBuffer nested = in.slice();
                nested.limit(length);
                Message child = new Codec(field.messageSchema()).decodeMessage(nested, depth + 1);
                in.position(in.position() + length);
                return child;
            }
            default:
                throw new CodecException("未知字段类型：" + field.type());
        }
    }

    /** 读长度前缀并校验不越界。 */
    private static int readLength(ByteBuffer in) {
        long length = Wire.readVarint(in);
        if (length < 0 || length > in.remaining()) {
            throw new CodecException("长度前缀越界：" + length + "（剩余 " + in.remaining() + " 字节）");
        }
        return (int) length;
    }

    private static byte[] readLengthDelimited(ByteBuffer in) {
        int length = readLength(in);
        byte[] body = new byte[length];
        in.get(body);
        return body;
    }

    private static void skipBytes(ByteBuffer in, int count) {
        if (count > in.remaining()) {
            throw new CodecException("输入截断：需要 " + count + " 字节，剩余 " + in.remaining());
        }
        in.position(in.position() + count);
    }

    /** 读一个未知字段，把键之后的载荷原始字节保留下来。 */
    private static UnknownFields.Entry readUnknown(ByteBuffer in, int tag, int wireType) {
        int bodyStart = in.position();
        switch (wireType) {
            case Wire.VARINT:
                Wire.readVarint(in);
                break;
            case Wire.FIXED64:
                skipBytes(in, 8);
                break;
            case Wire.LEN: {
                int length = readLength(in);
                skipBytes(in, length);
                break;
            }
            case Wire.FIXED32:
                skipBytes(in, 4);
                break;
            default:
                throw new CodecException("不支持的 wireType：" + wireType);
        }
        int bodyEnd = in.position();
        ByteBuffer view = in.duplicate();
        view.limit(bodyEnd);
        view.position(bodyStart);
        byte[] body = new byte[bodyEnd - bodyStart];
        view.get(body);
        return new UnknownFields.Entry(tag, wireType, body);
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
