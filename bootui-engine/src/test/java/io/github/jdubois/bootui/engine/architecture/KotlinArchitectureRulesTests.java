package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import io.github.jdubois.bootui.engine.architecture.kotlinfixtures.LegacyDateParameterMapper;
import io.github.jdubois.bootui.engine.architecture.kotlinfixtures.LegacyTicketMapper;
import io.github.jdubois.bootui.engine.architecture.kotlininjectionfixtures.KotlinConstructorInjectedComponent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * Pins the Kotlin-awareness of the Architecture rules against bytecode a real Kotlin compiler
 * produced (see {@code src/test/kotlin}). Two failure modes are covered: findings about members the
 * compiler generated rather than the developer wrote, and findings that judge a suspending function
 * by the {@code Continuation}-shaped signature it compiles to instead of the one it declares.
 */
class KotlinArchitectureRulesTests {

    private static final String KOTLIN_FIXTURES = "io.github.jdubois.bootui.engine.architecture.kotlinfixtures";

    private static final String KOTLIN_INJECTION_FIXTURES =
            "io.github.jdubois.bootui.engine.architecture.kotlininjectionfixtures";

    private ArchitectureContext context() {
        JavaClasses classes = new ClassFileImporter().importPackages(KOTLIN_FIXTURES);
        return new ArchitectureContext(classes, List.of(KOTLIN_FIXTURES), ArchitecturePlatform.SPRING);
    }

    private ArchitectureRuleResultDto evaluate(ArchitectureRule rule) {
        return rule.evaluate(context());
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void convertingExternalDatePropertiesDoesNotCountAsLegacyUse(ArchitecturePlatform platform) {
        JavaClasses classes = new ClassFileImporter().importClasses(LegacyTicketMapper.class);
        ArchitectureRuleResultDto result = new NoLegacyDateTimeRule()
                .evaluate(new ArchitectureContext(classes, List.of(KOTLIN_FIXTURES), platform));

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void aLegacyParameterRemainsAFindingEvenWhenImmediatelyConverted(ArchitecturePlatform platform) {
        JavaClasses classes = new ClassFileImporter().importClasses(LegacyDateParameterMapper.class);
        ArchitectureRuleResultDto result = new NoLegacyDateTimeRule()
                .evaluate(new ArchitectureContext(classes, List.of(KOTLIN_FIXTURES), platform));

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations())
                .singleElement()
                .asString()
                .contains("has parameter of type <java.util.Date>")
                .contains("LegacyDateTimeFixtures.kt");
    }

    @Test
    void transactionAnnotationsOnKotlinSpringDataRepositoriesAreSupportedButOrdinaryInterfacesStayReported() {
        ArchitectureRuleResultDto result = evaluate(new TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule());

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations())
                .singleElement()
                .asString()
                .contains("KotlinCartOperations.expireIfDueInOwnTransaction(java.util.UUID, java.time.Instant)")
                .doesNotContain("KotlinCartRepository");
    }

    @Test
    void suspendingScheduledMethodsAreJudgedOnTheirDeclaredSignature() {
        ArchitectureRuleResultDto result = evaluate(new ScheduledMethodsShouldHaveSupportedSignaturesRule());

        // Both Unit and value-returning suspend functions are supported; only a real source argument
        // is invalid. The continuation and generated $suspendImpl copy must not add findings.
        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations())
                .singleElement()
                .asString()
                .contains("reloadOrders")
                .contains("declares parameters");
        assertThat(result.sampleViolations()).noneMatch(violation -> violation.contains("countOrders"));
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

        // Not findings: a suspending function dispatching to its own generated $suspendImpl body, and a
        // $default bridge calling the very function it exists to reach. Both are calls the compiler makes.
        assertThat(result.violationCount()).isEqualTo(3);
        assertThat(result.sampleViolations()).noneMatch(violation -> violation.contains("$suspendImpl"));
        assertThat(result.sampleViolations()).noneMatch(violation -> violation.contains("$default"));
        assertThat(result.sampleViolations())
                .anySatisfy(violation -> assertThat(violation).contains("auditOrder"));
    }

    @Test
    void selfInvocationThroughADefaultArgumentBridgeIsReportedAgainstTheDeclaredFunction() {
        ArchitectureRuleResultDto result = evaluate(new NoSelfInvocationOfProxiedMethodsRule());

        // expireNow calls expire(id), which compiles into a call to the $default bridge. The finding is
        // real and must survive the bridge, naming the function the developer can actually refactor.
        assertThat(result.sampleViolations())
                .anySatisfy(violation ->
                        assertThat(violation).contains("expireNow").contains("expire(long, java.lang.String)"));
    }

    @Test
    void selfInvocationFromInsideALambdaIsStillReported() {
        ArchitectureRuleResultDto result = evaluate(new NoSelfInvocationOfProxiedMethodsRule());

        // Kotlin puts this lambda body in a synthetic method of the same class. Skipping every synthetic
        // origin, rather than only the compiler's dispatch bridges, would silence a real proxy bypass.
        assertThat(result.sampleViolations())
                .anySatisfy(violation -> assertThat(violation).contains("expireLater"));
    }

    @Test
    void nestedVariantsOfASealedExceptionHierarchyAreNotNamingFindings() {
        ArchitectureRuleResultDto result = evaluate(new ExceptionsShouldBeNamedExceptionRule());

        // KotlinClaimException.AlreadyAssigned already says what it is; renaming it would stutter.
        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void kotlinFileFacadesAndGeneratedHoldersAreNotUtilityClassFindings() {
        ArchitectureRuleResultDto result = evaluate(new UtilityClassesShouldBeFinalWithPrivateConstructorRule());

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void constructorPropertyInjectionIsNotFieldInjectionButKotlinFieldInjectionStillIs() {
        JavaClasses classes = new ClassFileImporter().importPackages(KOTLIN_INJECTION_FIXTURES);
        // Guard against a vacuous pass: the compiler really copied the constructor-property annotations onto the
        // backing fields, which is the bytecode that used to be reported (#1175).
        JavaClass constructorInjected = classes.get(KotlinConstructorInjectedComponent.class);
        assertThat(constructorInjected.getField("sessionNamespace").isAnnotatedWith(Value.class))
                .isTrue();
        assertThat(constructorInjected.getField("retries").isAnnotatedWith(Value.class))
                .isTrue();
        assertThat(constructorInjected.getField("fallbackRepository").isAnnotatedWith(Autowired.class))
                .isTrue();

        ArchitectureRuleResultDto result = new NoFieldInjectionRule()
                .evaluate(new ArchitectureContext(
                        classes, List.of(KOTLIN_INJECTION_FIXTURES), ArchitecturePlatform.SPRING));

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(3);
        assertThat(result.sampleViolations())
                .allSatisfy(violation -> assertThat(violation).contains("KotlinFieldInjectedComponent"))
                .anySatisfy(violation -> assertThat(violation).contains("KotlinFieldInjectedComponent.repository>"))
                .anySatisfy(violation -> assertThat(violation).contains("KotlinFieldInjectedComponent.bodyProperty>"))
                .anySatisfy(violation -> assertThat(violation).contains("KotlinFieldInjectedComponent.mismatched>"));
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
