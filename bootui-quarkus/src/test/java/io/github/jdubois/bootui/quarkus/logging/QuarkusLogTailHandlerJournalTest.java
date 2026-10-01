package io.github.jdubois.bootui.quarkus.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.LogPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

class QuarkusLogTailHandlerJournalTest {

    @Test
    void publishesWarningsAndErrorsWithTheirTemplateAndRequestButNeverTheirParameters() {
        List<RuntimeEvent> published = new ArrayList<>();
        QuarkusLogTailHandler handler = new QuarkusLogTailHandler(
                new LogTailBuffer(), new InternalPackageMatcher(List.of("io.github.jdubois.bootui")), published::add);

        try (BootUiCorrelation.Scope ignored =
                BootUiCorrelation.open(CorrelationContext.forRequest("0123456789abcdef"))) {
            handler.publish(record(Level.INFO, "com.example.Orders", "Order {0} placed", null));
            handler.publish(record(Level.WARNING, "com.example.Orders", "Payment {0} retried", null));
            handler.publish(
                    record(Level.SEVERE, "com.example.Orders", "Order {0} failed", new IllegalStateException()));
            handler.publish(record(Level.SEVERE, "io.github.jdubois.bootui.engine.X", "internal", null));
        }

        assertThat(published).hasSize(2).allSatisfy(event -> {
            assertThat(event.source()).isEqualTo(JournalSource.LOG);
            assertThat(event.requestId()).isEqualTo("0123456789abcdef");
        });
        assertThat(published.get(0).payload())
                .isEqualTo(new LogPayload("com.example.Orders", "WARN", "Payment {0} retried", null));
        assertThat(published.get(1).payload())
                .isEqualTo(new LogPayload(
                        "com.example.Orders", "ERROR", "Order {0} failed", "java.lang.IllegalStateException"));
        assertThat(published.get(1).failedOrSlow()).isTrue();
    }

    private static LogRecord record(Level level, String logger, String message, Throwable thrown) {
        LogRecord record = new LogRecord(level, message);
        record.setLoggerName(logger);
        record.setParameters(new Object[] {"secret-42"});
        record.setThrown(thrown);
        return record;
    }
}
