package io.github.jdubois.bootui.engine.scheduled;

import io.github.jdubois.bootui.core.dto.ScheduledReport;
import io.github.jdubois.bootui.core.dto.ScheduledTaskDto;
import io.github.jdubois.bootui.spi.ScheduledTaskProvider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Framework-neutral logic behind the Scheduled Tasks panel, shared by the Spring Boot and Quarkus adapters.
 *
 * <p>It reads the already-mapped, already-self-filtered scheduled tasks from a {@link ScheduledTaskProvider}
 * (optional: {@code null} when the scheduling backend type is absent) and applies BootUI's stable ordering
 * (by runnable, nulls last) plus the {@code schedulingPresent}/{@code total} wrapping on top. The mapping and
 * self-data filtering deliberately stay in the provider (the adapter): the raw runnable description that the
 * self-data filter inspects, and the framework-specific trigger model, are lost once a row becomes a
 * {@link ScheduledTaskDto}, so filtering and mapping there is provably byte-identical to the original Spring
 * controller.</p>
 */
public final class ScheduledTasksService {

    private final ScheduledTaskProvider provider;

    public ScheduledTasksService(ScheduledTaskProvider provider) {
        this.provider = provider;
    }

    /** The sorted scheduled-tasks report; empty with {@code schedulingPresent=false} when no backend is available. */
    public ScheduledReport report() {
        if (provider == null || !provider.available()) {
            return new ScheduledReport(false, 0, List.of());
        }
        List<ScheduledTaskDto> tasks = new ArrayList<>(provider.tasks());
        tasks.sort(Comparator.comparing(ScheduledTaskDto::runnable, Comparator.nullsLast(String::compareTo)));
        return new ScheduledReport(true, tasks.size(), tasks);
    }
}
