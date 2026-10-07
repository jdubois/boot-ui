package io.github.jdubois.bootui.webfluxsample.sideeffects;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The thread-locals sensor's seeded routes ({@code docs/PLAN-v2.md} §5.16, M5-5f) on Spring WebFlux, whose event
 * loops are never scanned: each runs its work on {@code boundedElastic}'s pooled workers, where Reactor's context
 * propagation makes the request's context current. {@code GET /api/thread-locals/leak} leaves {@link
 * TenantContext#CURRENT} set there; its counterexamples, {@code cleared} (removed in {@code finally}) and {@code nulled}
 * (set to {@code null}), never appear; {@code cache} fills a {@code withInitial} date format, reported with its initial
 * value flagged.
 */
@RestController
@RequestMapping("/api/thread-locals")
public class ThreadLocalsController {

    @GetMapping("/leak")
    public Mono<Map<String, Object>> leak(@RequestParam(name = "tenant", defaultValue = "acme") String tenant) {
        return elastic(() -> {
            TenantContext.CURRENT.set("tenant-secret-" + tenant);
            return Map.of("virtual", TenantContext.virtual());
        });
    }

    @GetMapping("/cleared")
    public Mono<Map<String, Object>> cleared(@RequestParam(name = "tenant", defaultValue = "acme") String tenant) {
        return elastic(() -> {
            TenantContext.CURRENT.set("tenant-secret-" + tenant);
            try {
                return Map.of("virtual", TenantContext.virtual());
            } finally {
                TenantContext.CURRENT.remove();
            }
        });
    }

    @GetMapping("/nulled")
    public Mono<Map<String, Object>> nulled() {
        return elastic(() -> {
            TenantContext.CURRENT.set("tenant-secret-nulled");
            TenantContext.CURRENT.set(null);
            return Map.of("virtual", TenantContext.virtual());
        });
    }

    @GetMapping("/cache")
    public Mono<Map<String, Object>> cache() {
        return elastic(() ->
                Map.of("day", TenantContext.FORMAT.get().format(new Date(0L)), "virtual", TenantContext.virtual()));
    }

    private static <T> Mono<T> elastic(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }
}
