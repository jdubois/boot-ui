package org.acme.nativeconsole;

import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.ToLongFunction;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import tools.jackson.databind.ObjectMapper;

@ApplicationScoped
public class DormancyProbe {

    private static final String THREAD_WITNESS = "native-acceptance-thread-witness";
    private final CountDownLatch stop = new CountDownLatch(1);
    private Thread witness;

    @Inject
    BeanManager beans;

    @Inject
    ObjectMapper mapper;

    // Keep included bean metadata reachable, but never Instance.get(): a probe must not manufacture a
    // recorder and then mistake its newly empty buffer for dormant startup wiring.
    @Inject
    Instance<HttpExchangeBuffer> http;

    @Inject
    Instance<LogTailBuffer> logs;

    @Inject
    Instance<SqlTraceRecorder> sql;

    @Inject
    Instance<ExceptionStore> exceptions;

    @Inject
    Instance<TelemetryStore> telemetry;

    @Inject
    Instance<RuntimeJournal> journal;

    void start(@Observes StartupEvent event) {
        Logger.getLogger(WitnessService.LOGGER_NAME);
        witness = new Thread(
                () -> {
                    try {
                        stop.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                },
                THREAD_WITNESS);
        witness.setDaemon(true);
        witness.start();
    }

    void stop(@Observes @Priority(Integer.MAX_VALUE) ShutdownEvent event) {
        try {
            String output = System.getProperty("native.acceptance.shutdown-file");
            if (output != null) {
                Files.writeString(Path.of(output), mapper.writeValueAsString(snapshot()) + "\n");
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot record the native shutdown dormancy witness", failure);
        } finally {
            stop.countDown();
        }
    }

    public Snapshot snapshot() {
        List<String> threads = Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .sorted()
                .toList();
        if (!threads.contains(THREAD_WITNESS)) {
            throw new IllegalStateException("Native thread enumeration did not observe the live host witness");
        }
        List<String> loggerNames = Collections.list(LogManager.getLogManager().getLoggerNames());
        if (!loggerNames.contains(WitnessService.LOGGER_NAME)) {
            throw new IllegalStateException("Native JUL enumeration did not observe the host logger witness");
        }
        List<String> handlers = new ArrayList<>();
        for (String name : loggerNames) {
            Logger logger = LogManager.getLogManager().getLogger(name);
            if (logger != null) {
                for (Handler handler : logger.getHandlers()) {
                    String type = handler.getClass().getName();
                    if (type.startsWith("io.github.jdubois.bootui.")) {
                        handlers.add(type);
                    }
                }
            }
        }
        return new Snapshot(
                "native-acceptance-v1",
                Map.of(
                        "http", read(http, buffer -> buffer.snapshot().size()),
                        "logs", read(logs, buffer -> buffer.recent().size()),
                        "sql", read(sql, SqlTraceRecorder::totalCaptured),
                        "exceptions", read(exceptions, ExceptionStore::totalExceptions),
                        "telemetry", read(telemetry, TelemetryStore::retainedTraceCount),
                        "journal", read(journal, RuntimeJournal::lastSequence)),
                read(logs, LogTailBuffer::subscriberCount),
                threads.stream().filter(name -> name.startsWith("bootui-")).toList(),
                List.copyOf(handlers),
                HostSpanProcessor.ended(),
                SlowConfigSource.counters());
    }

    private <T> Counter read(Instance<T> instance, ToLongFunction<T> counter) {
        if (instance.isUnsatisfied()) {
            return new Counter("absent", null);
        }
        if (instance.isAmbiguous()) {
            throw new IllegalStateException("Ambiguous native acceptance recorder");
        }
        Bean<T> bean = instance.getHandle().getBean();
        Context context = beans.getContext(bean.getScope());
        T existing = context.get(bean);
        return existing == null
                ? new Counter("uninstantiated", null)
                : new Counter("instantiated", counter.applyAsLong(existing));
    }

    public record Counter(String state, Long value) {}

    public record Snapshot(
            String protocol,
            Map<String, Counter> counters,
            Counter logSubscriptions,
            List<String> ownedThreads,
            List<String> installedHandlers,
            long hostSpans,
            Map<String, Integer> slowConfig) {}
}
