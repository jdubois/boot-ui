package io.github.jdubois.bootui.engine.loggers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.dto.LoggerDto;
import io.github.jdubois.bootui.core.dto.LoggersReport;
import io.github.jdubois.bootui.spi.LoggerProvider;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class LoggersServiceTests {

    private static final Predicate<String> SHOW_ALL = name -> true;

    private static final Predicate<String> BLOCK_NONE = name -> false;

    @Test
    void reportIsEmptyWhenProviderIsNull() {
        LoggersService service = new LoggersService(null, SHOW_ALL, BLOCK_NONE);

        LoggersReport report = service.report(null, null, null);

        assertThat(report.availableLevels()).isEmpty();
        assertThat(report.loggers()).isEmpty();
    }

    @Test
    void reportIsEmptyWhenProviderUnavailable() {
        FakeLoggerProvider provider = new FakeLoggerProvider();
        provider.available = false;
        provider.report = new LoggersReport(List.of("INFO"), List.of(new LoggerDto("com.example", "INFO", "INFO")));
        LoggersService service = new LoggersService(provider, SHOW_ALL, BLOCK_NONE);

        LoggersReport report = service.report(null, null, null);

        assertThat(report.availableLevels()).isEmpty();
        assertThat(report.loggers()).isEmpty();
    }

    @Test
    void reportFiltersHiddenLoggersSortsByNameAndPages() {
        FakeLoggerProvider provider = new FakeLoggerProvider();
        provider.report = new LoggersReport(
                List.of("INFO", "DEBUG"),
                List.of(
                        new LoggerDto("com.example.Beta", "INFO", "INFO"),
                        new LoggerDto("com.example.Alpha", "DEBUG", "DEBUG"),
                        new LoggerDto("hidden.Secret", "WARN", "WARN"),
                        new LoggerDto("org.other.Thing", "WARN", "WARN")));
        // Read visibility hides anything under "hidden.".
        Predicate<String> readVisible = name -> !name.startsWith("hidden.");
        LoggersService service = new LoggersService(provider, readVisible, BLOCK_NONE);

        LoggersReport report = service.report("com.example", 1, 1);

        // availableLevels passed through verbatim.
        assertThat(report.availableLevels()).containsExactly("INFO", "DEBUG");
        // Hidden logger removed; remaining sorted by name; query matched 2 (Alpha, Beta), offset 1 -> Beta.
        assertThat(report.loggers()).extracting(LoggerDto::name).containsExactly("com.example.Beta");
        assertThat(report.page().total()).isEqualTo(3); // visible loggers (hidden excluded)
        assertThat(report.page().matched()).isEqualTo(2); // matching the query
        assertThat(report.page().offset()).isEqualTo(1);
        assertThat(report.page().returned()).isEqualTo(1);
    }

    @Test
    void setLevelDelegatesToProviderAndPassesLevelThrough() {
        FakeLoggerProvider provider = new FakeLoggerProvider();
        LoggersService service = new LoggersService(provider, SHOW_ALL, BLOCK_NONE);

        LoggerDto result = service.setLevel("com.example", "DEBUG");

        assertThat(provider.setCalls).isEqualTo(1);
        assertThat(provider.lastName).isEqualTo("com.example");
        assertThat(provider.lastLevel).isEqualTo("DEBUG");
        assertThat(result.name()).isEqualTo("com.example");
        assertThat(result.configuredLevel()).isEqualTo("DEBUG");
    }

    @Test
    void setLevelPassesBlankLevelThroughForTheProviderToInterpretAsReset() {
        FakeLoggerProvider provider = new FakeLoggerProvider();
        LoggersService service = new LoggersService(provider, SHOW_ALL, BLOCK_NONE);

        service.setLevel("com.example", "");

        // The engine does not interpret reset semantics; the level string reaches the provider unchanged.
        assertThat(provider.setCalls).isEqualTo(1);
        assertThat(provider.lastLevel).isEqualTo("");
    }

    @Test
    void setLevelRejectsWriteBlockedLoggerBeforeTouchingProvider() {
        FakeLoggerProvider provider = new FakeLoggerProvider();
        Predicate<String> writeBlocked = name -> name.startsWith("io.github.jdubois.bootui.");
        LoggersService service = new LoggersService(provider, SHOW_ALL, writeBlocked);

        assertThatThrownBy(() -> service.setLevel("io.github.jdubois.bootui.autoconfigure.web.X", "WARN"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(provider.setCalls).isZero();
    }

    @Test
    void writeGuardIsIndependentOfReadVisibility() {
        // B3: even when read-visibility shows everything (the operator set exclude-self=false), the write
        // guard must still block BootUI's own loggers, so a read toggle can never unlock a write.
        FakeLoggerProvider provider = new FakeLoggerProvider();
        Predicate<String> readVisibleShowsEverything = name -> true;
        Predicate<String> writeBlocked = name -> name.startsWith("io.github.jdubois.bootui.");
        LoggersService service = new LoggersService(provider, readVisibleShowsEverything, writeBlocked);

        assertThatThrownBy(() -> service.setLevel("io.github.jdubois.bootui.autoconfigure.web.X", "WARN"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(provider.setCalls).isZero();

        // An ordinary application logger is still writable.
        service.setLevel("com.example.OrderService", "WARN");
        assertThat(provider.lastName).isEqualTo("com.example.OrderService");
    }

    @Test
    void setLevelThrowsIllegalStateWhenProviderIsNull() {
        LoggersService service = new LoggersService(null, SHOW_ALL, BLOCK_NONE);

        assertThatThrownBy(() -> service.setLevel("com.example", "DEBUG")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setLevelThrowsIllegalStateWhenProviderUnavailable() {
        FakeLoggerProvider provider = new FakeLoggerProvider();
        provider.available = false;
        LoggersService service = new LoggersService(provider, SHOW_ALL, BLOCK_NONE);

        assertThatThrownBy(() -> service.setLevel("com.example", "DEBUG")).isInstanceOf(IllegalStateException.class);
        assertThat(provider.setCalls).isZero();
    }

    private static final class FakeLoggerProvider implements LoggerProvider {

        private boolean available = true;

        private LoggersReport report = new LoggersReport(List.of(), List.of());

        private int setCalls;

        private String lastName;

        private String lastLevel;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public LoggersReport rawLoggers() {
            return report;
        }

        @Override
        public LoggerDto setLevel(String name, String level) {
            this.setCalls++;
            this.lastName = name;
            this.lastLevel = level;
            return new LoggerDto(name, level, level);
        }
    }
}
