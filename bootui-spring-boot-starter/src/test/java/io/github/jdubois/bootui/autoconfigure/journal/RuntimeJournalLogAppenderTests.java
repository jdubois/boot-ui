package io.github.jdubois.bootui.autoconfigure.journal;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.LogPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RuntimeJournalLogAppenderTests {

    private final List<RuntimeEvent> published = new CopyOnWriteArrayList<>();

    private RuntimeJournalLogAppender appender;

    @AfterEach
    void uninstall() {
        if (appender != null) {
            appender.destroy();
        }
    }

    @Test
    void recordsWarningsAndErrorsWithTheirTemplateAndRequestButNeverTheirArguments() {
        appender = RuntimeJournalLogAppender.install(published::add);
        Logger logger = LoggerFactory.getLogger("com.example.orders.OrderService");

        try (BootUiCorrelation.Scope ignored =
                BootUiCorrelation.open(CorrelationContext.forRequest("0123456789abcdef"))) {
            logger.info("Order {} placed", 42);
            logger.warn("Payment for order {} retried with token {}", 42, "secret-token");
            logger.error("Order {} failed", 43, new IllegalStateException("boom"));
        }

        assertThat(published).hasSize(2).allSatisfy(event -> {
            assertThat(event.source()).isEqualTo(JournalSource.LOG);
            assertThat(event.requestId()).isEqualTo("0123456789abcdef");
            assertThat(event.thread()).isEqualTo(Thread.currentThread().getName());
        });
        assertThat(published.get(0).payload())
                .isEqualTo(new LogPayload(
                        "com.example.orders.OrderService", "WARN", "Payment for order {} retried with token {}", null));
        assertThat(published.get(0).failedOrSlow()).isFalse();
        assertThat(published.get(1).payload())
                .isEqualTo(new LogPayload(
                        "com.example.orders.OrderService",
                        "ERROR",
                        "Order {} failed",
                        "java.lang.IllegalStateException"));
        assertThat(published.get(1).failedOrSlow()).isTrue();
    }

    @Test
    void skipsBootUisOwnLoggersAndDetachesOnDestroy() {
        appender = RuntimeJournalLogAppender.install(published::add);

        LoggerFactory.getLogger("io.github.jdubois.bootui.engine.Something").warn("BootUI internal warning");
        appender.destroy();
        LoggerFactory.getLogger("com.example.Late").warn("After the context closed");
        appender = null;

        assertThat(published).isEmpty();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        assertThat(context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
                        .getAppender(RuntimeJournalLogAppender.APPENDER_NAME))
                .isNull();
    }

    @Test
    void installingAgainReplacesThePreviousAppender() {
        List<RuntimeEvent> previous = new CopyOnWriteArrayList<>();
        RuntimeJournalLogAppender first = RuntimeJournalLogAppender.install(previous::add);
        appender = RuntimeJournalLogAppender.install(published::add);

        LoggerFactory.getLogger("com.example.Restarted").warn("After a DevTools restart");

        assertThat(first.isStarted()).isFalse();
        assertThat(previous).isEmpty();
        assertThat(published).hasSize(1);
    }
}
