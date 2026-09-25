package cn.org.chris.wake.domain.gateway;

import cn.org.chris.wake.domain.model.InboundMessage;

import java.util.concurrent.CompletableFuture;

/**
 * 定义 Cron 向 Runner 异步投递标准消息的端口。
 */
@FunctionalInterface
public interface CronDispatchGateway {

    /**
     * 投递一条由定时任务生成的消息。
     *
     * @param message 标准入站消息
     * @return 投递完成信号
     */
    CompletableFuture<Void> dispatch(InboundMessage message);
}
