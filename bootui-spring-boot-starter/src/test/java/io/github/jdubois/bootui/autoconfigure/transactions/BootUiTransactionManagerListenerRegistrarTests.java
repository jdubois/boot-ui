package io.github.jdubois.bootui.autoconfigure.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

class BootUiTransactionManagerListenerRegistrarTests {

    @Test
    void registersListenerOnUserDefinedManager() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        TransactionExecutionListener listener = mock(TransactionExecutionListener.class);

        registrar(manager, listener).afterSingletonsInstantiated();

        assertThat(manager.getTransactionExecutionListeners()).containsExactly(listener);
    }

    @Test
    void doesNotDuplicateListenerAlreadyAppliedBySpringBoot() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        TransactionExecutionListener listener = mock(TransactionExecutionListener.class);
        manager.addListener(listener);

        registrar(manager, listener).afterSingletonsInstantiated();

        assertThat(manager.getTransactionExecutionListeners()).containsExactly(listener);
    }

    @SuppressWarnings("unchecked")
    private static BootUiTransactionManagerListenerRegistrar registrar(
            ConfigurableTransactionManager manager, TransactionExecutionListener listener) {
        ObjectProvider<ConfigurableTransactionManager> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(manager));
        return new BootUiTransactionManagerListenerRegistrar(provider, listener);
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

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
