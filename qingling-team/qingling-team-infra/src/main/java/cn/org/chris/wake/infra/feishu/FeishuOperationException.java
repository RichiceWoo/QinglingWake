package cn.org.chris.wake.infra.feishu;

/**
 * 表示脱敏后的飞书 API 失败，只保留响应码和异常类型链路。
 */
public final class FeishuOperationException extends RuntimeException {

    /** 飞书响应码；本地或网络异常使用 -1。 */
    private final int code;

    /**
     * 创建不含底层消息正文的 API 失败。
     */
    public FeishuOperationException(String operation, int code) {
        super(operation + "，code=" + code);
        this.code = code;
    }

    /**
     * 创建只保留底层异常类型的 API 失败，避免堆栈日志泄漏服务端正文。
     */
    public FeishuOperationException(String operation, int code, Throwable cause) {
        super(operation + "，code=" + code + "，cause="
                + (cause == null ? "Unknown" : cause.getClass().getSimpleName()));
        this.code = code;
    }

    /**
     * 返回飞书响应码或本地异常占位值 -1。
     */
    public int code() {
        return code;
    }
}
