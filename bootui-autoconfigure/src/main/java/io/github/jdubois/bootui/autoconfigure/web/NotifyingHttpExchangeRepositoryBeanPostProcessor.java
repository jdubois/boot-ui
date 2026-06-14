package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.stream.BootUiChangeStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;

/**
 * Wraps a host-provided {@link HttpExchangeRepository} bean with {@link NotifyingHttpExchangeRepository}
 * after initialization, so the HTTP Exchanges panel receives live SSE updates even when the
 * application supplies its own repository instead of BootUI's auto-configured one.
 *
 * <p>This is the {@code HttpExchangeRepository} analogue of {@code SqlTraceDataSourceBeanPostProcessor}:
 * it is the robust equivalent of "winning precedence" over a host bean without replacing it. The
 * change stream is resolved lazily through an {@link ObjectProvider} so this post-processor does not
 * force early creation of unrelated beans.</p>
 *
 * <p>It is careful in two ways:</p>
 *
 * <ul>
 *   <li><b>No double-wrapping.</b> Instances that are already a {@link NotifyingHttpExchangeRepository}
 *       (BootUI's own bean already returns the decorator) are left untouched.</li>
 *   <li><b>Fail-open.</b> If wrapping throws, the original repository is returned unchanged so
 *       HTTP-exchange recording is never compromised. Only {@link VirtualMachineError} is re-thrown.</li>
 * </ul>
 */
public final class NotifyingHttpExchangeRepositoryBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(NotifyingHttpExchangeRepositoryBeanPostProcessor.class);

    private final ObjectProvider<BootUiChangeStream> changeStreamProvider;

    public NotifyingHttpExchangeRepositoryBeanPostProcessor(ObjectProvider<BootUiChangeStream> changeStreamProvider) {
        this.changeStreamProvider = changeStreamProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof HttpExchangeRepository repository) || bean instanceof NotifyingHttpExchangeRepository) {
            return bean;
        }
        BootUiChangeStream changeStream = changeStreamProvider.getIfAvailable();
        if (changeStream == null) {
            return bean;
        }
        try {
            return new NotifyingHttpExchangeRepository(repository, changeStream);
        } catch (Throwable ex) {
            if (ex instanceof VirtualMachineError vme) {
                throw vme;
            }
            log.warn(
                    "BootUI could not enable live HTTP-exchange streaming for repository bean '{}'; "
                            + "leaving it unwrapped",
                    beanName,
                    ex);
            return bean;
        }
    }
}
