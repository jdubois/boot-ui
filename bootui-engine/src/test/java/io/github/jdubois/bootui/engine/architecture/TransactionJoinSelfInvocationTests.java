package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import io.github.jdubois.bootui.engine.archunit.ArchUnitBytecodeImports;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CachePut;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

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

    @Test
    void documentedLazySelfProxyRepairBothExecutesAdviceAndClearsTheFinding() {
        try (var context = new AnnotationConfigApplicationContext(ProxyConfiguration.class)) {
            CountingTransactions transactions = context.getBean(CountingTransactions.class);
            context.getBean(LazySelfProxy.class).caller();
            assertThat(transactions.begins.getAndSet(0)).isEqualTo(1);
            context.getBean(DirectReceiver.class).caller();
            assertThat(transactions.begins).hasValue(0);
        }

        assertThat(evaluate(LazySelfProxy.class).status()).isEqualTo(ArchitectureRuleSupport.PASS);
        assertThat(evaluate(DirectReceiver.class).status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
    }

    @Test
    void knownProxyExpressionsIncludingAliasesLambdasAndBoundReferencesAreNotSelfCalls() {
        ArchitectureRuleResultDto result = evaluate(KnownProxyReceivers.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
        assertThat(result.violationCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "direct",
                "directAlias",
                "unknownParameter",
                "helperResult",
                "branch",
                "reassignedAlias",
                "directLambda",
                "capturedAliasLambda",
                "directReference",
                "unboundReference"
            })
    void realThisAndUnsupportedReceiverShapesKeepTheOwnerBasedHeuristic(String caller) {
        ArchitectureContext context = context(UncertainReceivers.class);
        ArchitectureRuleResultDto result = new NoSelfInvocationOfProxiedMethodsRule().evaluate(context);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .anySatisfy(violation -> assertThat(violation).contains("UncertainReceivers." + caller + "("));
        assertThat(context.evidence().requiredUnknown()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(classes = {MixedCalls.class, MixedReferences.class})
    void aProxyReceiverNeverHidesARealThisReceiverOnTheSameSourceLine(Class<?> fixture) {
        ArchitectureRuleResultDto result = evaluate(fixture);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isPositive();
        assertThat(result.sampleViolations())
                .anySatisfy(violation -> assertThat(violation).contains(fixture.getSimpleName() + ".caller()"));
    }

    @ParameterizedTest
    @ValueSource(classes = {UninjectedSelfReference.class, WritableSelfReference.class, NonLazySelfReference.class})
    void aSelfTypedFieldAloneDoesNotEstablishAProxyReceiver(Class<?> fixture) {
        assertThat(evaluate(fixture).status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
    }

    @Test
    void unavailableReceiverBytecodeKeepsTheHeuristicWithoutIntroducingACoverageGap(@TempDir Path directory)
            throws IOException {
        Path bytecode = directory.resolve("LazySelfProxy.class");
        try (InputStream input = LazySelfProxy.class.getResourceAsStream(
                "/" + LazySelfProxy.class.getName().replace('.', '/') + ".class")) {
            assertThat(input).isNotNull();
            Files.copy(input, bytecode);
        }
        JavaClasses classes = new ClassFileImporter().importPath(bytecode);
        assertThat(classes.get(LazySelfProxy.class).getSource().orElseThrow().getUri())
                .isEqualTo(bytecode.toUri());
        Files.delete(bytecode);
        ArchitectureContext context =
                new ArchitectureContext(classes, List.of(getClass().getPackageName()), ArchitecturePlatform.SPRING);

        ArchitectureRuleResultDto result = new NoSelfInvocationOfProxiedMethodsRule().evaluate(context);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(context.evidence().requiredUnknown()).isFalse();
    }

    private static ArchitectureRuleResultDto evaluate(Class<?>... fixtures) {
        return new NoSelfInvocationOfProxiedMethodsRule().evaluate(context(fixtures));
    }

    private static ArchitectureContext context(Class<?>... fixtures) {
        var imported = ArchUnitBytecodeImports.importClasses(fixtures);
        return new ArchitectureContext(
                imported,
                List.of(TransactionJoinSelfInvocationTests.class.getPackageName()),
                ArchitecturePlatform.SPRING);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class ProxyConfiguration {
        @Bean
        CountingTransactions transactionManager() {
            return new CountingTransactions();
        }

        @Bean
        LazySelfProxy lazySelfProxy() {
            return new LazySelfProxy();
        }

        @Bean
        DirectReceiver directReceiver() {
            return new DirectReceiver();
        }
    }

    static class CountingTransactions implements PlatformTransactionManager {
        final AtomicInteger begins = new AtomicInteger();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            begins.incrementAndGet();
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {}

        @Override
        public void rollback(TransactionStatus status) {}
    }

    public static class LazySelfProxy {
        @Autowired
        @Lazy
        LazySelfProxy self;

        public void caller() {
            self.callee();
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void callee() {}
    }

    public static class DirectReceiver {
        public void caller() {
            this.callee();
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void callee() {}
    }

    static class KnownProxyReceivers {
        @Autowired
        @Lazy
        KnownProxyReceivers self;

        @Autowired
        ObjectProvider<KnownProxyReceivers> provider;

        void injected() {
            self.callee();
        }

        void localAliases() {
            KnownProxyReceivers alias = self;
            KnownProxyReceivers secondAlias = alias;
            secondAlias.withArguments(42L, "order", 1.5);
        }

        void providerLookups() {
            ObjectProvider<KnownProxyReceivers> alias = provider;
            alias.getObject().callee();
            provider.getIfAvailable().callee();
            provider.getIfUnique().callee();
        }

        void currentProxy() {
            KnownProxyReceivers alias = (KnownProxyReceivers) AopContext.currentProxy();
            alias.callee();
        }

        Runnable lambda() {
            return () -> self.callee();
        }

        Runnable providerLambda() {
            return () -> provider.getObject().callee();
        }

        Runnable reference() {
            KnownProxyReceivers alias = self;
            return alias::callee;
        }

        Runnable providerReference() {
            return provider.getObject()::callee;
        }

        Runnable currentProxyReference() {
            return ((KnownProxyReceivers) AopContext.currentProxy())::callee;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void callee() {}

        @Async
        public void withArguments(long id, String label, double quantity) {}
    }

    static class UncertainReceivers {
        @Autowired
        @Lazy
        UncertainReceivers self;

        void direct() {
            this.callee();
        }

        void directAlias() {
            UncertainReceivers alias = this;
            alias.callee();
        }

        void unknownParameter(UncertainReceivers other) {
            other.callee();
        }

        void helperResult() {
            identity(self).callee();
        }

        private static UncertainReceivers identity(UncertainReceivers value) {
            return value;
        }

        void branch(boolean call) {
            if (call) {
                self.callee();
            }
        }

        void reassignedAlias() {
            UncertainReceivers alias = self;
            alias = this;
            alias.callee();
        }

        Runnable directLambda() {
            return () -> this.callee();
        }

        Runnable capturedAliasLambda() {
            UncertainReceivers alias = self;
            return () -> alias.callee();
        }

        Runnable directReference() {
            return this::callee;
        }

        Consumer<UncertainReceivers> unboundReference() {
            return UncertainReceivers::callee;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void callee() {}
    }

    static class MixedCalls {
        @Autowired
        @Lazy
        MixedCalls self;

        String caller() {
            return self.callee() + this.callee();
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public String callee() {
            return "order";
        }
    }

    static class MixedReferences {
        @Autowired
        @Lazy
        MixedReferences self;

        List<Runnable> caller() {
            return List.of(self::callee, this::callee);
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void callee() {}
    }

    static class UninjectedSelfReference {
        @Lazy
        UninjectedSelfReference self;

        void caller() {
            self.callee();
        }

        @Transactional
        public void callee() {}
    }

    static class WritableSelfReference {
        @Autowired
        @Lazy
        WritableSelfReference self;

        void caller() {
            self.callee();
        }

        void replace() {
            self = this;
        }

        @Transactional
        public void callee() {}
    }

    static class NonLazySelfReference {
        @Autowired
        @Lazy(false)
        NonLazySelfReference self;

        void caller() {
            self.callee();
        }

        @Transactional
        public void callee() {}
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
