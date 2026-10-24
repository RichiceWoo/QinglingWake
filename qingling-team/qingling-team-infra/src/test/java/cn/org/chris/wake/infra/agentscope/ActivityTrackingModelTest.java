package cn.org.chris.wake.infra.agentscope;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证模型活动装饰器只记录归属角色时间戳并完整透传模型语义。 */
class ActivityTrackingModelTest {

    /** 每个流式分片都应刷新当前角色时间，且不得污染其他角色。 */
    @Test
    void shouldTrackSubscriptionAndEveryChunkForOwningRoleOnly() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-24T00:00:00Z"));
        ModelActivityRegistry registry = new ModelActivityRegistry(Set.of("rd", "qa"), clock);
        ChatResponse first = response("first");
        ChatResponse second = response("second");
        Model delegate = new FixedModel(Flux.just(first, second)
                .doOnNext(ignored -> clock.advance(Duration.ofSeconds(1))));
        ActivityTrackingModel model = new ActivityTrackingModel(delegate, registry, "rd");
        List<Instant> observedActivity = new ArrayList<>();

        List<ChatResponse> responses = model.stream(List.of(), List.of(), null)
                .doOnNext(ignored -> observedActivity.add(registry.lastActivity("rd").orElseThrow()))
                .collectList()
                .block();

        assertThat(responses).containsExactly(first, second);
        assertThat(observedActivity).containsExactly(
                Instant.parse("2026-10-24T00:00:01Z"),
                Instant.parse("2026-10-24T00:00:02Z")
        );
        assertThat(registry.lastActivity("qa")).isEmpty();
    }

    /** 模型名称、结构化输出能力和上下文窗口必须全部委托给底层模型。 */
    @Test
    void shouldDelegateAllModelMetadataWithoutSemanticChanges() {
        ActivityTrackingModel model = new ActivityTrackingModel(
                new FixedModel(Flux.empty()), new ModelActivityRegistry(), "rd"
        );

        assertThat(model.getModelName()).isEqualTo("fixed-model");
        assertThat(model.supportsNativeStructuredOutput()).isTrue();
        assertThat(model.supportsNativeStructuredOutputWithTools()).isTrue();
        assertThat(model.getContextWindowSize()).isEqualTo(65_536);
    }

    /** 创建不含内容的稳定响应分片。 */
    private static ChatResponse response(String id) {
        return new ChatResponse(id, List.of(), null, Map.of(), null);
    }

    /** 提供固定响应流和确定元数据的模型替身。 */
    private record FixedModel(Flux<ChatResponse> responses) implements Model {

        /** 原样返回测试预设的响应流。 */
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions generateOptions
        ) {
            return responses;
        }

        /** 返回测试模型名称。 */
        @Override
        public String getModelName() {
            return "fixed-model";
        }

        /** 声明测试模型支持原生结构化输出。 */
        @Override
        public boolean supportsNativeStructuredOutput() {
            return true;
        }

        /** 声明测试模型支持工具与结构化输出并用。 */
        @Override
        public boolean supportsNativeStructuredOutputWithTools() {
            return true;
        }

        /** 返回确定的测试上下文窗口。 */
        @Override
        public int getContextWindowSize() {
            return 65_536;
        }
    }

    /** 支持测试按分片推进时间且保持固定时区的可变时钟。 */
    private static final class MutableClock extends Clock {

        /** 当前测试时间。 */
        private final AtomicReference<Instant> current;

        /** 该测试时钟固定使用 UTC。 */
        private final ZoneId zone;

        /** 使用指定初始时间创建 UTC 测试时钟。 */
        private MutableClock(Instant initial) {
            this(initial, ZoneOffset.UTC);
        }

        /** 使用指定初始时间和时区创建测试时钟。 */
        private MutableClock(Instant initial, ZoneId zone) {
            this.current = new AtomicReference<>(initial);
            this.zone = zone;
        }

        /** 使用共享时间引用创建其他时区视图。 */
        private MutableClock(AtomicReference<Instant> current, ZoneId zone) {
            this.current = current;
            this.zone = zone;
        }

        /** 返回测试时区。 */
        @Override
        public ZoneId getZone() {
            return zone;
        }

        /** 返回共享当前时间的新时区视图。 */
        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutableClock(current, newZone);
        }

        /** 返回当前测试时间。 */
        @Override
        public Instant instant() {
            return current.get();
        }

        /** 按指定正时长推进测试时间。 */
        private void advance(Duration duration) {
            current.updateAndGet(value -> value.plus(duration));
        }
    }
}
