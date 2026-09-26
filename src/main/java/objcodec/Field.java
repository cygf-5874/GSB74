package objcodec;

import java.util.Objects;

/**
 * schema 里的一个字段：{@code tag}、{@code type}、{@code name}，{@code MESSAGE} 字段另带嵌套 schema。
 *
 * <p>对外语义以 README 的「对外契约」一节为准。
 */
public final class Field {

    /** 字段类型。 */
    public enum Type {
        INT32,
        INT64,
        STRING,
        BYTES,
        BOOL,
        MESSAGE
    }

    /** 合法 tag 下界。 */
    public static final int MIN_TAG = 1;

    /** 合法 tag 上界。 */
    public static final int MAX_TAG = 65535;

    private final int tag;
    private final Type type;
    private final String name;
    private final Schema messageSchema;

    private Field(int tag, Type type, String name, Schema messageSchema) {
        if (tag < MIN_TAG || tag > MAX_TAG) {
            throw new CodecException("tag 必须在 [" + MIN_TAG + ", " + MAX_TAG + "] 内：" + tag);
        }
        this.tag = tag;
        this.type = Objects.requireNonNull(type, "type");
        this.name = Objects.requireNonNull(name, "name");
        this.messageSchema = messageSchema;
    }

    /** 构造一个标量字段。 */
    public static Field scalar(int tag, Type type, String name) {
        if (type == Type.MESSAGE) {
            throw new CodecException("MESSAGE 字段请用 Field.message");
        }
        return new Field(tag, type, name, null);
    }

    /** 构造一个嵌套消息字段。 */
    public static Field message(int tag, String name, Schema messageSchema) {
        return new Field(tag, Type.MESSAGE, name, Objects.requireNonNull(messageSchema, "messageSchema"));
    }

    public int tag() {
        return tag;
    }

    public Type type() {
        return type;
    }

    public String name() {
        return name;
    }

    /** {@code MESSAGE} 字段的嵌套 schema；其它类型为 {@code null}。 */
    public Schema messageSchema() {
        return messageSchema;
    }
}
