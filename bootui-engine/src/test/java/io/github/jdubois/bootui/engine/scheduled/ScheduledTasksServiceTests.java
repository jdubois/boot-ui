package io.github.jdubois.bootui.engine.scheduled;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ScheduledReport;
import io.github.jdubois.bootui.core.dto.ScheduledTaskDto;
import io.github.jdubois.bootui.spi.ScheduledTaskProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduledTasksServiceTests {

    @Test
    void reportIsAbsentWhenProviderIsNull() {
        ScheduledTasksService service = new ScheduledTasksService(null);

        ScheduledReport report = service.report();

        assertThat(report.schedulingPresent()).isFalse();
        assertThat(report.total()).isZero();
        assertThat(report.tasks()).isEmpty();
    }

    @Test
    void reportIsAbsentWhenProviderUnavailable() {
        FakeScheduledTaskProvider provider = new FakeScheduledTaskProvider();
        provider.available = false;
        provider.tasks = List.of(new ScheduledTaskDto("com.example.Job", "CRON", "0 0 * * * ?", null, null));
        ScheduledTasksService service = new ScheduledTasksService(provider);

        ScheduledReport report = service.report();

        assertThat(report.schedulingPresent()).isFalse();
        assertThat(report.total()).isZero();
        assertThat(report.tasks()).isEmpty();
    }

    @Test
    void reportIsPresentWithZeroTasksWhenProviderAvailableButEmpty() {
        FakeScheduledTaskProvider provider = new FakeScheduledTaskProvider();
        provider.available = true;
        provider.tasks = List.of();
        ScheduledTasksService service = new ScheduledTasksService(provider);

        ScheduledReport report = service.report();

        assertThat(report.schedulingPresent()).isTrue();
        assertThat(report.total()).isZero();
        assertThat(report.tasks()).isEmpty();
    }

    @Test
    void sortsByRunnableWithNullsLastAndReportsTotal() {
        FakeScheduledTaskProvider provider = new FakeScheduledTaskProvider();
        provider.tasks = List.of(
                new ScheduledTaskDto("com.example.Zeta", "CRON", "0 0 * * * ?", null, null),
                new ScheduledTaskDto(null, "FIXED_RATE", "1000", null, "s"),
                new ScheduledTaskDto("com.example.Alpha", "FIXED_RATE", "5000", null, "s"));
        ScheduledTasksService service = new ScheduledTasksService(provider);

        ScheduledReport report = service.report();

        assertThat(report.schedulingPresent()).isTrue();
        assertThat(report.total()).isEqualTo(3);
        assertThat(report.tasks())
                .extracting(ScheduledTaskDto::runnable)
                .containsExactly("com.example.Alpha", "com.example.Zeta", null);
    }

    private static final class FakeScheduledTaskProvider implements ScheduledTaskProvider {

        private boolean available = true;

        private List<ScheduledTaskDto> tasks = List.of();

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public List<ScheduledTaskDto> tasks() {
            return tasks;
        }
    }
}
