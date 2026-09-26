package objcodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一份 schema：一组字段，按声明顺序保存；对外可按 {@code tag} 或名字查询。
 *
 * <p>对外语义以 README 的「对外契约」一节为准。
 */
public final class Schema {

    private final List<Field> fields;
    private final Map<Integer, Field> byTag;
    private final Map<String, Field> byName;

    private Schema(List<Field> fields) {
        this.fields = List.copyOf(fields);
        this.byTag = new LinkedHashMap<>();
        this.byName = new LinkedHashMap<>();
        for (Field f : fields) {
            byTag.put(f.tag(), f);
            byName.put(f.name(), f);
        }
    }

    /** 用一组字段构造 schema。 */
    public static Schema of(Field... fields) {
        Builder b = builder();
        for (Field f : fields) {
            b.add(f);
        }
        return b.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 按声明顺序返回全部字段。 */
    public List<Field> fields() {
        return fields;
    }

    /** 按 tag 查字段，找不到返回 {@code null}。 */
    public Field fieldByTag(int tag) {
        return byTag.get(tag);
    }

    /** 按名字查字段，找不到返回 {@code null}。 */
    public Field fieldByName(String name) {
        return byName.get(name);
    }

    /** schema 构造器。 */
    public static final class Builder {

        private final List<Field> fields = new ArrayList<>();

        private Builder() {
        }

        public Builder field(int tag, Field.Type type, String name) {
            return add(Field.scalar(tag, type, name));
        }

        public Builder message(int tag, String name, Schema messageSchema) {
            return add(Field.message(tag, name, messageSchema));
        }

        public Builder add(Field field) {
            fields.add(Objects.requireNonNull(field, "field"));
            return this;
        }

        /** 校验（tag 唯一）并构造 schema。 */
        public Schema build() {
            Map<Integer, Field> seen = new LinkedHashMap<>();
            for (Field f : fields) {
                Field prev = seen.put(f.tag(), f);
                if (prev != null) {
                    throw new CodecException("重复的 tag：" + f.tag()
                            + "（" + prev.name() + " 与 " + f.name() + "）");
                }
            }
            return new Schema(fields);
        }
    }
}
