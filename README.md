# objcodec

一个**可演进的对象编解码器**（Java 17，只用 JDK 标准库，无第三方依赖）。

`objcodec` 把「字段名 → 值」的消息按 `Schema` 编码成一段二进制，再从二进制解回消息。
它面向的场景是**长期演进的存储 / 传输格式**：写入方与读取方可能持有不同版本的 schema，
所以格式必须能在版本之间双向兼容，且编码结果逐字节确定。

```java
Schema schema = Schema.builder()
        .field(1, Field.Type.INT32, "id")
        .field(2, Field.Type.STRING, "name")
        .build();

Codec codec = new Codec(schema);

byte[] bytes = codec.encode(Codec.Message.of()
        .set("id", 7)
        .set("name", "hi"));

Codec.Message msg = codec.decode(bytes);
int id = msg.getInt("id");            // 7
String name = msg.getString("name");  // "hi"
UnknownFields extra = msg.unknown();  // 解码时遇到的、当前 schema 不认识而保留下来的字段
```

## 目录

```
src/main/java/objcodec/Codec.java          编解码入口 + Message 值对象
src/main/java/objcodec/Schema.java         schema 定义与校验
src/main/java/objcodec/Field.java          单个字段（tag / type / name / 嵌套 schema）
src/main/java/objcodec/Wire.java           线格式常量与 varint 读写
src/main/java/objcodec/UnknownFields.java  未知字段容器
src/main/java/objcodec/CodecException.java 编解码异常
src/test/java/objcodec/CodecTest.java      既有用例（12 个，手写 runner，无第三方框架）
scripts/build.sh                           编译 src/ 到 out/
scripts/test.sh                            跑既有用例
scripts/check.sh                           固定验收入口（勿改）
check/Checker.java                         固定验收程序（勿改）
```

## 语言版本前提

- Java 17（`javac` / `java`）。
- **只用 JDK 标准库**：不引入 Maven / Gradle，不引入任何第三方依赖。
- 构建产物放在 `out/`，已在 `.gitignore` 中忽略。

## 怎么跑

```bash
bash scripts/build.sh      # 编译到 out/
bash scripts/test.sh       # 既有用例
bash scripts/check.sh      # 固定验收；支持 -list 与 --only <组名>
bash scripts/check.sh -list              # 列出全部验收场景
bash scripts/check.sh --only unknown     # 只跑一组
```

## 对外契约

下面 10 条是 `objcodec` 的**对外契约**，它们是本题验收点的唯一出处。
**它们是契约，不是「当前行为」的转述**：其中若干条现在的实现并没有满足。

1. **字段模型**。每个字段有 `tag`（`1..65535`）与 `type`
   （`INT32` / `INT64` / `STRING` / `BYTES` / `BOOL` / `MESSAGE`）。同一 schema 内 `tag` 唯一；
   `MESSAGE` 字段携带它自己的嵌套 `Schema`。
2. **线格式与写出顺序**。每个字段写成 `varint(tag << 3 | wireType)` 作为键，后跟载荷。
   `wireType` 取 `VARINT=0`（`INT32` / `INT64` / `BOOL`）、`FIXED32=5`、`FIXED64=1`、
   `LEN=2`（`STRING` / `BYTES` / `MESSAGE`，载荷是「长度 varint + 内容」）。
   编码时**已设置**的字段按 `tag` **升序**写出，字节序确定。
3. **未知字段保留**。解码时遇到 schema 里没有的 `tag`，必须把它**保留**到 `UnknownFields`
   （连同 `tag`、`wireType` 与载荷的**原始字节**）；重新编码时按 `tag` 升序**原样**写回。
4. **默认值**。缺失字段取 schema 声明的默认值：`INT32` / `INT64` 为 `0`，`BOOL` 为 `false`，
   `STRING` 为空串，`BYTES` 为空数组，`MESSAGE` 为「**未设置**」（`getMessage` 返回 `null`，
   `has` 返回 `false`），而不是一个空对象。编码只写**已设置**的字段，缺失字段不写。
5. **schema 演进双向兼容**。老 schema 解新数据 → 新字段进 unknown 且**不报错**；
   新 schema 解老数据 → 缺失字段取默认值；两种方向下再编码一次，
   **未修改字段的字节不变**（含被保留下来的 unknown 字段）。
6. **非法输入**。`tag` 为 `0` / 同一 schema 内重复 / 超过 `65535` → `CodecException`；
   `tag` 已知但 `wireType` 与 schema 声明的类型不符 → `CodecException`；
   `tag` 未知时，只要 `wireType` 是本格式认识的（`0` / `1` / `2` / `5`），就按 unknown 保留。
7. **有符号整数**。`INT32` / `INT64` 用**补码 varint**：负数必须编码成 **10 字节**
   （例如 `-1` 编码为 9 个 `0xFF` 后跟 `0x01`）。
8. **截断与损坏输入**。截断 / 损坏的输入必须抛 `CodecException`，
   **不许**把 `BufferUnderflowException` / `IndexOutOfBoundsException` / `IllegalArgumentException`
   之类泄漏出去；`decode(ByteBuffer)` 失败时必须把该 `ByteBuffer` 的 `position`
   恢复到**调用前的值**（不越读、不留半读状态）。
9. **嵌套深度上限**。嵌套 `MESSAGE` 的深度上限为 **32**（顶层消息深度为 0）。
   超过上限必须抛 `CodecException`，不得抛 `StackOverflowError`。
10. **字节确定性**。同一对象两次编码结果**逐字节相同**；`encode(decode(bytes))` 得到的字节
    等于 `bytes` 的**规范形式**——字段按 `tag` 升序，未知字段参与同一排序，
    而不是简单追加到末尾。

## 验收

`check/` 是固定验收程序，**不要修改它**。它按四组场景检查上面的契约：

| 组 | 场景 | 对应契约 |
| --- | --- | --- |
| `basic` | `scalar-roundtrip` | 1、2、4 |
| `basic` | `tag-ascending` | 2 |
| `basic` | `missing-defaults` | 4 |
| `basic` | `nested-message` | 1、2 |
| `evolution` | `old-reads-new` | 3、5 |
| `evolution` | `new-reads-old` | 4、5 |
| `evolution` | `reencode-byte-stable` | 3、7、10 |
| `unknown` | `retain-and-writeback` | 3、6、10 |
| `unknown` | `wiretype-retained` | 3、6 |
| `robust` | `truncated-depth-invalid` | 6、8、9 |

共 10 个场景。每个场景独立判定，失败不会遮蔽其余场景；判据全部确定性，不依赖墙钟、
机器速度或 `HashMap` 迭代顺序。`--only <组名>` 可只跑一组。
