package io.github.jdubois.bootui.autoconfigure.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

class BootUiTransactionManagerBeanPostProcessorTests {

    @Test
    void registersListenerOnConfigurableTransactionManagerBeans() {
        TransactionRecorder recorder = recorder(true);
        BootUiTransactionManagerBeanPostProcessor postProcessor =
                new BootUiTransactionManagerBeanPostProcessor(recorderProvider(recorder));

        RecordingTransactionManager manager = new RecordingTransactionManager();
        Object result = postProcessor.postProcessAfterInitialization(manager, "transactionManager");

        assertThat(result).isSameAs(manager);
        assertThat(manager.registeredListeners).hasSize(1);
        assertThat(manager.registeredListeners.get(0)).isInstanceOf(BootUiTransactionExecutionListener.class);
    }

    @Test
    void leavesUnrelatedBeansUnchanged() {
        TransactionRecorder recorder = recorder(true);
        BootUiTransactionManagerBeanPostProcessor postProcessor =
                new BootUiTransactionManagerBeanPostProcessor(recorderProvider(recorder));

        Object bean = new Object();
        Object result = postProcessor.postProcessAfterInitialization(bean, "somethingElse");

        assertThat(result).isSameAs(bean);
    }

    @Test
    void skipsRegistrationWhenRecorderIsAbsent() {
        BootUiTransactionManagerBeanPostProcessor postProcessor =
                new BootUiTransactionManagerBeanPostProcessor(recorderProvider(null));

        RecordingTransactionManager manager = new RecordingTransactionManager();
        postProcessor.postProcessAfterInitialization(manager, "transactionManager");

        assertThat(manager.registeredListeners).isEmpty();
    }

    @Test
    void skipsRegistrationWhenRecorderIsDisabled() {
        TransactionRecorder recorder = recorder(false);
        BootUiTransactionManagerBeanPostProcessor postProcessor =
                new BootUiTransactionManagerBeanPostProcessor(recorderProvider(recorder));

        RecordingTransactionManager manager = new RecordingTransactionManager();
        postProcessor.postProcessAfterInitialization(manager, "transactionManager");

        assertThat(manager.registeredListeners).isEmpty();
    }

    @Test
    void failsOpenWhenAddListenerThrows() {
        TransactionRecorder recorder = recorder(true);
        BootUiTransactionManagerBeanPostProcessor postProcessor =
                new BootUiTransactionManagerBeanPostProcessor(recorderProvider(recorder));

        ConfigurableTransactionManager manager = mock(
                ConfigurableTransactionManager.class,
                org.mockito.Mockito.withSettings().extraInterfaces(PlatformTransactionManager.class));
        doThrow(new IllegalStateException("boom")).when(manager).addListener(org.mockito.ArgumentMatchers.any());

        Object result = postProcessor.postProcessAfterInitialization(manager, "brokenManager");

        assertThat(result).isSameAs(manager);
        verify(manager, times(1)).addListener(org.mockito.ArgumentMatchers.any());
    }

    private static TransactionRecorder recorder(boolean enabled) {
        return new TransactionRecorder(enabled, true, 10, 100, 100, (SqlTraceRecorder) null);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TransactionRecorder> recorderProvider(TransactionRecorder recorder) {
        ObjectProvider<TransactionRecorder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(recorder);
        return provider;
    }

    /** Minimal real {@code ConfigurableTransactionManager} that records registered listeners. */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private final java.util.List<TransactionExecutionListener> registeredListeners = new java.util.ArrayList<>();

        @Override
        public void addListener(TransactionExecutionListener listener) {
            registeredListeners.add(listener);
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {}

        @Override
        protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {}
    }
}
