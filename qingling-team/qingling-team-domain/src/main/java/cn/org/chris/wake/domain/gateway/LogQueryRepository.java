package cn.org.chris.wake.domain.gateway;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 为日志查询应用服务提供与存储格式解耦的只读端口。
 */
public interface LogQueryRepository {

    /**
     * 读取全部角色的 L2 任务日志，忽略空行和损坏行。
     *
     * @param logsRoot 日志根目录
     * @return 按文件名和行顺序排列的日志对象
     */
    List<Map<String, Object>> readL2(Path logsRoot);

    /**
     * 读取共享 L1 人工反馈日志，忽略空行和损坏行。
     *
     * @param logsRoot 日志根目录
     * @return 按文件名和行顺序排列的日志对象
     */
    List<Map<String, Object>> readL1(Path logsRoot);

    /**
     * 读取指定 session 的步骤日志。
     *
     * @param sessionFile session JSONL 文件
     * @return 有效步骤对象
     */
    List<Map<String, Object>> readSteps(Path sessionFile);
}
