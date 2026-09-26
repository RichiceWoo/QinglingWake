package cn.org.chris.wake.starter.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 保存已通过 fail-fast 校验并规范化路径的运行时配置。
 *
 * @param properties 原始类型安全配置
 * @param workspaceRoot 规范化外部 workspace 根目录
 * @param dataDirectory 规范化数据根目录
 */
public record RuntimeSettings(
        /** 完整类型安全配置。 */ QinglingTeamProperties properties,
        /** 规范化外部 workspace 根目录。 */ Path workspaceRoot,
        /** 规范化数据根目录。 */ Path dataDirectory
) {
    /** 固化非空配置与绝对规范化路径。 */
    public RuntimeSettings {
        Objects.requireNonNull(properties, "properties 不能为空");
        workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot 不能为空")
                .toAbsolutePath().normalize();
        dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory 不能为空")
                .toAbsolutePath().normalize();
    }
}
