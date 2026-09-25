package cn.org.chris.wake.domain.event;

import cn.org.chris.wake.domain.gateway.EventRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证事件动作白名单与 Manager 单写者领域规则。
 */
class EventServiceTest {

    /**
     * 合法固定动作和角色后缀通配动作均可追加。
     */
    @Test
    void shouldAcceptFixedAndRoleSpecificActions() {
        RecordingEventRepository repository = new RecordingEventRepository();
        EventService service = new EventService(repository);

        assertThat(service.append("p1", "manager", "project_created", Map.of("source", "human")))
                .isEqualTo(1L);
        assertThat(EventService.isValidAction("retro_applied_by_qa")).isTrue();
        assertThat(repository.projectId).isEqualTo("p1");
        assertThat(repository.actor).isEqualTo("manager");
        assertThat(repository.action).isEqualTo("project_created");
        assertThat(repository.details).containsEntry("source", "human");
    }

    /**
     * 非 Manager actor、未知动作和非法通配角色必须拒绝。
     */
    @Test
    void shouldRejectInvalidActorAndAction() {
        EventService service = new EventService(new RecordingEventRepository());

        assertThatThrownBy(() -> service.append("p1", "rd", "project_created", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.append("p1", "manager", "totally_fake_action", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(EventService.isValidAction("retro_applied_by_stranger")).isFalse();
        assertThat(EventService.isValidAction(null)).isFalse();
    }

    /**
     * 记录事件追加参数，避免测试依赖运行时字节码代理。
     */
    private static final class RecordingEventRepository implements EventRepository {

        /** 最近追加事件的项目标识。 */
        private String projectId;

        /** 最近追加事件的发起角色。 */
        private String actor;

        /** 最近追加事件的动作。 */
        private String action;

        /** 最近追加事件的业务详情。 */
        private Map<String, Object> details;

        /**
         * 记录事件参数并返回首个序号。
         *
         * @param projectId 项目标识
         * @param actor 事件发起角色
         * @param action 受支持的动作名称
         * @param details 事件业务详情
         * @return 固定事件序号
         */
        @Override
        public long append(String projectId, String actor, String action, Map<String, Object> details) {
            this.projectId = projectId;
            this.actor = actor;
            this.action = action;
            this.details = details;
            return 1L;
        }

        /**
         * 本测试替身不保存事件历史。
         *
         * @param projectId 项目标识
         * @return 空事件列表
         */
        @Override
        public List<Map<String, Object>> readAll(String projectId) {
            return List.of();
        }
    }
}
