package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.engine.reactivesecurity.ReactiveSecurityAdvisorService;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Serves the Spring Security Advisor panel on the Spring WebFlux (reactive) adapter.
 *
 * <p>{@code GET} returns the last report (initially "not scanned"); {@code POST /scan} collects the
 * application's own registered {@code SecurityWebFilterChain} beans (excluding BootUI's own,
 * mirroring {@code PanelsController}'s availability check) and related security beans via
 * the Spring observation collector, then hands the resulting framework-neutral observation to the
 * framework-neutral advisor service.</p>
 *
 * <p>This controller is the reactive counterpart of the servlet-only
 * {@code io.github.jdubois.bootui.autoconfigure.security.SecurityController}: it reuses the same
 * {@link SecurityReport} DTO and the same {@link DismissedRulesStore}, so the browser UI and
 * dismissal store see an identical shape on both stacks. It is also distinct in scope from the raw
 * Spring Security panel ({@code ReactiveSpringSecurityController}): this one evaluates the observed
 * chains against a best-practice ruleset rather than rendering them as-is.</p>
 */
@RestController
@Lazy
@RequestMapping("/bootui/api/security")
public class ReactiveSecurityController {

    private final ReactiveSecurityAdvisorService advisor;

    public ReactiveSecurityController(ReactiveSecurityAdvisorService advisor) {
        this.advisor = advisor;
    }

    @GetMapping
    public SecurityReport security() {
        return advisor.report();
    }

    /**
     * Runs the security scan off the event loop (on {@code boundedElastic}) because the scan
     * uses reflection and may call {@code Flux.collectList().block(timeout)} internally.
     */
    @PostMapping("/scan")
    public Mono<SecurityReport> scan() {
        return Mono.fromCallable(advisor::scan).subscribeOn(Schedulers.boundedElastic());
    }
}
