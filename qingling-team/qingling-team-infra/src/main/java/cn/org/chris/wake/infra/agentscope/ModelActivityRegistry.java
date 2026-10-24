package cn.org.chris.wake.infra.agentscope;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按固定团队角色保存最近一次模型订阅或流式分片活动时间，不保存提示词、输出和凭据。
 */
public final class ModelActivityRegistry {

    /** 允许写入的有限角色集合，使注册表容量天然受控。 */
    private final Set<String> allowedRoles;

    /** 每个角色最近一次模型活动时间，条目数不超过允许角色数量。 */
    private final ConcurrentHashMap<String, Instant> lastActivityByRole = new ConcurrentHashMap<>();

    /** 生成可测试时间戳的时钟。 */
    private final Clock clock;

    /** 使用固定四角色和系统 UTC 时钟创建生产注册表。 */
    public ModelActivityRegistry() {
        this(Set.copyOf(TeamAgentFactory.TEAM_ROLES), Clock.systemUTC());
    }

    /**
     * 使用指定角色集合和时钟创建受控注册表。
     *
     * @param allowedRoles 允许记录的非空角色集合
     * @param clock 生成活动时间的时钟
     */
    ModelActivityRegistry(Set<String> allowedRoles, Clock clock) {
        if (allowedRoles == null || allowedRoles.isEmpty()) {
            throw new IllegalArgumentException("allowedRoles 不能为空");
        }
        this.allowedRoles = Set.copyOf(allowedRoles);
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 刷新指定角色的模型活动时间；未知角色会被拒绝以维持容量边界。
     *
     * @param role 模型调用所属团队角色
     */
    public void recordActivity(String role) {
        String checkedRole = Objects.requireNonNull(role, "role 不能为空");
        if (!allowedRoles.contains(checkedRole)) {
            throw new IllegalArgumentException("未知模型活动角色: " + checkedRole);
        }
        lastActivityByRole.put(checkedRole, Instant.now(clock));
    }

    /**
     * 返回指定角色最近一次模型活动时间。
     *
     * @param role 团队角色
     * @return 尚未产生模型活动时为空
     */
    public Optional<Instant> lastActivity(String role) {
        if (role == null || !allowedRoles.contains(role)) {
            return Optional.empty();
        }
        return Optional.ofNullable(lastActivityByRole.get(role));
    }
}
