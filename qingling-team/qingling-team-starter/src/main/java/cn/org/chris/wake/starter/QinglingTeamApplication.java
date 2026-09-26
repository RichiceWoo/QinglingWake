package cn.org.chris.wake.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Qingling Team Spring Boot 进程入口，仅扫描 starter 中显式装配的组件。
 */
@SpringBootApplication(scanBasePackages = "cn.org.chris.wake.starter")
public class QinglingTeamApplication {

    /** 禁止实例化进程入口。 */
    protected QinglingTeamApplication() {
    }

    /**
     * 启动 Spring 容器和受控运行时生命周期。
     *
     * @param arguments Spring Boot 命令行参数
     */
    public static void main(String[] arguments) {
        SpringApplication.run(QinglingTeamApplication.class, arguments);
    }
}
