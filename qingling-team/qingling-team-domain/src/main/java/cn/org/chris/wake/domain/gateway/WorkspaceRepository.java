package cn.org.chris.wake.domain.gateway;

import java.util.List;

/**
 * 定义受角色权限和路径逃逸防护约束的共享工作区端口。
 */
public interface WorkspaceRepository {

    /**
     * 幂等初始化项目目录、四角色邮箱和事件文件。
     *
     * @param projectId 项目标识
     */
    void initializeProject(String projectId);

    /**
     * 读取项目内共享文本文件。
     *
     * @param projectId 项目标识
     * @param role 操作角色
     * @param relativePath 项目内相对路径
     * @return UTF-8 文本内容
     */
    String read(String projectId, String role, String relativePath);

    /**
     * 写入项目内共享文本文件。
     *
     * @param projectId 项目标识
     * @param role 操作角色
     * @param relativePath 项目内相对路径
     * @param content UTF-8 文本内容
     */
    void write(String projectId, String role, String relativePath, String content);

    /**
     * 列出项目内可见的相对路径。
     *
     * @param projectId 项目标识
     * @param role 操作角色
     * @return 稳定排序的相对路径
     */
    List<String> list(String projectId, String role);
}
