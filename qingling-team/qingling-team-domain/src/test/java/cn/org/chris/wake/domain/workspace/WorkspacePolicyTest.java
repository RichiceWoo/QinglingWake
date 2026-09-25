package cn.org.chris.wake.domain.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证共享工作区 Owner、reviewer 和路径穿越规则。
 */
class WorkspacePolicyTest {

    /**
     * 各角色只能写入 Python 权限矩阵指定的 Owner 目录。
     *
     * @param role 写入角色
     * @param path 相对路径
     */
    @ParameterizedTest
    @CsvSource({
            "manager,needs/requirements.md",
            "pm,design/product_spec.md",
            "rd,tech/tech_design.md",
            "rd,code/backend/main.py",
            "qa,qa/defects/bug_1.md"
    })
    void shouldAllowOwnerPaths(String role, String path) {
        assertThat(WorkspacePolicy.validateWrite(role, path).toString()).isEqualTo(path);
    }

    /**
     * 角色越权和邮箱、事件直写必须被拒绝。
     *
     * @param role 写入角色
     * @param path 相对路径
     */
    @ParameterizedTest
    @CsvSource({
            "pm,code/app.py",
            "rd,design/product_spec.md",
            "manager,mailboxes/pm.json",
            "manager,events.jsonl"
    })
    void shouldRejectUnauthorizedOrDedicatedPaths(String role, String path) {
        assertThatThrownBy(() -> WorkspacePolicy.validateWrite(role, path))
                .isInstanceOf(SecurityException.class);
    }

    /**
     * reviewer 文件名前缀必须与当前角色一致且 topic 不能为空。
     */
    @Test
    void shouldValidateReviewNaming() {
        assertThat(WorkspacePolicy.validateWrite("rd", "reviews/product_design/rd_review.md"))
                .isNotNull();
        assertThatThrownBy(() -> WorkspacePolicy.validateWrite("rd", "reviews/product_design/qa_review.md"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> WorkspacePolicy.validateWrite("manager", "reviews/design/manager.md"))
                .isInstanceOf(SecurityException.class);
    }

    /**
     * 绝对路径、父目录段和反斜杠路径全部拒绝。
     */
    @Test
    void shouldRejectTraversalAndNonPosixPaths() {
        assertThatThrownBy(() -> WorkspacePolicy.validateRead("pm", "/etc/passwd"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> WorkspacePolicy.validateRead("pm", "../../etc/passwd"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> WorkspacePolicy.validateRead("pm", "design\\secret.md"))
                .isInstanceOf(SecurityException.class);
    }
}
