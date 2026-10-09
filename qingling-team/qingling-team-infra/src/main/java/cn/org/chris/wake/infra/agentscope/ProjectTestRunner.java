package cn.org.chris.wake.infra.agentscope;

import reactor.core.publisher.Mono;

/**
 * 定义仅能运行固定项目 pytest 的 Docker Test Runner 边界。
 */
public interface ProjectTestRunner {

    /**
     * 幂等启动指定 execution ID；重复请求不得创建第二个测试进程。
     *
     * @param executionId Java 生成的稳定执行标识
     * @param projectId 已校验项目标识
     * @return 当前执行快照
     */
    Mono<ProjectTestExecution> start(String executionId, String projectId);

    /**
     * 从 Docker 持久状态查询执行结果。
     *
     * @param executionId 稳定执行标识
     * @return 当前或终态执行快照
     */
    Mono<ProjectTestExecution> query(String executionId);

    /**
     * 终止执行对应的容器进程组并写入取消终态。
     *
     * @param executionId 稳定执行标识
     * @return 取消后的执行快照
     */
    Mono<ProjectTestExecution> cancel(String executionId);

    /**
     * 关闭失效 MCP client、重建连接并执行无副作用健康探针。
     *
     * @return 成功恢复并重新开放调用时为 true
     */
    default Mono<Boolean> recoverConnection() {
        return Mono.just(false);
    }
}
