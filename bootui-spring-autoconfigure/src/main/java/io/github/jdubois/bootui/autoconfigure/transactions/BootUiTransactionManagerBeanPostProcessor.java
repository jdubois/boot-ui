package io.github.jdubois.bootui.autoconfigure.transactions;

import io.github.jdubois.bootui.engine.transactions.TransactionRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Registers {@link BootUiTransactionExecutionListener} against every {@link
 * ConfigurableTransactionManager} bean after initialization, so the Transactions panel observes
 * every {@code @Transactional} boundary without replacing or wrapping the application's own
 * {@link PlatformTransactionManager} beans.
 *
 * <p>{@code ConfigurableTransactionManager} (Spring Framework 6.1+, implemented by {@code
 * AbstractPlatformTransactionManager} and therefore essentially every built-in manager) is the
 * public seam {@code addListener} lives on; a manager that does not implement it (a custom, minimal
 * {@code PlatformTransactionManager}) is simply left unobserved rather than causing an error. The
 * recorder is resolved lazily through an {@link ObjectProvider} so this post-processor never forces
 * early creation of unrelated beans, and registration is skipped entirely when capture is disabled.
 * It fails open: if registering the listener throws, the manager bean is returned unchanged so the
 * application's transaction management is never compromised.</p>
 */
public final class BootUiTransactionManagerBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(BootUiTransactionManagerBeanPostProcessor.class);

    private final ObjectProvider<TransactionRecorder> recorderProvider;

    public BootUiTransactionManagerBeanPostProcessor(ObjectProvider<TransactionRecorder> recorderProvider) {
        this.recorderProvider = recorderProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof PlatformTransactionManager)
                || !(bean instanceof ConfigurableTransactionManager configurable)) {
            return bean;
        }
        TransactionRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null || !recorder.isEnabled()) {
            return bean;
        }
        try {
            configurable.addListener(new BootUiTransactionExecutionListener(recorder));
        } catch (RuntimeException ex) {
            log.warn(
                    "BootUI could not enable transaction capture for PlatformTransactionManager bean '{}'; leaving it"
                            + " unobserved",
                    beanName,
                    ex);
        }
        return bean;
    }
}
