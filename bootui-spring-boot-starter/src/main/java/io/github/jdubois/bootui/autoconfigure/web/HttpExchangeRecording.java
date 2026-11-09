package io.github.jdubois.bootui.autoconfigure.web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.actuate.web.exchanges.Include;
import org.springframework.boot.actuate.web.exchanges.RecordableHttpResponse;
import org.springframework.http.HttpHeaders;

/** Observation-only recording shared by the servlet and reactive Actuator bindings. */
public final class HttpExchangeRecording {

    private static final Logger LOG = LoggerFactory.getLogger(HttpExchangeRecording.class);

    private final HttpExchangeRepository repository;
    private final Set<Include> includes;

    public HttpExchangeRecording(HttpExchangeRepository repository, Set<Include> includes) {
        this.repository = repository;
        this.includes = Set.copyOf(includes);
    }

    public void finish(
            HttpExchange.Started started,
            RecordableHttpResponse response,
            Supplier<java.security.Principal> principal,
            Supplier<String> sessionId) {
        try {
            repository.add(started.finish(response, principal, sessionId, includes));
        } catch (RuntimeException failure) {
            logFailure(failure);
        }
    }

    public static Map<String, List<String>> headers(
            Iterable<String> names, Function<String, ? extends Collection<String>> values) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : names) {
            if (name != null) {
                headers.put(name, new ArrayList<>(values.apply(name)));
            }
        }
        return headers;
    }

    @SuppressWarnings("removal")
    public static Map<String, List<String>> headers(HttpHeaders source) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        // The deprecated raw view is needed: normalized views reject null names before callers can filter them.
        source.asMultiValueMap().forEach((name, values) -> {
            if (name != null && names.add(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, new ArrayList<>(values));
            }
        });
        return headers;
    }

    public static void logFailure(Exception failure) {
        // Metadata and exception messages can contain credentials, including in an application's repository.
        LOG.warn(
                "BootUI skipped an HTTP exchange capture ({})",
                failure.getClass().getSimpleName());
    }
}
