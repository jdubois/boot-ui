package io.github.jdubois.bootui.autoconfigure.journal;

import io.github.jdubois.bootui.engine.journal.RuntimeEventPublisher;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Installs the runtime journal ({@code docs/PLAN-v2.md} §5.2) on every recorder as Spring creates it, on both web
 * stacks, so a recorder publishes from its first event. The journal is looked up only when a recorder appears, and a
 * recorder created while no journal exists, as in a test slice, keeps publishing nothing.
 */
public final class RuntimeEventPublisherInstaller implements BeanPostProcessor {

    private final ObjectProvider<RuntimeJournal> journal;

    public RuntimeEventPublisherInstaller(ObjectProvider<RuntimeJournal> journal) {
        this.journal = journal;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof RuntimeEventPublisher publisher) {
            RuntimeJournal available = journal.getIfAvailable();
            if (available != null) {
                publisher.setRuntimeEventSink(available);
            }
        }
        return bean;
    }
}
