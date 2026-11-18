package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.RepositoryDefinition;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.transaction.annotation.Transactional;

class ArchitectureEvaluationEvidenceTests {
    @TempDir
    Path tempDir;

    @Test
    void selectedArchUnitTargetsNotEmptyShouldSuccessEstablishCompletion() {
        ArchitectureContext context = context(NoFields.class);
        assertThat(new NoFieldInjectionRule().evaluate(context).status()).isEqualTo("PASS");
        assertThat(context.evidence().usable()).isFalse();
        assertThat(new ControllersShouldNotDependOnRepositoriesRule()
                        .evaluate(context)
                        .status())
                .isEqualTo("PASS");
        assertThat(context.evidence().usable()).isFalse();
        assertThat(new NoFieldInjectionRule().evaluate(context(OneField.class)).status())
                .isEqualTo("PASS");
        ArchitectureContext field = context(OneField.class);
        new NoFieldInjectionRule().evaluate(field);
        assertThat(field.evidence().usable()).isTrue();
    }

    @Test
    void repeatedScheduleUsesTheSameComposedAnnotationSelectorForFindingsAndCompletion() {
        ArchitectureContext context = context(RepeatedSchedule.class);
        var result = new ScheduledMethodsShouldHaveSupportedSignaturesRule().evaluate(context);
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(context.evidence().usable()).isTrue();
        assertThat(result.violationCount()).isPositive();
    }

    @ParameterizedTest
    @ValueSource(classes = {RepositoryOnly.class, DefinedRepositoryOnly.class, NoFields.class})
    void exemptRepositoriesAndNonInterfacesDoNotEstablishTransactionalInterfaceEvidence(Class<?> type) {
        ArchitectureContext context = context(type);
        var result = new TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule().evaluate(context);

        assertThat(result.status()).isEqualTo("PASS");
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations()).isEmpty();
        assertThat(context.evidence().evaluated()).isTrue();
        assertThat(context.evidence().usable()).isFalse();
        assertThat(context.evidence().requiredUnknown()).isFalse();
    }

    @Test
    void ordinaryTransactionalInterfaceFindingsStillEstablishUsableEvidence() {
        ArchitectureContext context = context(OrdinaryTransactionalInterface.class);
        var result = new TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule().evaluate(context);

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(context.evidence().usable()).isTrue();
        assertThat(context.evidence().requiredUnknown()).isFalse();
    }

    @Test
    void failureAfterTargetSelectionDoesNotCompleteAndRealInfoSurvivesDismissal() {
        ArchitectureRule broken = new AbstractArchitectureRule(new NoFieldInjectionRule().definition()) {
            @Override
            ArchRule rule(ArchitectureContext context) {
                context.evidence().observed();
                throw new IllegalStateException();
            }
        };
        ArchitectureContext context = context(OneField.class);
        assertThat(broken.evaluate(context).status()).isEqualTo("ERROR");
        assertThat(context.evidence().usable()).isFalse();

        ArchitectureScanner scanner = new ArchitectureScanner(
                () -> List.of(getClass().getPackageName()),
                packages -> CompiledOutputFixtures.importFrom(tempDir, "target/classes", LegacyDate.class),
                ArchitecturePlatform.SPRING,
                Clock.systemUTC(),
                List.of(new NoLegacyDateTimeRule(), broken));
        var report = scanner.scan();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.evidence().usable()).isTrue();
        assertThat(scanner.applyDismissals(report, Set.of("ARCH-CODE-008")).evidence())
                .isEqualTo(report.evidence());
    }

    @Test
    void unresolvedRequiredObservationRetainsGenuineFindingsWithoutCompleting() {
        ArchitectureRule partial = new AbstractArchitectureRule(new NoLegacyDateTimeRule().definition()) {
            @Override
            ArchRule rule(ArchitectureContext context) {
                return com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
                        .should(
                                new com.tngtech.archunit.lang.ArchCondition<com.tngtech.archunit.core.domain.JavaClass>(
                                        "observe one finding alongside an unresolved dependency") {
                                    @Override
                                    public void check(
                                            com.tngtech.archunit.core.domain.JavaClass type,
                                            com.tngtech.archunit.lang.ConditionEvents events) {
                                        context.evidence().observed();
                                        context.evidence().markRequiredUnknown();
                                        events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(
                                                type, "Observed finding"));
                                    }
                                });
            }
        };
        ArchitectureContext context = context(OneField.class);
        assertThat(partial.evaluate(context).status()).isEqualTo("VIOLATION");
        assertThat(context.evidence().usable()).isTrue();
        assertThat(context.evidence().requiredUnknown()).isTrue();
    }

    private static ArchitectureContext context(Class<?> type) {
        return new ArchitectureContext(
                new ClassFileImporter().importClasses(type),
                List.of(type.getPackageName()),
                ArchitecturePlatform.SPRING);
    }

    static class NoFields {}

    static class OneField {
        String value;
    }

    @NoRepositoryBean
    interface BaseRepository<T, ID> extends CrudRepository<T, ID> {}

    @Transactional
    interface RepositoryOnly extends BaseRepository<Object, Long> {}

    @RepositoryDefinition(domainClass = Object.class, idClass = Long.class)
    @Transactional
    interface DefinedRepositoryOnly {}

    interface OrdinaryTransactionalInterface {
        @Transactional
        void save();
    }

    static class LegacyDate {
        java.util.Date value;
    }

    static class RepeatedSchedule {
        @Schedules({@Scheduled(fixedDelay = 1000), @Scheduled(fixedDelay = 2000)})
        void scheduled(String unsupportedParameter) {}
    }
}
