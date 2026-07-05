package io.github.jdubois.bootui.console.web;

import io.github.jdubois.bootui.console.activity.ConsoleActivityChangeStream;
import io.github.jdubois.bootui.console.activity.ConsoleActivityProfileAssembler;
import io.github.jdubois.bootui.console.activity.ConsoleActivityProperties;
import io.github.jdubois.bootui.console.activity.ConsoleActivityReportAssembler;
import io.github.jdubois.bootui.console.activity.ReactiveActivityStore;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Serves the console's own Live Activity data: the same {@code /bootui/api/activity/**} contract a
 * host application's {@code LiveActivityController} serves, so the shared Vue {@code LiveActivity.vue}
 * panel needs no console-specific code &mdash; only its query parameters differ in effect (every
 * request is always answered from the durable store across every instance the console has received
 * data from; there is no in-memory-only mode to fall back to).
 */
@RestController
@RequestMapping("/bootui/api/activity")
public class ConsoleActivityController {

    private final ReactiveActivityStore store;
    private final ConsoleActivityChangeStream changeStream;
    private final ConsoleActivityProperties properties;

    public ConsoleActivityController(
            ReactiveActivityStore store,
            ConsoleActivityChangeStream changeStream,
            ConsoleActivityProperties properties) {
        this.store = store;
        this.changeStream = changeStream;
        this.properties = properties;
    }

    @GetMapping
    public Mono<LiveActivityReport> activity(
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "since", required = false) Long since,
            @RequestParam(name = "until", required = false) Long until,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "pageSize", required = false, defaultValue = "0") int pageSize) {
        ActivityQuery query = new ActivityQuery("", type, severity, q, since, until, cursor, pageSize);
        return store.queryAllInstances(query)
                .defaultIfEmpty(ActivityPage.EMPTY)
                .map(page -> ConsoleActivityReportAssembler.assemble(page, true, properties.getTableName()));
    }

    @GetMapping("/request/{id}")
    public Mono<RequestProfileDto> request(@PathVariable("id") String id) {
        return ConsoleActivityProfileAssembler.assemble(store, id);
    }

    /**
     * Streams a coalesced {@code update} notification whenever this console receives a new forwarded
     * batch, so the dashboard can refresh live without polling. See {@link ConsoleActivityChangeStream}.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> stream() {
        return changeStream.open();
    }
}
