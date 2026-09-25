package cn.org.chris.wake.domain;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 防止领域模块反向依赖框架、基础设施或响应式类型。
 */
@AnalyzeClasses(packages = "cn.org.chris.wake.domain", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainArchitectureTest {

    /**
     * 领域代码只使用 JDK 与自身类型，不得感知迁移目标框架和外层模块。
     */
    @ArchTest
    static final ArchRule DOMAIN_MUST_NOT_DEPEND_ON_FRAMEWORKS = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "io.agentscope..",
                    "reactor..",
                    "java.sql..",
                    "cn.org.chris.wake.app..",
                    "cn.org.chris.wake.infra..",
                    "cn.org.chris.wake.adapter..",
                    "cn.org.chris.wake.starter.."
            );
}
