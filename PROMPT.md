加了字段之后，老版本读新写出来的数据会失败 —— objcodec 现在就是这个毛病。
它是 Java 17 的二进制对象编解码器，只用 JDK 标准库，构建与自检走 `scripts/*.sh`
（`scripts/check.sh` 是固定验收程序，别改）。

README「对外契约」一节列了 10 条（schema 演进、未知字段、字节确定性都在里面）；
`src/test/java` 里 12 个用例当前全绿，但它们只覆盖同一个 schema 版本内的往返。

任务：把 10 条补齐，让既有用例与固定件全过。

验收：
- bash scripts/build.sh 退出码 0；
- bash scripts/check.sh 退出码 0，10 个场景全过（basic 4 + evolution 3 + unknown 2 + robust 1）。

约束：
1. 不改 `check/`、不改既有用例的断言；已公开的类与方法签名不变（可新增）。
2. 不引入第三方依赖，不用 Maven / Gradle。
3. 编码结果必须逐字节确定。
4. 截断与非法输入一律抛 `CodecException`，不许让 JDK 的越界异常漏出去。
