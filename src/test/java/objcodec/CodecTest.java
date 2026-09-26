package objcodec;

import objcodec.Codec.Message;
import objcodec.Field.Type;

import java.util.Arrays;

/**
 * objcodec 的既有用例（12 个，手写 runner，无第三方框架）。
 *
 * <p>只覆盖同一个 schema 版本内的往返与基本类型。起点应 12/12 全绿。
 */
public final class CodecTest {

    private interface Case {
        void run() throws Exception;
    }

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        run("scalarRoundTrip", CodecTest::scalarRoundTrip);
        run("encodingIsTagAscending", CodecTest::encodingIsTagAscending);
        run("encodingIndependentOfInsertionOrder", CodecTest::encodingIndependentOfInsertionOrder);
        run("missingScalarsUseDefaults", CodecTest::missingScalarsUseDefaults);
        run("missingMessageIsUnsetNotEmpty", CodecTest::missingMessageIsUnsetNotEmpty);
        run("presentEmptyMessageRoundTrips", CodecTest::presentEmptyMessageRoundTrips);
        run("nestedMessageRoundTrip", CodecTest::nestedMessageRoundTrip);
        run("tagBoundaryValues", CodecTest::tagBoundaryValues);
        run("deterministicRepeatedEncoding", CodecTest::deterministicRepeatedEncoding);
        run("scalarValuesSurviveRoundTrip", CodecTest::scalarValuesSurviveRoundTrip);
        run("emptyStringAndBytesRoundTrip", CodecTest::emptyStringAndBytesRoundTrip);
        run("shallowNestingRoundTrip", CodecTest::shallowNestingRoundTrip);

        System.out.println("结果：通过 " + passed + "/" + (passed + failed));
        if (failed != 0) {
            System.exit(1);
        }
    }

    private static void run(String name, Case body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL " + name + "  " + t);
        }
    }

    // ------------------------------------------------------------------ 用例

    private static void scalarRoundTrip() {
        Schema schema = Schema.builder()
                .field(1, Type.INT32, "i32")
                .field(2, Type.INT64, "i64")
                .field(3, Type.STRING, "str")
                .field(4, Type.BYTES, "raw")
                .field(5, Type.BOOL, "flag")
                .build();
        Codec codec = new Codec(schema);
        byte[] raw = new byte[] {0, 1, 2, (byte) 0xFF, 7};
        byte[] encoded = codec.encode(Message.of()
                .set("i32", 7)
                .set("i64", 1234567890123L)
                .set("str", "你好, objcodec")
                .set("raw", raw)
                .set("flag", true));
        Message decoded = codec.decode(encoded);
        assertEquals(7, decoded.getInt("i32"), "i32");
        assertEquals(1234567890123L, decoded.getLong("i64"), "i64");
        assertEquals("你好, objcodec", decoded.getString("str"), "str");
        assertTrue(Arrays.equals(raw, decoded.getBytes("raw")), "raw");
        assertTrue(decoded.getBool("flag"), "flag");
        assertTrue(decoded.has("i32") && decoded.has("flag"), "字段应视为已设置");
    }

    private static void encodingIsTagAscending() {
        Schema schema = Schema.builder()
                .field(9, Type.INT32, "n9")
                .field(2, Type.INT32, "n2")
                .field(5, Type.INT32, "n5")
                .field(1, Type.INT32, "n1")
                .build();
        Codec codec = new Codec(schema);
        byte[] encoded = codec.encode(Message.of()
                .set("n9", 90)
                .set("n2", 20)
                .set("n5", 50)
                .set("n1", 10));
        int[] tags = readVarintFieldTags(encoded);
        assertTrue(Arrays.equals(new int[] {1, 2, 5, 9}, tags),
                "编码应按 tag 升序，实际 " + Arrays.toString(tags));
    }

    private static void encodingIndependentOfInsertionOrder() {
        Schema schema = Schema.builder()
                .field(1, Type.INT32, "a")
                .field(2, Type.STRING, "b")
                .field(3, Type.BOOL, "c")
                .build();
        Codec codec = new Codec(schema);
        byte[] one = codec.encode(Message.of().set("a", 1).set("b", "x").set("c", true));
        byte[] two = codec.encode(Message.of().set("c", true).set("b", "x").set("a", 1));
        assertTrue(Arrays.equals(one, two), "设置顺序不应影响编码结果");
    }

    private static void missingScalarsUseDefaults() {
        Schema schema = Schema.builder()
                .field(1, Type.INT32, "i32")
                .field(2, Type.INT64, "i64")
                .field(3, Type.STRING, "str")
                .field(4, Type.BYTES, "raw")
                .field(5, Type.BOOL, "flag")
                .build();
        Codec codec = new Codec(schema);
        Message decoded = codec.decode(new byte[0]);
        assertEquals(0, decoded.getInt("i32"), "缺失 INT32 默认 0");
        assertEquals(0L, decoded.getLong("i64"), "缺失 INT64 默认 0");
        assertEquals("", decoded.getString("str"), "缺失 STRING 默认空串");
        assertEquals(0, decoded.getBytes("raw").length, "缺失 BYTES 默认空数组");
        assertTrue(!decoded.getBool("flag"), "缺失 BOOL 默认 false");
        assertTrue(!decoded.has("i32") && !decoded.has("str"), "缺失字段不应视为已设置");
        assertEquals(0, codec.encode(decoded).length, "全是默认值的消息编码应为空");
    }

    private static void missingMessageIsUnsetNotEmpty() {
        Schema inner = Schema.builder().field(1, Type.INT32, "v").build();
        Schema schema = Schema.builder().message(1, "inner", inner).build();
        Codec codec = new Codec(schema);
        Message decoded = codec.decode(new byte[0]);
        assertTrue(decoded.getMessage("inner") == null, "缺失 MESSAGE 应为「未设置」而不是空对象");
        assertTrue(!decoded.has("inner"), "缺失 MESSAGE 不应视为已设置");
    }

    private static void presentEmptyMessageRoundTrips() {
        Schema inner = Schema.builder().field(1, Type.INT32, "v").build();
        Schema schema = Schema.builder().message(1, "inner", inner).build();
        Codec codec = new Codec(schema);
        byte[] encoded = codec.encode(Message.of().set("inner", Message.of()));
        assertTrue(encoded.length > 0, "已设置的空消息也应被写出");
        Message decoded = codec.decode(encoded);
        assertTrue(decoded.has("inner"), "空消息应视为已设置");
        assertTrue(decoded.getMessage("inner") != null, "空消息不应是 null");
        assertTrue(!decoded.getMessage("inner").has("v"), "空消息里没有字段");
    }

    private static void nestedMessageRoundTrip() {
        Schema inner = Schema.builder()
                .field(1, Type.INT32, "v")
                .field(2, Type.STRING, "label")
                .build();
        Schema schema = Schema.builder()
                .field(1, Type.INT32, "id")
                .message(2, "inner", inner)
                .build();
        Codec codec = new Codec(schema);
        byte[] encoded = codec.encode(Message.of()
                .set("id", 3)
                .set("inner", Message.of().set("v", 9).set("label", "x")));
        Message decoded = codec.decode(encoded);
        assertEquals(3, decoded.getInt("id"), "id");
        assertEquals(9, decoded.getMessage("inner").getInt("v"), "inner.v");
        assertEquals("x", decoded.getMessage("inner").getString("label"), "inner.label");
    }

    private static void tagBoundaryValues() {
        Schema schema = Schema.builder()
                .field(1, Type.INT32, "low")
                .field(65535, Type.INT32, "high")
                .build();
        Codec codec = new Codec(schema);
        byte[] encoded = codec.encode(Message.of().set("low", 11).set("high", 22));
        Message decoded = codec.decode(encoded);
        assertEquals(11, decoded.getInt("low"), "tag=1");
        assertEquals(22, decoded.getInt("high"), "tag=65535");
    }

    private static void deterministicRepeatedEncoding() {
        Schema schema = Schema.builder()
                .field(1, Type.INT32, "a")
                .field(2, Type.STRING, "b")
                .field(3, Type.BYTES, "c")
                .build();
        Codec codec = new Codec(schema);
        Message msg = Message.of().set("a", 42).set("b", "same").set("c", new byte[] {1, 2, 3});
        assertTrue(Arrays.equals(codec.encode(msg), codec.encode(msg)), "同一对象两次编码应逐字节相同");
    }

    private static void scalarValuesSurviveRoundTrip() {
        Schema schema = Schema.builder()
                .field(1, Type.INT64, "big")
                .field(2, Type.STRING, "s")
                .field(3, Type.BOOL, "t")
                .field(4, Type.BOOL, "f")
                .build();
        Codec codec = new Codec(schema);
        Message decoded = codec.decode(codec.encode(Message.of()
                .set("big", Long.MAX_VALUE)
                .set("s", "round trip")
                .set("t", true)
                .set("f", false)));
        assertEquals(Long.MAX_VALUE, decoded.getLong("big"), "big");
        assertEquals("round trip", decoded.getString("s"), "s");
        assertTrue(decoded.getBool("t"), "t");
        assertTrue(!decoded.getBool("f"), "f");
    }

    private static void emptyStringAndBytesRoundTrip() {
        Schema schema = Schema.builder()
                .field(1, Type.STRING, "s")
                .field(2, Type.BYTES, "b")
                .build();
        Codec codec = new Codec(schema);
        Message decoded = codec.decode(codec.encode(Message.of()
                .set("s", "")
                .set("b", new byte[0])));
        assertEquals("", decoded.getString("s"), "空串");
        assertEquals(0, decoded.getBytes("b").length, "空字节数组");
    }

    private static void shallowNestingRoundTrip() {
        Schema leaf = Schema.builder().field(1, Type.INT32, "v").build();
        Schema level = Schema.builder().message(1, "n", leaf).build();
        for (int i = 0; i < 7; i++) {
            level = Schema.builder().message(1, "n", level).build();
        }
        Message msg = Message.of();
        Message cursor = msg;
        for (int i = 0; i < 8; i++) {
            Message child = Message.of();
            cursor.set("n", child);
            cursor = child;
        }
        cursor.set("v", 42);
        Message decoded = new Codec(level).decode(new Codec(level).encode(msg));
        Message down = decoded;
        for (int i = 0; i < 8; i++) {
            down = down.getMessage("n");
            assertTrue(down != null, "第 " + (i + 1) + " 层不应缺失");
        }
        assertEquals(42, down.getInt("v"), "叶子值");
    }

    // ------------------------------------------------------------------ 辅助

    private static int[] readVarintFieldTags(byte[] data) {
        int[] tags = new int[64];
        int count = 0;
        int pos = 0;
        while (pos < data.length) {
            long[] key = readVarint(data, pos);
            int wireType = (int) (key[0] & 0x7L);
            assertTrue(wireType == Wire.VARINT, "本用例只放 varint 字段，实际 wireType=" + wireType);
            tags[count++] = (int) (key[0] >>> 3);
            pos = (int) key[1];
            long[] payload = readVarint(data, pos);
            pos = (int) payload[1];
        }
        return Arrays.copyOf(tags, count);
    }

    private static long[] readVarint(byte[] data, int pos) {
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

    private static void assertEquals(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + "：期望=" + expected + " 实际=" + actual);
        }
    }

    private static void assertTrue(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private CodecTest() {
    }
}
