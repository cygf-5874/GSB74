import objcodec.Codec;
import objcodec.Codec.Message;
import objcodec.CodecException;
import objcodec.Field;
import objcodec.Field.Type;
import objcodec.Schema;
import objcodec.UnknownFields;
import objcodec.Wire;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * objcodec 的固定验收程序（勿改）。
 *
 * <p>10 个场景覆盖 README「对外契约」的 10 条：basic 4 / evolution 3 / unknown 2 / robust 1。
 * 每个场景独立判定，失败不早退，一次把问题都暴露出来。
 *
 * <p>用法：java -cp out Checker [-list] [--only &lt;组名&gt;[,&lt;组名&gt;...]]
 */
public final class Checker {

    private interface Body {
        /** 返回 null 表示 PASS，否则返回可判定的失败原因。 */
        String run() throws Exception;
    }

    private static final class Scenario {
        final String group;
        final String name;
        final Body body;

        Scenario(String name, Body body) {
            this.name = name;
            this.body = body;
            int slash = name.indexOf('/');
            this.group = slash < 0 ? name : name.substring(0, slash);
        }
    }

    public static void main(String[] args) {
        List<String> only = new ArrayList<>();
        boolean list = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("-list".equals(a)) {
                list = true;
            } else if ("--only".equals(a)) {
                if (i + 1 >= args.length) {
                    System.out.println("--only 需要一个组名");
                    System.exit(2);
                }
                for (String g : args[++i].split(",")) {
                    if (!g.isEmpty()) {
                        only.add(g);
                    }
                }
            } else {
                System.out.println("未知参数：" + a);
                System.exit(2);
            }
        }

        List<Scenario> all = scenarios();

        if (list) {
            for (Scenario s : all) {
                System.out.println(s.name);
            }
            return;
        }

        int total = 0;
        int pass = 0;
        for (Scenario s : all) {
            if (!only.isEmpty() && !only.contains(s.group)) {
                continue;
            }
            total++;
            String err;
            try {
                err = s.body.run();
            } catch (Throwable t) {
                err = "检查过程抛异常：" + t;
            }
            if (err == null) {
                pass++;
                System.out.println("PASS " + s.name);
            } else {
                System.out.println("FAIL " + s.name + "  " + err);
            }
        }
        if (total == 0) {
            System.out.println("没有匹配的场景（--only " + only + "）");
            System.exit(2);
        }
        System.out.println("结果：通过 " + pass + "/" + total);
        if (pass != total) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------- 场景表

    private static List<Scenario> scenarios() {
        List<Scenario> out = new ArrayList<>();

        // ---------------- basic ----------------

        out.add(new Scenario("basic/scalar-roundtrip", () -> {
            Schema schema = Schema.builder()
                    .field(1, Type.INT32, "i32")
                    .field(2, Type.INT64, "i64")
                    .field(3, Type.STRING, "str")
                    .field(4, Type.BYTES, "raw")
                    .field(5, Type.BOOL, "flag")
                    .build();
            Codec codec = new Codec(schema);
            byte[] raw = new byte[] {0, 1, (byte) 0xFE, (byte) 0xFF, 3};
            byte[] data = codec.encode(Message.of()
                    .set("i32", 7)
                    .set("i64", 9876543210L)
                    .set("str", "你好 objcodec")
                    .set("raw", raw)
                    .set("flag", true));
            Message d = codec.decode(data);
            if (d.getInt("i32") != 7) {
                return "期望=i32=7 实际=" + d.getInt("i32");
            }
            if (d.getLong("i64") != 9876543210L) {
                return "期望=i64=9876543210 实际=" + d.getLong("i64");
            }
            if (!"你好 objcodec".equals(d.getString("str"))) {
                return "期望=str 原样往返 实际=" + d.getString("str");
            }
            if (!Arrays.equals(raw, d.getBytes("raw"))) {
                return "期望=bytes 原样往返 实际=" + Arrays.toString(d.getBytes("raw"));
            }
            if (!d.getBool("flag")) {
                return "期望=flag=true 实际=false";
            }
            if (!d.has("i32") || !d.has("flag")) {
                return "期望=已设置字段 has()=true 实际=false";
            }
            return null;
        }));

        out.add(new Scenario("basic/tag-ascending", () -> {
            Schema schema = Schema.builder()
                    .field(9, Type.INT32, "n9")
                    .field(2, Type.INT32, "n2")
                    .field(5, Type.INT32, "n5")
                    .field(1, Type.INT32, "n1")
                    .build();
            Codec codec = new Codec(schema);
            byte[] data = codec.encode(Message.of()
                    .set("n9", 90)
                    .set("n2", 20)
                    .set("n5", 50)
                    .set("n1", 10));
            int[] tags = tagsOf(parseKeys(data));
            if (!Arrays.equals(new int[] {1, 2, 5, 9}, tags)) {
                return "期望=字段按 tag 升序写出 [1, 2, 5, 9] 实际=" + Arrays.toString(tags);
            }
            byte[] other = codec.encode(Message.of()
                    .set("n1", 10)
                    .set("n5", 50)
                    .set("n2", 20)
                    .set("n9", 90));
            if (!Arrays.equals(data, other)) {
                return "期望=设置顺序不影响编码字节 实际=两次编码不同";
            }
            Message d = codec.decode(data);
            if (d.getInt("n1") != 10 || d.getInt("n2") != 20
                    || d.getInt("n5") != 50 || d.getInt("n9") != 90) {
                return "期望=四字段往返正确 实际=" + d.getInt("n1") + "/" + d.getInt("n2")
                        + "/" + d.getInt("n5") + "/" + d.getInt("n9");
            }
            return null;
        }));

        out.add(new Scenario("basic/missing-defaults", () -> {
            Schema inner = Schema.builder().field(1, Type.INT32, "v").build();
            Schema schema = Schema.builder()
                    .field(1, Type.INT32, "i32")
                    .field(2, Type.INT64, "i64")
                    .field(3, Type.STRING, "str")
                    .field(4, Type.BYTES, "raw")
                    .field(5, Type.BOOL, "flag")
                    .message(6, "inner", inner)
                    .build();
            Codec codec = new Codec(schema);
            Message d = codec.decode(new byte[0]);
            if (d.getInt("i32") != 0) {
                return "期望=缺失 INT32 默认 0 实际=" + d.getInt("i32");
            }
            if (d.getLong("i64") != 0L) {
                return "期望=缺失 INT64 默认 0 实际=" + d.getLong("i64");
            }
            if (!d.getString("str").isEmpty()) {
                return "期望=缺失 STRING 默认空串 实际=" + d.getString("str");
            }
            if (d.getBytes("raw").length != 0) {
                return "期望=缺失 BYTES 默认空数组 实际=长度 " + d.getBytes("raw").length;
            }
            if (d.getBool("flag")) {
                return "期望=缺失 BOOL 默认 false 实际=true";
            }
            if (d.getMessage("inner") != null) {
                return "期望=缺失 MESSAGE 为「未设置」(null) 实际=非 null";
            }
            if (d.has("i32") || d.has("inner")) {
                return "期望=缺失字段 has()=false 实际=true";
            }
            if (codec.encode(d).length != 0) {
                return "期望=全默认消息编码为空 实际=长度 " + codec.encode(d).length;
            }
            return null;
        }));

        out.add(new Scenario("basic/nested-message", () -> {
            Schema inner = Schema.builder()
                    .field(1, Type.INT32, "v")
                    .field(2, Type.STRING, "label")
                    .build();
            Schema schema = Schema.builder()
                    .field(1, Type.INT32, "id")
                    .message(2, "inner", inner)
                    .build();
            Codec codec = new Codec(schema);
            byte[] data = codec.encode(Message.of()
                    .set("id", 3)
                    .set("inner", Message.of().set("v", 9).set("label", "x")));
            Message d = codec.decode(data);
            if (d.getInt("id") != 3) {
                return "期望=id=3 实际=" + d.getInt("id");
            }
            if (d.getMessage("inner") == null) {
                return "期望=嵌套消息已设置 实际=null";
            }
            if (d.getMessage("inner").getInt("v") != 9
                    || !"x".equals(d.getMessage("inner").getString("label"))) {
                return "期望=inner=(v=9,label=x) 实际=(v=" + d.getMessage("inner").getInt("v")
                        + ",label=" + d.getMessage("inner").getString("label") + ")";
            }
            byte[] empty = codec.encode(Message.of().set("inner", Message.of()));
            Message de = codec.decode(empty);
            if (!de.has("inner")) {
                return "期望=已设置的空 MESSAGE has()=true 实际=false";
            }
            if (de.getMessage("inner") == null) {
                return "期望=已设置的空 MESSAGE 非 null 实际=null";
            }
            if (de.getMessage("inner").has("v")) {
                return "期望=空 MESSAGE 里没有字段 实际=有";
            }
            return null;
        }));

        // ---------------- evolution ----------------

        out.add(new Scenario("evolution/old-reads-new", () -> {
            Schema old = Schema.builder()
                    .field(1, Type.INT32, "a")
                    .field(2, Type.STRING, "b")
                    .build();
            Schema neu = Schema.builder()
                    .field(1, Type.INT32, "a")
                    .field(2, Type.STRING, "b")
                    .field(3, Type.INT64, "c")
                    .build();
            byte[] data = new Codec(neu).encode(Message.of()
                    .set("a", 7)
                    .set("b", "hi")
                    .set("c", 123456789L));
            Codec oc = new Codec(old);
            Message d;
            try {
                d = oc.decode(data);
            } catch (Throwable t) {
                return "期望=老 schema 解新数据不报错 实际=" + t;
            }
            if (d.getInt("a") != 7 || !"hi".equals(d.getString("b"))) {
                return "期望=老字段 a=7,b=hi 实际=a=" + d.getInt("a") + ",b=" + d.getString("b");
            }
            if (d.unknown().size() != 1 || d.unknown().entries().get(0).tag() != 3) {
                return "期望=新字段 tag=3 进 unknown（1 个）实际=" + d.unknown().size()
                        + " 个" + (d.unknown().isEmpty() ? "" : "，首个 tag=" + d.unknown().entries().get(0).tag());
            }
            byte[] re = oc.encode(d);
            if (!Arrays.equals(re, data)) {
                return "期望=再编码字节不变（unknown 原样写回）实际=" + hex(re) + " 期望=" + hex(data);
            }
            return null;
        }));

        out.add(new Scenario("evolution/new-reads-old", () -> {
            Schema old = Schema.builder()
                    .field(1, Type.INT32, "a")
                    .field(2, Type.STRING, "b")
                    .build();
            Schema neu = Schema.builder()
                    .field(1, Type.INT32, "a")
                    .field(3, Type.INT64, "c")
                    .build();
            byte[] data = new Codec(old).encode(Message.of()
                    .set("a", 5)
                    .set("b", "bye"));
            Codec nc = new Codec(neu);
            Message d;
            try {
                d = nc.decode(data);
            } catch (Throwable t) {
                return "期望=新 schema 解老数据不报错 实际=" + t;
            }
            if (d.getInt("a") != 5) {
                return "期望=a=5 实际=" + d.getInt("a");
            }
            if (d.getLong("c") != 0L) {
                return "期望=缺失新字段默认 0 实际=" + d.getLong("c");
            }
            if (d.has("c")) {
                return "期望=缺失新字段 has()=false 实际=true";
            }
            if (d.unknown().size() != 1 || d.unknown().entries().get(0).tag() != 2) {
                return "期望=被删字段 tag=2 进 unknown（1 个）实际=" + d.unknown().size()
                        + " 个" + (d.unknown().isEmpty() ? "" : "，首个 tag=" + d.unknown().entries().get(0).tag());
            }
            byte[] re = nc.encode(d);
            if (!Arrays.equals(re, data)) {
                return "期望=再编码字节不变 实际=" + hex(re) + " 期望=" + hex(data);
            }
            return null;
        }));

        out.add(new Scenario("evolution/reencode-byte-stable", () -> {
            Schema schema = Schema.builder()
                    .field(1, Type.INT64, "a")
                    .field(3, Type.STRING, "c")
                    .build();
            Codec codec = new Codec(schema);
            byte[] data = concat(
                    varintField(1, -1L),
                    fixed32Field(2, 0xDEADBEEF),
                    lenField(3, "z".getBytes(StandardCharsets.UTF_8)),
                    lenField(4, new byte[] {9, 8, 7}));
            Message d = codec.decode(data);
            if (d.getLong("a") != -1L) {
                return "期望=a=-1 实际=" + d.getLong("a");
            }
            if (!"z".equals(d.getString("c"))) {
                return "期望=c=z 实际=" + d.getString("c");
            }
            byte[] re = codec.encode(d);
            if (!Arrays.equals(re, data)) {
                return "期望=encode(decode(bytes))==bytes（未知字段参与 tag 升序、负数 10 字节）"
                        + " 实际=" + hex(re) + " 期望=" + hex(data);
            }
            byte[] neg = codec.encode(Message.of().set("a", -1L));
            byte[] expectNeg = varintField(1, -1L);
            if (!Arrays.equals(neg, expectNeg)) {
                return "期望=int64 -1 编码为补码 10 字节 varint（9×0xFF + 0x01）实际=" + hex(neg);
            }
            if (neg.length != 11 || (neg[10] & 0xFF) != 0x01) {
                return "期望=-1 的载荷恰好 10 字节且末字节 0x01 实际=总长 " + neg.length
                        + " 末字节 0x" + Integer.toHexString(neg[neg.length - 1] & 0xFF);
            }
            byte[] re2 = codec.encode(codec.decode(re));
            if (!Arrays.equals(re, re2)) {
                return "期望=再往返一次字节稳定 实际=" + hex(re2) + " 期望=" + hex(re);
            }
            return null;
        }));

        // ---------------- unknown ----------------

        out.add(new Scenario("unknown/retain-and-writeback", () -> {
            Schema schema = Schema.builder()
                    .field(1, Type.INT32, "a")
                    .field(5, Type.STRING, "e")
                    .build();
            Codec codec = new Codec(schema);
            byte[] data = concat(
                    lenField(5, "z".getBytes(StandardCharsets.UTF_8)),
                    varintField(2, 42),
                    varintField(1, 7));
            Message d = codec.decode(data);
            if (d.getInt("a") != 7 || !"z".equals(d.getString("e"))) {
                return "期望=a=7,e=z 实际=a=" + d.getInt("a") + ",e=" + d.getString("e");
            }
            if (d.unknown().size() != 1) {
                return "期望=未知 tag=2 被保留（1 个）实际=" + d.unknown().size() + " 个";
            }
            UnknownFields.Entry u = d.unknown().entries().get(0);
            if (u.tag() != 2 || u.wireType() != Wire.VARINT) {
                return "期望=未知字段 tag=2 wireType=VARINT 实际=tag=" + u.tag() + " wireType=" + u.wireType();
            }
            byte[] body = u.body();
            if (body.length != 1 || (body[0] & 0xFF) != 42) {
                return "期望=未知字段载荷原样保留(varint 42) 实际=" + Arrays.toString(body);
            }
            byte[] re = codec.encode(d);
            int[] tags = tagsOf(parseKeys(re));
            if (!Arrays.equals(new int[] {1, 2, 5}, tags)) {
                return "期望=重编码按 tag 升序 [1, 2, 5]（未知 tag 参与排序，不追加在尾部）实际=" + Arrays.toString(tags);
            }
            byte[] expect = concat(
                    varintField(1, 7),
                    varintField(2, 42),
                    lenField(5, "z".getBytes(StandardCharsets.UTF_8)));
            if (!Arrays.equals(re, expect)) {
                return "期望=规范化字节 实际=" + hex(re) + " 期望=" + hex(expect);
            }
            Message d2 = codec.decode(re);
            if (d2.unknown().size() != 1 || d2.unknown().entries().get(0).tag() != 2) {
                return "期望=重编码后再解码仍保留未知字段 实际=" + d2.unknown().size() + " 个";
            }
            return null;
        }));

        out.add(new Scenario("unknown/wiretype-retained", () -> {
            Schema schema = Schema.builder().field(1, Type.INT32, "a").build();
            Codec codec = new Codec(schema);
            byte[] data = concat(
                    varintField(1, 1),
                    fixed64Field(3, 0x1122334455667788L),
                    fixed32Field(6, 0x99AABBCC));
            Message d = codec.decode(data);
            if (d.getInt("a") != 1) {
                return "期望=a=1 实际=" + d.getInt("a");
            }
            if (d.unknown().size() != 2) {
                return "期望=两个未知 tag 都保留 实际=" + d.unknown().size() + " 个";
            }
            UnknownFields.Entry e3 = entryByTag(d.unknown(), 3);
            UnknownFields.Entry e6 = entryByTag(d.unknown(), 6);
            if (e3 == null || e3.wireType() != Wire.FIXED64 || e3.body().length != 8) {
                return "期望=未知 tag=3 以 FIXED64 原样保留(8 字节) 实际=" + describe(e3);
            }
            if (e6 == null || e6.wireType() != Wire.FIXED32 || e6.body().length != 4) {
                return "期望=未知 tag=6 以 FIXED32 原样保留(4 字节) 实际=" + describe(e6);
            }
            byte[] re = codec.encode(d);
            if (!Arrays.equals(re, data)) {
                return "期望=无论 wireType 是否认识都原样写回 实际=" + hex(re) + " 期望=" + hex(data);
            }
            return null;
        }));

        // ---------------- robust ----------------

        out.add(new Scenario("robust/truncated-depth-invalid", () -> {
            // (1) 截断输入：抛 CodecException，且不越读（position 回到调用前）。
            Schema inner = Schema.builder().field(1, Type.INT64, "x").build();
            Schema schema = Schema.builder()
                    .field(1, Type.INT32, "a")
                    .field(2, Type.STRING, "b")
                    .message(3, "m", inner)
                    .build();
            Codec codec = new Codec(schema);
            byte[] full = codec.encode(Message.of()
                    .set("a", 300)
                    .set("b", "hello")
                    .set("m", Message.of().set("x", 99999L)));
            int[] cuts = {1, 2, 4, full.length - 2, full.length - 1};
            for (int cut : cuts) {
                if (cut <= 0 || cut >= full.length) {
                    continue;
                }
                byte[] trunc = Arrays.copyOf(full, cut);
                ByteBuffer buf = ByteBuffer.wrap(trunc);
                int start = buf.position();
                try {
                    codec.decode(buf);
                    return "期望=截断输入(cut=" + cut + ")抛 CodecException 实际=未抛异常";
                } catch (CodecException expected) {
                    if (buf.position() != start) {
                        return "期望=解码失败后 position 回到 " + start + "（cut=" + cut + "）实际=" + buf.position();
                    }
                } catch (Throwable t) {
                    return "期望=截断输入(cut=" + cut + ")抛 CodecException 实际=" + t.getClass().getName();
                }
            }

            // (2) 嵌套深度：32 层允许，33 层必须抛 CodecException（不得 StackOverflowError）。
            Codec okCodec = new Codec(nestedSchema(32));
            byte[] okBytes = okCodec.encode(nestedMessage(32, 7));
            try {
                Message down = okCodec.decode(okBytes);
                for (int i = 0; i < 32; i++) {
                    down = down.getMessage("n");
                    if (down == null) {
                        return "期望=深度 32 往返成功 实际=第 " + (i + 1) + " 层缺失";
                    }
                }
                if (down.getInt("v") != 7) {
                    return "期望=深度 32 叶子值 7 实际=" + down.getInt("v");
                }
            } catch (Throwable t) {
                return "期望=深度 32 正常解码 实际=" + t;
            }
            Codec deepCodec = new Codec(nestedSchema(33));
            byte[] deepBytes = deepCodec.encode(nestedMessage(33, 7));
            try {
                deepCodec.decode(deepBytes);
                return "期望=嵌套深度 33 超过上限 32 抛 CodecException 实际=未抛异常";
            } catch (CodecException expected) {
                // ok
            } catch (Throwable t) {
                return "期望=深度超限抛 CodecException 实际=" + t.getClass().getName();
            }

            // (3) schema / 输入非法：一律 CodecException。
            try {
                Field.scalar(0, Type.INT32, "z");
                return "期望=tag=0 抛 CodecException 实际=未抛";
            } catch (CodecException expected) {
                // ok
            }
            try {
                Field.scalar(65536, Type.INT32, "z");
                return "期望=tag=65536 抛 CodecException 实际=未抛";
            } catch (CodecException expected) {
                // ok
            }
            try {
                Schema.of(Field.scalar(1, Type.INT32, "a"), Field.scalar(1, Type.INT64, "b"));
                return "期望=重复 tag 抛 CodecException 实际=未抛";
            } catch (CodecException expected) {
                // ok
            }
            Schema one = Schema.builder().field(1, Type.INT32, "a").build();
            byte[] wrongWireType = lenField(1, new byte[] {1, 2, 3});
            try {
                new Codec(one).decode(wrongWireType);
                return "期望=已知 tag 但 wireType 不符抛 CodecException 实际=未抛";
            } catch (CodecException expected) {
                // ok
            }
            return null;
        }));

        return out;
    }

    // ------------------------------------------------------------- 辅助

    private static Schema nestedSchema(int messageLevels) {
        Schema schema = Schema.builder().field(1, Type.INT32, "v").build();
        for (int i = 0; i < messageLevels; i++) {
            schema = Schema.builder().message(1, "n", schema).build();
        }
        return schema;
    }

    private static Message nestedMessage(int messageLevels, int leafValue) {
        Message root = Message.of();
        Message cursor = root;
        for (int i = 0; i < messageLevels; i++) {
            Message child = Message.of();
            cursor.set("n", child);
            cursor = child;
        }
        cursor.set("v", leafValue);
        return root;
    }

    private static UnknownFields.Entry entryByTag(UnknownFields unknown, int tag) {
        for (UnknownFields.Entry e : unknown.entries()) {
            if (e.tag() == tag) {
                return e;
            }
        }
        return null;
    }

    private static String describe(UnknownFields.Entry e) {
        if (e == null) {
            return "缺失";
        }
        return "tag=" + e.tag() + " wireType=" + e.wireType() + " body=" + e.body().length + " 字节";
    }

    private static int[] tagsOf(List<int[]> keys) {
        int[] tags = new int[keys.size()];
        for (int i = 0; i < tags.length; i++) {
            tags[i] = keys.get(i)[0];
        }
        return tags;
    }

    /** 按线格式遍历一遍，读出每个字段的 [tag, wireType]。 */
    private static List<int[]> parseKeys(byte[] data) {
        List<int[]> keys = new ArrayList<>();
        int pos = 0;
        while (pos < data.length) {
            long[] key = readVarintAt(data, pos);
            int tag = (int) (key[0] >>> 3);
            int wireType = (int) (key[0] & 0x7L);
            keys.add(new int[] {tag, wireType});
            pos = (int) key[1];
            switch (wireType) {
                case Wire.VARINT:
                    pos = (int) readVarintAt(data, pos)[1];
                    break;
                case Wire.FIXED64:
                    pos += 8;
                    break;
                case Wire.LEN:
                    long[] len = readVarintAt(data, pos);
                    pos = (int) (len[1] + len[0]);
                    break;
                case Wire.FIXED32:
                    pos += 4;
                    break;
                default:
                    throw new CodecException("固定件不支持遍历 wireType=" + wireType);
            }
        }
        return keys;
    }

    private static long[] readVarintAt(byte[] data, int pos) {
        long result = 0L;
        int shift = 0;
        while (true) {
            int b = data[pos++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new long[] {result, pos};
            }
            shift += 7;
        }
    }

    private static byte[] varintField(int tag, long value) {
        return concat(key(tag, Wire.VARINT), varintBytes(value));
    }

    private static byte[] lenField(int tag, byte[] payload) {
        return concat(key(tag, Wire.LEN), varintBytes(payload.length), payload);
    }

    private static byte[] fixed32Field(int tag, int value) {
        byte[] body = new byte[4];
        for (int i = 0; i < 4; i++) {
            body[i] = (byte) (value >>> (8 * i));
        }
        return concat(key(tag, Wire.FIXED32), body);
    }

    private static byte[] fixed64Field(int tag, long value) {
        byte[] body = new byte[8];
        for (int i = 0; i < 8; i++) {
            body[i] = (byte) (value >>> (8 * i));
        }
        return concat(key(tag, Wire.FIXED64), body);
    }

    private static byte[] key(int tag, int wireType) {
        return varintBytes(((long) tag << 3) | wireType);
    }

    private static byte[] varintBytes(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long v = value;
        while (true) {
            if ((v & ~0x7FL) == 0L) {
                out.write((int) v);
                return out.toByteArray();
            }
            out.write((int) ((v & 0x7FL) | 0x80L));
            v >>>= 7;
        }
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.append(']').toString();
    }

    private Checker() {
    }
}
