package io.github.jdubois.bootui.autoconfigure.transactions;

import java.util.Collection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Completes listener registration for user-defined transaction managers after all singleton beans
 * have been created.
 *
 * <p>Spring Boot applies {@link TransactionExecutionListener} beans to transaction managers it
 * auto-configures. User-defined managers make that auto-configuration back off, so this registrar
 * adds the same listener to any remaining configurable manager. Registration is idempotent because
 * Boot-configured managers already contain the exact listener bean.</p>
 */
public final class BootUiTransactionManagerListenerRegistrar implements SmartInitializingSingleton {

    private final ObjectProvider<ConfigurableTransactionManager> transactionManagers;
    private final TransactionExecutionListener listener;

    public BootUiTransactionManagerListenerRegistrar(
            ObjectProvider<ConfigurableTransactionManager> transactionManagers, TransactionExecutionListener listener) {
        this.transactionManagers = transactionManagers;
        this.listener = listener;
    }

    @Override
    public void afterSingletonsInstantiated() {
        transactionManagers.orderedStream().forEach(this::registerIfMissing);
    }

    private void registerIfMissing(ConfigurableTransactionManager transactionManager) {
        Collection<TransactionExecutionListener> listeners = transactionManager.getTransactionExecutionListeners();
        if (!listeners.contains(listener)) {
            transactionManager.addListener(listener);
        }
    }
}
