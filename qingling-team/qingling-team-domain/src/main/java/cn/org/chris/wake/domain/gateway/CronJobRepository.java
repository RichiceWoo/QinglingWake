package cn.org.chris.wake.domain.gateway;

import cn.org.chris.wake.domain.model.CronJob;

import java.util.List;

/**
 * 定义 tasks.json 的原子读写、热重载版本与 wake 去重端口。
 */
public interface CronJobRepository {

    /**
     * 读取全部任务，包含禁用任务。
     *
     * @return 文件顺序下的不可变任务快照
     */
    List<CronJob> findAll();

    /**
     * 原子替换全部任务。
     *
     * @param jobs 完整任务列表
     */
    void replaceAll(List<CronJob> jobs);

    /**
     * 按标识替换已有任务或追加新任务。
     *
     * @param job 待保存任务
     */
    void upsert(CronJob job);

    /**
     * 在同一文件锁内复用同路由、同消息且未到期的一次性任务，否则追加候选任务。
     *
     * @param candidate 新的一次性 wake
     * @param nowMs 当前毫秒时间
     * @return 实际复用或新增的任务
     */
    CronJob saveWakeIfAbsent(CronJob candidate, long nowMs);

    /**
     * 删除指定任务。
     *
     * @param jobId 任务标识
     * @return 是否实际删除
     */
    boolean delete(String jobId);

    /**
     * 获取文件存在性、mtime 与 size 组合版本。
     *
     * @return 当前文件版本
     */
    Revision revision();

    /**
     * 用于可靠识别同一 mtime tick 内文件变化的组合版本。
     *
     * @param exists 文件是否存在
     * @param modifiedAtMs 最后修改时间毫秒值
     * @param size 文件字节数
     */
    record Revision(
            /** 文件是否存在。 */ boolean exists,
            /** 文件最后修改时间。 */ long modifiedAtMs,
            /** 文件字节数。 */ long size
    ) {
        /**
         * 创建缺失文件的稳定版本。
         *
         * @return 缺失版本
         */
        public static Revision missing() {
            return new Revision(false, 0L, -1L);
        }
    }
}
