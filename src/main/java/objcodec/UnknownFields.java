package objcodec;

import java.util.ArrayList;
import java.util.List;

/**
 * 解码时遇到的、当前 schema 不认识的字段。
 *
 * <p>每个条目保存 {@code tag}、{@code wireType} 以及载荷的**原始字节**
 * （键之后的部分，逐字节保留），以便重新编码时原样写回。
 * 对外语义以 README 的「对外契约」一节为准。
 */
public final class UnknownFields {

    /** 一个未知字段。 */
    public static final class Entry {

        private final int tag;
        private final int wireType;
        private final byte[] body;

        Entry(int tag, int wireType, byte[] body) {
            this.tag = tag;
            this.wireType = wireType;
            this.body = body.clone();
        }

        public int tag() {
            return tag;
        }

        public int wireType() {
            return wireType;
        }

        /** 键之后的原始载荷字节。 */
        public byte[] body() {
            return body.clone();
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    /** 按遇到顺序返回全部未知字段（返回副本）。 */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    void add(Entry entry) {
        entries.add(entry);
    }
}
