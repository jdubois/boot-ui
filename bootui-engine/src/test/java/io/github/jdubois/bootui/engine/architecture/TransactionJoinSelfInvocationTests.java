package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cache.annotation.CachePut;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ARCH-SPRING-004 and self-calls between transactional methods (#1176): a call that would only join the caller's
 * transaction through the proxy loses nothing by skipping it, while every call whose outcome the proxy would change
 * stays reported.
 */
class TransactionJoinSelfInvocationTests {

    @ParameterizedTest
    @ValueSource(
            classes = {
                RequiredCallsRequired.class,
                RequiredCallsReadOnly.class,
                MandatoryCallsRequired.class,
                RequiredCallsSupports.class,
                RequiresNewCallsRequired.class,
                ClassLevelTransactionalWithPrivateHelper.class,
                RecursivePrivateHelper.class,
                JakartaCallsJakarta.class,
                SpringCallsJakarta.class,
                MatchingAttributes.class
            })
    void selfCallThatOnlyJoinsTheCallersTransactionIsNotReported(Class<?> fixture) {
        ArchitectureRuleResultDto result = evaluate(fixture);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
        assertThat(result.violationCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            classes = {
                NonTransactionalCaller.class,
                CalleeRequiresNew.class,
                CalleeNested.class,
                CalleeNotSupported.class,
                CalleeNever.class,
                CallerSupports.class,
                CallerNotSupported.class,
                ClassLevelSupportsCaller.class,
                JakartaCallerSupports.class,
                DifferentRollbackFor.class,
                DifferentNoRollbackFor.class,
                DifferentTransactionManager.class,
                DifferentIsolation.class,
                DifferentTimeout.class,
                CalleeAlsoCached.class,
                CalleeAlsoAsync.class,
                CallFromLambda.class,
                FinalCaller.class,
                StaticCaller.class,
                PrivateHelperAlsoCalledWithoutTransaction.class,
                PrivateHelperUsedAsMethodReference.class,
                PrivateHelperCalledFromLambda.class,
                CallInsideTryCatch.class,
                PrivateHelperCalledInsideTry.class
            })
    void selfCallWhoseOutcomeTheProxyWouldChangeStaysReported(Class<?> fixture) {
        ArchitectureRuleResultDto result = evaluate(fixture);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).singleElement().asString().contains(fixture.getSimpleName());
    }

    @Test
    void inheritedTransactionalTargetStaysReportedWhenTheSubclassIsAsyncAtClassLevel() {
        ArchitectureRuleResultDto result = evaluate(AsyncSubclass.class, TransactionalBase.class);

        // Spring's @Async pointcut matches the proxied class, so the inherited callee is asynchronous through it.
        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .anySatisfy(violation -> assertThat(violation).contains("AsyncSubclass.caller()"));
    }

    private static ArchitectureRuleResultDto evaluate(Class<?>... fixtures) {
        JavaClasses classes = new ClassFileImporter().importClasses(fixtures);
        return new NoSelfInvocationOfProxiedMethodsRule()
                .evaluate(new ArchitectureContext(
                        classes,
                        List.of(TransactionJoinSelfInvocationTests.class.getPackageName()),
                        ArchitecturePlatform.SPRING));
    }

    // ---- Joining self-calls: nothing is lost. ----

    @Service
    private static class RequiredCallsRequired {

        @Transactional
        void caller() {
            callee();
        }

        @Transactional
        void callee() {}
    }

    @Service
    private static class RequiredCallsReadOnly {

        @Transactional
        void caller() {
            callee();
        }

        @Transactional(readOnly = true)
        void callee() {}
    }

    @Service
    private static class MandatoryCallsRequired {

        @Transactional(propagation = Propagation.MANDATORY)
        void caller() {
            callee();
        }

        @Transactional
        void callee() {}
    }

    @Service
    private static class RequiredCallsSupports {

        @Transactional
        void caller() {
            callee();
        }

        @Transactional(propagation = Propagation.SUPPORTS)
        void callee() {}
    }

    @Service
    private static class RequiresNewCallsRequired {

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        void caller() {
            callee();
        }

        @Transactional
        void callee() {}
    }

    /** The issue's shape: a private helper of class-level transactional methods, and a direct public caller. */
    @Service
    @Transactional
    private static class ClassLevelTransactionalWithPrivateHelper {

        void reserve() {
            requireAvailable();
        }

        void rename() {
            requireAvailable();
        }

        void check() {
            callee();
        }

        private void requireAvailable() {
            callee();
        }

        @Transactional(readOnly = true)
        void callee() {}
    }

    @Service
    private static class RecursivePrivateHelper {

        @Transactional
        void caller() {
            walk(3);
        }

        private void walk(int depth) {
            if (depth > 0) {
                walk(depth - 1);
            } else {
                callee();
            }
        }

        @Transactional
        void callee() {}
    }

    @Service
    private static class JakartaCallsJakarta {

        @jakarta.transaction.Transactional
        void caller() {
            callee();
        }

        @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.MANDATORY)
        void callee() {}
    }

    @Service
    private static class SpringCallsJakarta {

        @Transactional
        void caller() {
            callee();
        }

        @jakarta.transaction.Transactional
        void callee() {}
    }

    @Service
    private static class MatchingAttributes {

        @Transactional(
                transactionManager = "orders",
                rollbackFor = IOException.class,
                isolation = Isolation.SERIALIZABLE,
                timeout = 5)
        void caller() {
            callee();
        }

        @Transactional(
                value = "orders",
                rollbackFor = IOException.class,
                isolation = Isolation.SERIALIZABLE,
                timeout = 5)
        void callee() {}
    }

    // ---- Self-calls whose outcome the proxy would change. ----

    @Service
    private static class NonTransactionalCaller {

        void caller() {
            callee();
        }

        @Transactional
        void callee() {}
    }

    @Service
    private static class CalleeRequiresNew {

        @Transactional
        void caller() {
            callee();
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        void callee() {}
    }

    @Service
    private static class CalleeNested {

        @Transactional
        void caller() {
            callee();
        }

        @Transactional(propagation = Propagation.NESTED)
        void callee() {}
    }

    @Service
    private static class CalleeNotSupported {

        @Transactional
        void caller() {
            callee();
        }

        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void callee() {}
    }

    @Service
    private static class CalleeNever {

        @Transactional
        void caller() {
            callee();
        }

        @Transactional(propagation = Propagation.NEVER)
        void callee() {}
    }

    @Service
    private static class CallerSupports {

        @Transactional(propagation = Propagation.SUPPORTS)
        void caller() {
            callee();
        }

        @Transactional
        void callee() {}
    }

    @Service
    private static class CallerNotSupported {

        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void caller() {
            callee();
        }

        @Transactional
        void callee() {}
    }

    @Service
    @Transactional(propagation = Propagation.SUPPORTS)
    private static class ClassLevelSupportsCaller {

        void caller() {
            callee();
        }

        @Transactional
        void callee() {}
    }

    @Service
    private static class JakartaCallerSupports {

        @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.SUPPORTS)
        void caller() {
            callee();
        }

        @jakarta.transaction.Transactional
        void callee() {}
    }

    @Service
    private static class DifferentRollbackFor {

        @Transactional
        void caller() {
            callee();
        }

        @Transactional(rollbackFor = IOException.class)
        void callee() {}
    }

    @Service
    private static class DifferentNoRollbackFor {

        @Transactional(noRollbackFor = IllegalStateException.class)
        void caller() {
            callee();
        }

        @Transactional
        void callee() {}
    }

    @Service
    private static class DifferentTransactionManager {

        @Transactional
        void caller() {
            callee();
        }

        @Transactional("audit")
        void callee() {}
    }

    @Service
    private static class DifferentIsolation {

        @Transactional
        void caller() {
            callee();
        }

        @Transactional(isolation = Isolation.SERIALIZABLE)
        void callee() {}
    }

    @Service
    private static class DifferentTimeout {

        @Transactional(timeout = 30)
        void caller() {
            callee();
        }

        @Transactional(timeout = 5)
        void callee() {}
    }

    @Service
    private static class CalleeAlsoCached {

        @Transactional
        void caller() {
            callee();
        }

        @Transactional
        @CachePut("items")
        public String callee() {
            return "item";
        }
    }

    @Service
    private static class CalleeAlsoAsync {

        @Transactional
        void caller() {
            callee();
        }

        @Async
        @Transactional
        public void callee() {}
    }

    /** The lambda may run after the caller's transaction ended, or on another thread. */
    @Service
    private static class CallFromLambda {

        @Transactional
        Runnable caller() {
            return () -> callee();
        }

        @Transactional
        void callee() {}
    }

    /** A class-based proxy cannot override a final method, so it never starts the caller's transaction. */
    @Service
    private static class FinalCaller {

        @Transactional
        final void caller() {
            callee();
        }

        @Transactional
        void callee() {}
    }

    @Service
    private static class StaticCaller {

        @Transactional
        static void caller(StaticCaller self) {
            self.callee();
        }

        @Transactional
        void callee() {}
    }

    @Service
    private static class PrivateHelperAlsoCalledWithoutTransaction {

        @Transactional
        void transactional() {
            helper();
        }

        void plain() {
            helper();
        }

        private void helper() {
            callee();
        }

        @Transactional
        void callee() {}
    }

    @Service
    private static class PrivateHelperUsedAsMethodReference {

        @Transactional
        void transactional() {
            helper();
        }

        @Transactional
        void later(Consumer<Runnable> executor) {
            executor.accept(this::helper);
        }

        private void helper() {
            callee();
        }

        @Transactional
        void callee() {}
    }

    @Service
    private static class PrivateHelperCalledFromLambda {

        @Transactional
        void transactional() {
            helper();
        }

        @Transactional
        Runnable later() {
            return () -> helper();
        }

        private void helper() {
            callee();
        }

        @Transactional
        void callee() {}
    }

    /**
     * Through the proxy the failing callee marks the shared transaction rollback-only and the commit fails; called
     * directly, catching the exception lets the caller commit its partial work.
     */
    @Service
    private static class CallInsideTryCatch {

        @Transactional
        void caller() {
            try {
                callee();
            } catch (RuntimeException ignored) {
                // continue with the next row
            }
        }

        @Transactional
        void callee() {}
    }

    @Service
    private static class PrivateHelperCalledInsideTry {

        @Transactional
        void transactional() {
            try {
                helper();
            } catch (RuntimeException ignored) {
                // continue with the next row
            }
        }

        private void helper() {
            callee();
        }

        @Transactional
        void callee() {}
    }

    private static class TransactionalBase {

        @Transactional
        public void callee() {}
    }

    @Service
    @Async
    private static class AsyncSubclass extends TransactionalBase {

        @Transactional
        public void caller() {
            callee();
        }
    }
}
