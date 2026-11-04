package io.github.jdubois.bootui.autoconfigure.journal;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPublisher;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Binds aggregates before Spring exposes the journal to any recorder, then starts its GC and resource producers.
 * This also covers application-supplied journals and aggregates. Both web stacks install the journal on each recorder
 * as it is created, so its first event reaches the aggregates. A recorder created without a journal, as in a test
 * slice, keeps publishing nothing.
 */
public final class RuntimeEventPublisherInstaller implements BeanPostProcessor {

    private final ObjectProvider<RuntimeJournal> journal;
    private final ObjectProvider<JournalAggregates> aggregates;
    private final ObjectProvider<BootUiProperties> properties;

    public RuntimeEventPublisherInstaller(
            ObjectProvider<RuntimeJournal> journal,
            ObjectProvider<JournalAggregates> aggregates,
            ObjectProvider<BootUiProperties> properties) {
        this.journal = journal;
        this.aggregates = aggregates;
        this.properties = properties;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof RuntimeJournal created) {
            try {
                JournalAggregates listener = aggregates.getObject();
                created.addListener(listener);
                created.startGcSource();
                created.startResourceSampler(
                        properties.getObject().getResources().toSettings(), listener.resourceTrack());
            } catch (RuntimeException | Error failure) {
                created.close();
                throw failure;
            }
        }
        if (bean instanceof RuntimeEventPublisher publisher) {
            RuntimeJournal available = journal.getIfAvailable();
            if (available != null) {
                publisher.setRuntimeEventSink(available);
            }
        }
        return bean;
    }
}
