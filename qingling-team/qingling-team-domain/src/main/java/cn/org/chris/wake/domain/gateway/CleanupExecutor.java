package cn.org.chris.wake.domain.gateway;

import cn.org.chris.wake.domain.model.CleanupPolicy;

import java.time.Instant;
import java.util.Map;

/**
 * 定义 data root 内的清理和沙盒配置初始化端口。
 */
public interface CleanupExecutor {

    /**
     * 按策略清理过期条目。
     *
     * @param policy 清理策略
     * @param now 当前时间
     * @return 每条规则删除的文件或目录数量
     */
    Map<String, Integer> sweep(CleanupPolicy policy, Instant now);

    /**
     * 初始化会话 uploads、outputs 与 tmp 目录。
     *
     * @param sessionId 安全会话标识
     */
    void ensureWorkspaceDirectories(String sessionId);

    /**
     * 以安全权限写入飞书凭证。
     *
     * @param appId 飞书应用标识
     * @param appSecret 飞书应用密钥
     */
    void writeFeishuCredentials(String appId, String appSecret);

    /**
     * API Key 非空时以安全权限写入百度凭证。
     *
     * @param apiKey 百度千帆 API Key
     */
    void writeBaiduCredentials(String apiKey);
}
