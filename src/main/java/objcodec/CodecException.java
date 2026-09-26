package objcodec;

/**
 * 编解码过程中的错误：schema 定义非法、输入损坏 / 截断、wireType 不符等。
 *
 * <p>对外语义以 README 的「对外契约」一节为准。
 */
public class CodecException extends RuntimeException {

    public CodecException(String message) {
        super(message);
    }

    public CodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
