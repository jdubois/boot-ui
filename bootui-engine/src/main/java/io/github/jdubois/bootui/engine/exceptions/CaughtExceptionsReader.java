package io.github.jdubois.bootui.engine.exceptions;

import io.github.jdubois.bootui.core.dto.CaughtExceptionsReport;
import io.github.jdubois.bootui.core.dto.CaughtExceptionsSummaryDto;
import io.github.jdubois.bootui.engine.javaagent.AgentCaughtExceptions;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RunningHandoffs;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Reads the Exceptions panel's <b>Caught in application code</b> section ({@code docs/PLAN-v2.md} M5-6) for every
 * adapter: drains what the agent recorded, waits briefly for the journal to process it, and resolves each caught
 * exception's outcome ({@link CaughtOutcomes}). A read starts no scan and no network call; its result is cached until
 * the journal moves, a clear, a change of panel visibility, or the next second, since settling depends on time.
 */
public final class CaughtExceptionsReader {

    static final String UNAVAILABLE =
            "The BootUI agent's caught-exceptions sensor does not record in this run: attach the agent and claim the"
                    + " caught-exceptions sensor (bootui.agent.sensors) to see what application code caught.";

    /** How long a read waits for the journal to process what it drained. */
    static final Duration DRAIN_WAIT = Duration.ofMillis(500);

    private final RuntimeJournal journal;
    private final Supplier<AgentCaughtExceptions> sensor;
    private final LogCoverage logs;
    private final RunningHandoffs handoffs;
    private final LongSupplier clock;

    private Key cachedKey;
    private CaughtExceptionsReport cached;

    public CaughtExceptionsReader(
            RuntimeJournal journal,
            Supplier<AgentCaughtExceptions> sensor,
            LogCoverage logs,
            RunningHandoffs handoffs) {
        this(journal, sensor, logs, handoffs, System::currentTimeMillis);
    }

    CaughtExceptionsReader(
            RuntimeJournal journal,
            Supplier<AgentCaughtExceptions> sensor,
            LogCoverage logs,
            RunningHandoffs handoffs,
            LongSupplier clock) {
        this.journal = journal;
        this.sensor = sensor;
        this.logs = logs == null ? LogCoverage.UNREADABLE : logs;
        this.handoffs = handoffs == null ? RunningHandoffs.shared() : handoffs;
        this.clock = clock;
    }

    /** The section, under the panels {@code panelEnabled} shows. */
    public CaughtExceptionsReport report(Predicate<String> panelEnabled) {
        AgentCaughtExceptions current = sensor.get();
        if (journal == null || current == null || !current.routing()) {
            return CaughtExceptionsReport.unavailable(UNAVAILABLE);
        }
        boolean drained = drain(current);
        current.sampleLosses();
        boolean httpVisible = panelEnabled.test(BootUiPanels.HTTP_EXCHANGES);
        boolean logRecorded = journal.records(JournalSource.LOG);
        boolean logVisible = logRecorded && panelEnabled.test(BootUiPanels.LOG_TAIL);
        long now = clock.getAsLong();
        Key key = new Key(
                journal.lastSequence(),
                journal.status().clears(),
                current.clearedAtMillis(),
                httpVisible,
                logVisible,
                drained,
                now / 1_000L);
        synchronized (this) {
            if (key.equals(cachedKey)) {
                return cached;
            }
        }
        List<String> limitations = new ArrayList<>();
        if (!httpVisible) {
            limitations.add("HTTP Exchanges is hidden, so rows name no route or request.");
        }
        if (!logRecorded) {
            limitations.add("The runtime journal does not record logs, so no caught exception is known not logged.");
        } else if (!logVisible) {
            limitations.add(
                    "Log Tail is hidden, so logs are not consulted and no caught exception is known not logged.");
        }
        List<String> bypassing = logs.bypassingLoggers();
        if (!bypassing.isEmpty()) {
            limitations.add("These loggers do not pass WARN logs to the root logger, where BootUI reads them, so no"
                    + " caught exception is known not logged: " + String.join(", ", bypassing) + ".");
        }
        if (!current.executorsRecorded()) {
            limitations.add("The agent's executors sensor does not record handoffs, so work a request handed over"
                    + " cannot be followed and no caught exception is known not logged.");
        }
        if (current.sitesOverLimit() > 0) {
            limitations.add("Some exception handlers were left uninstrumented: the agent instruments at most 16,384"
                    + " handlers per JVM, so what they catch is not seen.");
        }
        if (current.lossUnaccounted()) {
            limitations.add("The attached agent does not count its losses: attach the agent of this BootUI version.");
        }
        if (!journal.records(JournalSource.EXCEPTION)) {
            limitations.add("The runtime journal does not record exceptions, so none is known not rethrown or logged.");
        }
        limitations.add("A caught exception wrapping another (ExecutionException, CompletionException) is unknown: its"
                + " cause may have been logged where it was thrown.");
        limitations.add("A task queued for longer than the 5 s settle window before it starts is not yet followed: what"
                + " it rethrows or logs later is seen then.");
        CaughtOutcomes.Context context = new CaughtOutcomes.Context(
                now,
                drained,
                httpVisible,
                logVisible,
                current.executorsRecorded(),
                current.lossUnaccounted(),
                current.clearedAtMillis(),
                journal.lossHorizonMillis(),
                journal::lostMillis,
                current::lostBetween,
                handoffs.forgottenMillis(),
                handoffs::runningFor,
                (from, to) -> {
                    if (!logRecorded) {
                        return "the runtime journal does not record logs";
                    }
                    if (!logVisible) {
                        return "Log Tail is hidden";
                    }
                    return logs.gap(from, to);
                },
                journal.records(JournalSource.EXCEPTION),
                current.routingSinceMillis());
        CaughtExceptionsReport report = CaughtOutcomes.resolve(journal.entries(), context, limitations);
        synchronized (this) {
            cachedKey = key;
            cached = report;
        }
        return report;
    }

    /** The summary the Exceptions panel's report carries, or {@code null} while the sensor does not record. */
    public CaughtExceptionsSummaryDto summary(Predicate<String> panelEnabled) {
        AgentCaughtExceptions current = sensor.get();
        if (journal == null || current == null || !current.routing()) {
            return null;
        }
        return CaughtExceptionsSummaryDto.of(report(panelEnabled));
    }

    private boolean drain(AgentCaughtExceptions current) {
        try {
            current.drainNow();
            return journal.awaitDrained(DRAIN_WAIT);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private record Key(
            long sequence,
            long clears,
            Long clearedAt,
            boolean httpVisible,
            boolean logVisible,
            boolean drained,
            long second) {}
}
