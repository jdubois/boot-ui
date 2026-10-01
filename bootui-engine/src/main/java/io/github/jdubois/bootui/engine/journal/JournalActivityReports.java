package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.engine.journal.JournalActivityFeed.Feed;
import io.github.jdubois.bootui.engine.journal.JournalActivityFeed.Filter;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Live Activity's report served from the runtime journal ({@code docs/PLAN-v2.md} §5.3), shared by every adapter. Rows
 * of a disabled panel are left out exactly as the 1.x feed leaves them out, so per-panel policy holds.
 */
public final class JournalActivityReports {

    /** The label of the journal in a report's sources. */
    public static final String SOURCE = "Runtime journal";

    /** The entries one report returns when the caller asks for none in particular. */
    public static final int DEFAULT_LIMIT = 200;

    /** The most entries one report returns, whatever the caller asks for. */
    public static final int MAX_LIMIT = 5_000;

    static final String DISABLED =
            "Live Activity reads the runtime journal, which is disabled (bootui.runtime-journal.enabled=false).";

    /** The warning a buffer-served report carries when a caller asked for a journal-only filter. */
    public static final String JOURNAL_FILTERS_IGNORED = "The route, run, request id, and no-request filters apply to"
            + " the feed rendered from the runtime journal (source=journal); this feed comes from the panel buffers.";

    private final RuntimeJournal journal;
    private final JournalActivityFeed feed;
    private final Predicate<String> panelEnabled;

    /**
     * @param journal the journal, or {@code null} when the adapter created none
     * @param declaredRoutes the application's declared routes, or {@code null}
     * @param panelEnabled whether a panel, by its {@link BootUiPanels} id, is enabled
     */
    public JournalActivityReports(
            RuntimeJournal journal,
            long requestSlowThresholdMs,
            int nPlusOneThreshold,
            Supplier<RouteTemplateResolver> declaredRoutes,
            Predicate<String> panelEnabled) {
        this.journal = journal;
        this.feed = new JournalActivityFeed(requestSlowThresholdMs, nPlusOneThreshold, declaredRoutes);
        this.panelEnabled = panelEnabled == null ? panel -> true : panelEnabled;
    }

    /**
     * The feed and KPIs over the journal's retained events.
     *
     * @param limit the entries to return; {@code 0} or less for {@link #DEFAULT_LIMIT}, never more than
     *     {@link #MAX_LIMIT}
     * @param healthStatus the application's health status, or {@code null}
     */
    public LiveActivityReport report(Filter filter, int limit, String healthStatus) {
        if (journal == null || !journal.settings().enabled()) {
            return new LiveActivityReport(
                    false, List.of(), Map.of(), feed.kpis(List.of(), healthStatus), List.of(), List.of(DISABLED));
        }
        List<JournalEntry> visible = new ArrayList<>();
        for (JournalEntry entry : journal.entries()) {
            if (panelEnabled(panelOf(entry.event()))) {
                visible.add(entry);
            }
        }
        int cap = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        Feed rendered = feed.render(visible, journal::eventId, journal.run().id(), filter, cap);
        return new LiveActivityReport(
                true,
                rendered.entries(),
                rendered.typeCounts(),
                feed.kpis(visible, healthStatus),
                List.of(SOURCE),
                List.of());
    }

    /** Whether any journal-only filter is set, which a buffer-served report cannot honor. */
    public static boolean hasJournalOnlyFilter(Filter filter) {
        return filter != null
                && (filter.route() != null
                        || filter.runId() != null
                        || filter.requestId() != null
                        || filter.noRequest());
    }

    private boolean panelEnabled(String panel) {
        try {
            return panel == null || panelEnabled.test(panel);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** The panel that owns an event's rows, whose policy the feed honors. */
    static String panelOf(RuntimeEvent event) {
        return switch (event.source()) {
            case HTTP -> BootUiPanels.HTTP_EXCHANGES;
            case SQL -> BootUiPanels.SQL_TRACE;
            case REST_CLIENT -> BootUiPanels.REST_CLIENT_TRACE;
            case EXCEPTION -> BootUiPanels.EXCEPTIONS;
            case SECURITY -> BootUiPanels.SECURITY_LOGS;
            case CACHE -> BootUiPanels.CACHE;
            case SCHEDULED -> BootUiPanels.SCHEDULED;
            case TRANSACTION -> BootUiPanels.TRANSACTIONS;
            case LOG -> BootUiPanels.LOG_TAIL;
            case MESSAGING ->
                event.payload() instanceof MessagingPayload message
                        ? switch (String.valueOf(message.broker())) {
                            case "jms" -> BootUiPanels.JMS;
                            case "rabbitmq" -> BootUiPanels.RABBITMQ;
                            default -> BootUiPanels.KAFKA;
                        }
                        : BootUiPanels.KAFKA;
            default -> null;
        };
    }
}
