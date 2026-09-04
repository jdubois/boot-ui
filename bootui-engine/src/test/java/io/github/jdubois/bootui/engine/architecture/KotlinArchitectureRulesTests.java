package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the Kotlin-awareness of the Architecture rules against bytecode a real Kotlin compiler
 * produced (see {@code src/test/kotlin}). Two failure modes are covered: findings about members the
 * compiler generated rather than the developer wrote, and findings that judge a suspending function
 * by the {@code Continuation}-shaped signature it compiles to instead of the one it declares.
 */
class KotlinArchitectureRulesTests {

    private static final String KOTLIN_FIXTURES = "io.github.jdubois.bootui.engine.architecture.kotlinfixtures";

    private ArchitectureContext context() {
        JavaClasses classes = new ClassFileImporter().importPackages(KOTLIN_FIXTURES);
        return new ArchitectureContext(classes, List.of(KOTLIN_FIXTURES), ArchitecturePlatform.SPRING);
    }

    private ArchitectureRuleResultDto evaluate(ArchitectureRule rule) {
        return rule.evaluate(context());
    }

    @Test
    void suspendingScheduledMethodsAreJudgedOnTheirDeclaredSignature() {
        ArchitectureRuleResultDto result = evaluate(new ScheduledMethodsShouldHaveSupportedSignaturesRule());

        // refreshOrders() is a supported suspending scheduled function and must not be reported at all;
        // countOrders(): Long is reported once, for the result Spring discards -- not for its continuation
        // parameter, and not again through the synthetic $suspendImpl body Kotlin generates.
        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations())
                .singleElement()
                .asString()
                .contains("countOrders")
                .contains("returns java.lang.Long");
        assertThat(result.sampleViolations()).noneMatch(violation -> violation.contains("declares parameters"));
        assertThat(result.sampleViolations()).noneMatch(violation -> violation.contains("suspendImpl"));
    }

    @Test
    void suspendingAsyncMethodsAreReportedAsUnsupportedRatherThanAsAnObjectReturn() {
        ArchitectureRuleResultDto result = evaluate(new AsyncMethodsShouldHaveSupportedSignaturesRule());

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations())
                .singleElement()
                .asString()
                .contains("notifyCustomer")
                .contains("Kotlin suspending function")
                .doesNotContain("java.lang.Object");
    }

    @Test
    void proxyabilityIsJudgedOnDeclaredMethodsOnly() {
        ArchitectureRuleResultDto result = evaluate(new ProxiedMethodsShouldNotBePrivateOrStaticRule());

        // The private @Transactional function stays reported; the static synthetic $suspendImpl copy of
        // the annotated suspending function does not, because the developer never declared it.
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).singleElement().asString().contains("auditOrder");
        assertThat(result.recommendation()).contains("kotlin-spring");
    }

    @Test
    void compilerGeneratedSelfInvocationIsNotReported() {
        ArchitectureRuleResultDto result = evaluate(new NoSelfInvocationOfProxiedMethodsRule());

        // Calling one's own private @Transactional function is a real finding; a suspending function
        // dispatching to its own generated $suspendImpl body is not.
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).singleElement().asString().contains("auditOrder");
    }

    @Test
    void kotlinFileFacadesAndGeneratedHoldersAreNotUtilityClassFindings() {
        ArchitectureRuleResultDto result = evaluate(new UtilityClassesShouldBeFinalWithPrivateConstructorRule());

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void theWholeRulesetProducesNoFindingAboutACompilerGeneratedMember() {
        ArchitectureContext context = context();

        for (ArchitectureRule rule : ArchitectureRuleRegistry.activeRules()) {
            assertThat(rule.evaluate(context).sampleViolations())
                    .as("rule findings must never name a compiler-generated Kotlin member")
                    .noneMatch(violation -> violation.contains("$suspendImpl")
                            || violation.contains("$default")
                            || violation.contains("component1")
                            || violation.contains("Kt.formatOrderId"));
        }
    }
}
