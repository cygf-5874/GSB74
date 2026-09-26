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
        for (Field f : fields) {
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

    /** 从一个 {@link ByteBuffer} 的当前位置起解码出一个消息。 */
    public Message decode(ByteBuffer in) {
        return decodeMessage(in);
    }

    private Message decodeMessage(ByteBuffer in) {
        Message msg = new Message();
        while (in.hasRemaining()) {
            long key = Wire.readVarint(in);
            int tag = (int) (key >>> 3);
            int wireType = (int) (key & 0x7L);
            Field field = schema.fieldByTag(tag);
            if (field == null) {
                skipField(in, wireType);
                continue;
            }
            if (Wire.wireTypeOf(field.type()) != wireType) {
                throw new CodecException("字段 " + field.name() + " 的 wireType 不符：期望 "
                        + Wire.wireTypeOf(field.type()) + " 实际 " + wireType);
            }
            msg.set(field.name(), readValue(in, field));
        }
        return msg;
    }

    private Object readValue(ByteBuffer in, Field field) {
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
                int length = (int) Wire.readVarint(in);
                ByteBuffer nested = in.slice();
                nested.limit(length);
                Message child = new Codec(field.messageSchema()).decodeMessage(nested);
                in.position(in.position() + length);
                return child;
            }
            default:
                throw new CodecException("未知字段类型：" + field.type());
        }
    }

    private static byte[] readLengthDelimited(ByteBuffer in) {
        int length = (int) Wire.readVarint(in);
        byte[] body = new byte[length];
        in.get(body);
        return body;
    }

    private static void skipField(ByteBuffer in, int wireType) {
        switch (wireType) {
            case Wire.VARINT:
                Wire.readVarint(in);
                return;
            case Wire.FIXED64:
                in.position(in.position() + 8);
                return;
            case Wire.LEN: {
                int length = (int) Wire.readVarint(in);
                in.position(in.position() + length);
                return;
            }
            case Wire.FIXED32:
                in.position(in.position() + 4);
                return;
            default:
                throw new CodecException("不支持的 wireType：" + wireType);
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
