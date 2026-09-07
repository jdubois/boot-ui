package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadFactory;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;

class ArchitectureAccuracyTests {

    @Test
    void privateFinalInstanceLoggersAreValidOnBothPlatforms() {
        for (ArchitecturePlatform platform : ArchitecturePlatform.values()) {
            ArchitectureRuleResultDto result =
                    evaluate(new LoggersShouldBePrivateStaticFinalRule(), platform, InstanceLoggers.class);
            assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
            assertThat(result.recommendation()).contains("either static or instance");
        }
    }

    @Test
    void mutableAndExposedLoggersRemainFindingsOnBothPlatforms() {
        for (ArchitecturePlatform platform : ArchitecturePlatform.values()) {
            ArchitectureRuleResultDto result =
                    evaluate(new LoggersShouldBePrivateStaticFinalRule(), platform, UnsafeLoggers.class);
            assertThat(result.violationCount()).isEqualTo(3);
        }
    }

    @Test
    void loggerNameExemptionIsSpecificToQuarkusInjectionShapeAndJbossType() {
        ArchitectureRule rule = new LoggersShouldBePrivateStaticFinalRule();
        ArchitectureRuleResultDto quarkus = evaluate(rule, ArchitecturePlatform.QUARKUS, QualifiedLoggers.class);
        assertThat(quarkus.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(quarkus.violationCount()).isEqualTo(5);
        assertThat(quarkus.sampleViolations()).noneMatch(value -> value.contains(".injected "));
        assertThat(quarkus.sampleViolations())
                .anyMatch(value -> value.contains(".staticLogger"))
                .anyMatch(value -> value.contains(".finalLogger"))
                .anyMatch(value -> value.contains(".producerLogger"))
                .anyMatch(value -> value.contains(".wrongLoggerType"))
                .anyMatch(value -> value.contains(".unrelatedAnnotation"));

        assertThat(evaluate(rule, ArchitecturePlatform.SPRING, QualifiedLoggers.class)
                        .violationCount())
                .isEqualTo(6);
    }

    @Test
    void repeatedAndComposedSchedulesAreCheckedOncePerMethodAndReason() {
        ArchitectureRuleResultDto result = evaluate(
                new ScheduledMethodsShouldHaveSupportedSignaturesRule(),
                ArchitecturePlatform.SPRING,
                RepeatedJobs.class);
        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(5);
        assertThat(result.sampleViolations()).anyMatch(value -> value.contains(".repeated("));
        assertThat(result.sampleViolations()).anyMatch(value -> value.contains(".container("));
        assertThat(result.sampleViolations()).anyMatch(value -> value.contains(".composed("));
        assertThat(result.sampleViolations()).anyMatch(value -> value.contains(".nested("));
        assertThat(result.sampleViolations()).anyMatch(value -> value.contains(".metaContainer("));
    }

    @Test
    void emptyAndUnrelatedAnnotationsDoNotDeclareSchedulesAndCyclesTerminate() {
        assertThat(evaluate(
                                new ScheduledMethodsShouldHaveSupportedSignaturesRule(),
                                ArchitecturePlatform.SPRING,
                                NotScheduled.class)
                        .status())
                .isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void ordinaryJavaContinuationParameterIsNotCompilerPlumbing() {
        ArchitectureRuleResultDto result = evaluate(
                new ScheduledMethodsShouldHaveSupportedSignaturesRule(),
                ArchitecturePlatform.SPRING,
                JavaContinuationJob.class);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).singleElement().asString().contains("declares parameters");
    }

    @Test
    void completionStageHasNonDeferredDiagnosisRatherThanOnlyIgnoredReturnAdvice() {
        ArchitectureRuleResultDto result = evaluate(
                new ScheduledMethodsShouldHaveSupportedSignaturesRule(), ArchitecturePlatform.SPRING, StageJobs.class);
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations()).allMatch(value -> value.contains("non-deferred"));
        assertThat(result.recommendation()).contains("Verify custom adapters separately");
    }

    @Test
    void matchingAReactivePackageIsNotProofOfAReactiveType() {
        ArchitectureRuleResultDto result = evaluate(
                new ScheduledMethodsShouldHaveSupportedSignaturesRule(),
                ArchitecturePlatform.SPRING,
                NonReactiveJobs.class);
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations()).anyMatch(value -> value.contains(".rxScheduler("));
        assertThat(result.sampleViolations()).anyMatch(value -> value.contains(".reactorSignal("));
    }

    @Test
    void knownReactiveTypesAndSubtypesAreAccepted() {
        ArchitectureRuleResultDto result = evaluate(
                new ScheduledMethodsShouldHaveSupportedSignaturesRule(),
                ArchitecturePlatform.SPRING,
                ReactiveJobs.class);
        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void nominalReactiveReturnRecognitionDoesNotRequireResolvingTheOptionalLibrary() {
        ArchConfiguration.withThreadLocalScope(configuration -> {
            configuration.setResolveMissingDependenciesFromClassPath(false);
            ArchitectureRuleResultDto result = evaluate(
                    new ScheduledMethodsShouldHaveSupportedSignaturesRule(),
                    ArchitecturePlatform.SPRING,
                    NominalReactiveJob.class);
            assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
        });
    }

    @Test
    void supportedSignaturesAgreeWithTheBaselineStandardReactiveAdapters() {
        org.springframework.core.ReactiveAdapterRegistry registry =
                new org.springframework.core.ReactiveAdapterRegistry();
        for (Class<?> type : List.of(
                org.reactivestreams.Publisher.class,
                reactor.core.publisher.Mono.class,
                java.util.concurrent.Flow.Publisher.class,
                io.smallrye.mutiny.Uni.class,
                DeferredUni.class)) {
            org.springframework.core.ReactiveAdapter adapter = registry.getAdapter(type);
            assertThat(adapter).as("standard adapter for %s", type).isNotNull();
            assertThat(adapter.getDescriptor().isDeferred())
                    .as("deferred %s", type)
                    .isTrue();
        }
        assertThat(registry.getAdapter(CompletionStage.class).getDescriptor().isDeferred())
                .isFalse();
        assertThat(registry.getAdapter(reactor.core.publisher.Signal.class)).isNull();
    }

    @Test
    void threadFactoriesMayAllocateThreadsWithoutExemptingOtherMethods() {
        for (ArchitecturePlatform platform : ArchitecturePlatform.values()) {
            assertThat(evaluate(new NoDirectThreadInstantiationRule(), platform, NamedFactory.class)
                            .status())
                    .isEqualTo(ArchitectureRuleSupport.PASS);
            assertThat(evaluate(new NoDirectThreadInstantiationRule(), platform, CovariantFactory.class)
                            .status())
                    .isEqualTo(ArchitectureRuleSupport.PASS);
            assertThat(evaluate(new NoDirectThreadInstantiationRule(), platform, InheritedFactory.class)
                            .status())
                    .isEqualTo(ArchitectureRuleSupport.PASS);
            assertThat(evaluate(
                                    new NoDirectThreadInstantiationRule(),
                                    platform,
                                    AnonymousFactoryHolder.FACTORY.getClass())
                            .status())
                    .isEqualTo(ArchitectureRuleSupport.PASS);
            ArchitectureRuleResultDto invalid =
                    evaluate(new NoDirectThreadInstantiationRule(), platform, FactoryWithOtherMethods.class);
            assertThat(invalid.violationCount()).isEqualTo(2);
            assertThat(invalid.sampleViolations()).anyMatch(value -> value.contains("unmanaged()"));
            assertThat(invalid.sampleViolations()).anyMatch(value -> value.contains("newThread()"));
        }
    }

    @Test
    void aMethodNameAloneDoesNotEstablishAThreadFactoryContract() {
        ArchitectureRuleResultDto result =
                evaluate(new NoDirectThreadInstantiationRule(), ArchitecturePlatform.SPRING, ImpostorFactory.class);
        assertThat(result.violationCount()).isEqualTo(2);
    }

    private static ArchitectureRuleResultDto evaluate(
            ArchitectureRule rule, ArchitecturePlatform platform, Class<?>... fixtures) {
        JavaClasses classes = new ClassFileImporter().importClasses(fixtures);
        return rule.evaluate(
                new ArchitectureContext(classes, List.of(ArchitectureAccuracyTests.class.getPackageName()), platform));
    }

    private static class InstanceLoggers {
        private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(getClass());
        private final java.util.logging.Logger jul = java.util.logging.Logger.getLogger("instance");
        private final org.jboss.logging.Logger jboss = org.jboss.logging.Logger.getLogger(getClass());
    }

    private static class UnsafeLoggers {
        private org.slf4j.Logger mutable;
        public final org.slf4j.Logger exposed = org.slf4j.LoggerFactory.getLogger(getClass());
        public static final org.slf4j.Logger shared = org.slf4j.LoggerFactory.getLogger("shared");
    }

    private static class QualifiedLoggers {
        @io.quarkus.logging.LoggerName("injected")
        org.jboss.logging.Logger injected;

        @io.quarkus.logging.LoggerName("static")
        static org.jboss.logging.Logger staticLogger;

        @io.quarkus.logging.LoggerName("final")
        final org.jboss.logging.Logger finalLogger = org.jboss.logging.Logger.getLogger("final");

        @io.quarkus.logging.LoggerName("wrong-type")
        org.slf4j.Logger wrongLoggerType;

        @io.quarkus.logging.LoggerName("producer")
        @jakarta.enterprise.inject.Produces
        org.jboss.logging.Logger producerLogger;

        @LoggerName
        org.jboss.logging.Logger unrelatedAnnotation;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    private @interface LoggerName {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
    @Scheduled(fixedDelay = 1000)
    private @interface EverySecond {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @EverySecond
    private @interface NestedSchedule {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @Schedules({@Scheduled(fixedDelay = 1000), @Scheduled(fixedDelay = 2000)})
    private @interface MultipleSchedules {}

    private static class RepeatedJobs {
        @Scheduled(fixedDelay = 1000)
        @Scheduled(fixedDelay = 2000)
        void repeated(String argument) {}

        @Schedules({@Scheduled(fixedDelay = 1000), @Scheduled(fixedDelay = 2000)})
        void container(String argument) {}

        @EverySecond
        void composed(String argument) {}

        @NestedSchedule
        void nested(String argument) {}

        @MultipleSchedules
        void metaContainer(String argument) {}
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
    @CycleB
    private @interface CycleA {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.ANNOTATION_TYPE)
    @CycleA
    private @interface CycleB {}

    private static class NotScheduled {
        @Schedules({})
        int empty(String argument) {
            return 1;
        }

        @CycleA
        int cycle(String argument) {
            return 1;
        }

        @UnrelatedAnnotations.Scheduled
        int unrelated(String argument) {
            return 1;
        }
    }

    private static class UnrelatedAnnotations {
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        @interface Scheduled {}
    }

    private static class JavaContinuationJob {
        @Scheduled(fixedDelay = 1000)
        void run(kotlin.coroutines.Continuation<String> continuation) {}
    }

    private static class StageJobs {
        @Scheduled(fixedDelay = 1000)
        CompletionStage<Void> stage() {
            return null;
        }

        @Scheduled(fixedDelay = 1000)
        CompletableFuture<Void> future() {
            return null;
        }
    }

    private static class NonReactiveJobs {
        @Scheduled(fixedDelay = 1000)
        io.reactivex.rxjava3.core.Scheduler rxScheduler() {
            return null;
        }

        @Scheduled(fixedDelay = 1000)
        reactor.core.publisher.Signal<String> reactorSignal() {
            return null;
        }
    }

    private abstract static class DeferredSingle extends io.reactivex.rxjava3.core.Single<String> {}

    private static class NominalReactiveJob {
        @Scheduled(fixedDelay = 1000)
        reactor.core.publisher.Mono<String> run() {
            return null;
        }
    }

    private interface DeferredPublisher extends org.reactivestreams.Publisher<String> {}

    private interface DeferredUni extends io.smallrye.mutiny.Uni<String> {}

    private static class ReactiveJobs {
        @Scheduled(fixedDelay = 1000)
        DeferredSingle rxSubtype() {
            return null;
        }

        @Scheduled(fixedDelay = 1000)
        DeferredPublisher publisherSubtype() {
            return null;
        }

        @Scheduled(fixedDelay = 1000)
        DeferredUni mutinySubtype() {
            return null;
        }

        @Scheduled(fixedDelay = 1000)
        reactor.core.publisher.Mono<String> mono() {
            return null;
        }
    }

    private static class NamedFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            return new Thread(task);
        }
    }

    private static class WorkerThread extends Thread {}

    private static class InheritedFactory extends NamedFactory {
        @Override
        public Thread newThread(Runnable task) {
            return new Thread(task);
        }
    }

    private static class CovariantFactory implements ThreadFactory {
        @Override
        public WorkerThread newThread(Runnable task) {
            return new WorkerThread();
        }
    }

    private static class AnonymousFactoryHolder {
        static final ThreadFactory FACTORY = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable task) {
                return new Thread(task);
            }
        };
    }

    private static class FactoryWithOtherMethods extends NamedFactory {
        public Thread newThread() {
            return new Thread();
        }

        Thread unmanaged() {
            return new Thread();
        }
    }

    private static class ImpostorFactory {
        public Thread newThread(Runnable task) {
            return new Thread(task);
        }

        private static Thread newThread() {
            return new Thread();
        }
    }
}
