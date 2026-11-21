package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import io.github.jdubois.bootui.engine.journal.DurableJournalCapture;
import io.github.jdubois.bootui.engine.journal.DurableJournalCaptureSettings;
import io.github.jdubois.bootui.engine.journal.DurableJournalDetailProvider;
import io.github.jdubois.bootui.engine.journal.DurableJournalRunMetadata;
import io.github.jdubois.bootui.engine.journal.DurableJournalRuntime;
import io.github.jdubois.bootui.engine.journal.DurableJournalSettings;
import io.github.jdubois.bootui.engine.journal.DurableJournalStore;
import io.github.jdubois.bootui.engine.journal.ExceptionStoreDurableJournalDetailProvider;
import io.github.jdubois.bootui.engine.journal.HttpPayloadDurableJournalDetailProvider;
import io.github.jdubois.bootui.engine.journal.JournalActivityFeed;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.RunHistory;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;

/** Starts configured durable capture eagerly, before any Live Activity controller is opened. */
public final class BootUiDurableJournalStartup {

    public BootUiDurableJournalStartup(
            BootUiProperties properties,
            RuntimeJournal journal,
            JournalAggregates aggregates,
            DurableJournalSettings settings,
            DurableJournalRuntime runtime,
            ObjectProvider<DurableJournalStore> stores,
            ObjectProvider<ExceptionStore> exceptions,
            InsightsStack stack) {
        if (!settings.enabled()) {
            return;
        }
        if (!journal.settings().enabled() || !journal.isOpen()) {
            LogFactory.getLog(BootUiDurableJournalStartup.class)
                    .warn("Live Activity persistence is enabled, but the runtime journal is unavailable or disabled;"
                            + " the live in-memory endpoint remains active.");
            return;
        }
        try {
            DurableJournalStore store = stores.getObject();
            store.verifySchema();
            DurableJournalCapture capture = DurableJournalCapture.start(
                    journal,
                    settings,
                    store,
                    new JournalActivityFeed(
                            properties.getActivity().getRequestSlowThresholdMs(),
                            properties.getActivity().getNPlusOneThreshold(),
                            aggregates.declaredRoutes()),
                    DurableJournalCaptureSettings.of(settings),
                    detailProviders(exceptions),
                    phase -> DurableJournalRunMetadata.snapshot(
                            phase,
                            journal,
                            settings.identity(),
                            stack,
                            Map.of(),
                            null,
                            phase == DurableJournalRunMetadata.Phase.END
                                    ? RunHistory.shared().summaries().stream()
                                            .filter(summary -> summary.header()
                                                    .runId()
                                                    .equals(journal.run().id()))
                                            .findFirst()
                                            .orElse(null)
                                    : null));
            if (!journal.commitWhileOpen(() -> runtime.activate(store, capture))) {
                capture.close();
            }
        } catch (RuntimeException ex) {
            LogFactory.getLog(BootUiDurableJournalStartup.class)
                    .warn(
                            "Live Activity durable journal capture could not start; the live in-memory endpoint"
                                    + " remains active.",
                            ex);
        }
    }

    private static List<DurableJournalDetailProvider> detailProviders(ObjectProvider<ExceptionStore> exceptions) {
        List<DurableJournalDetailProvider> providers = new ArrayList<>();
        providers.add(new HttpPayloadDurableJournalDetailProvider());
        ExceptionStore store = exceptions.getIfAvailable();
        if (store != null) {
            providers.add(new ExceptionStoreDurableJournalDetailProvider(store));
        }
        return List.copyOf(providers);
    }
}
