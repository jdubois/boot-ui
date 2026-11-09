package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.web.BootUiHttpExchangeRepository;
import io.github.jdubois.bootui.autoconfigure.web.BootUiMounts;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeRecording;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.actuate.web.exchanges.Include;
import org.springframework.boot.actuate.web.exchanges.RecordableHttpRequest;
import org.springframework.boot.actuate.web.exchanges.RecordableHttpResponse;
import org.springframework.boot.webflux.actuate.web.exchanges.HttpExchangesWebFilter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/**
 * BootUI's reactive recording filter: Actuator's {@link HttpExchangesWebFilter}, except that it does not record
 * BootUI's own requests into BootUI's own repository while {@code bootui.monitoring.exclude-self} is on, so console
 * polling never takes a slot from application traffic. The servlet sibling is {@code BootUiHttpExchangesFilter}.
 *
 * <p>The decision uses the decoded path below the WebFlux base path, resolved by {@link BootUiReactivePaths} exactly
 * as BootUI's reactive safety filters resolve it, never the query string. When the repository is an application's
 * own, every request is recorded as before and BootUI hides its own at read time.</p>
 *
 * <p>Recording uses Actuator's public exchange model with guarded preparation and response-commit observation.
 * Malformed header names are ignored, and capture failures cannot prevent response commitment or recover an
 * application error.</p>
 */
public class BootUiHttpExchangesWebFilter extends HttpExchangesWebFilter {

    private final boolean skipBootUiRequests;
    private final String path;
    private final String apiPath;
    private final HttpExchangeRecording recording;

    public BootUiHttpExchangesWebFilter(
            HttpExchangeRepository repository,
            Set<Include> includes,
            BootUiProperties properties,
            BootUiSelfDataFilter selfDataFilter) {
        super(repository, includes);
        this.recording = new HttpExchangeRecording(repository, includes);
        this.skipBootUiRequests = repository instanceof BootUiHttpExchangeRepository bootUiRepository
                && bootUiRepository.ownsRetention()
                && !selfDataFilter.shouldInclude(true);
        this.path = properties.getPath();
        this.apiPath = properties.getApiPath();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (skipBootUiRequests && isBootUiPath(BootUiReactivePaths.pathWithinApplication(exchange.getRequest()))) {
            return chain.filter(exchange);
        }
        return Mono.defer(() -> Mono.zip(
                        exchange.getPrincipal().singleOptional(),
                        exchange.getSession().singleOptional()))
                .map(values -> new Observation(
                        values.getT1().orElse(null), values.getT2().orElse(null), true))
                .onErrorResume(RuntimeException.class, failure -> {
                    HttpExchangeRecording.logFailure(failure);
                    return Mono.just(new Observation(null, null, false));
                })
                .flatMap(observation -> {
                    if (observation.enabled()) {
                        observeCommit(exchange, observation);
                    }
                    return chain.filter(exchange);
                });
    }

    private void observeCommit(ServerWebExchange exchange, Observation observation) {
        try {
            HttpExchange.Started started = HttpExchange.start(new ReactiveRequest(exchange.getRequest()));
            ServerHttpResponse response = exchange.getResponse();
            response.beforeCommit(() -> {
                recording.finish(
                        started, new ReactiveResponse(response), observation::principal, observation::sessionId);
                return Mono.empty();
            });
        } catch (RuntimeException failure) {
            HttpExchangeRecording.logFailure(failure);
        }
    }

    private record Observation(Principal principal, WebSession session, boolean enabled) {
        String sessionId() {
            return session != null && session.isStarted() ? session.getId() : null;
        }
    }

    private record ReactiveRequest(ServerHttpRequest request) implements RecordableHttpRequest {

        @Override
        public URI getUri() {
            return request.getURI();
        }

        @Override
        public String getRemoteAddress() {
            var remote = request.getRemoteAddress();
            return remote == null || remote.getAddress() == null
                    ? null
                    : remote.getAddress().toString();
        }

        @Override
        public String getMethod() {
            return request.getMethod().name();
        }

        @Override
        public Map<String, List<String>> getHeaders() {
            return HttpExchangeRecording.headers(request.getHeaders());
        }
    }

    private record ReactiveResponse(ServerHttpResponse response) implements RecordableHttpResponse {

        @Override
        public int getStatus() {
            HttpStatusCode status = response.getStatusCode();
            return status == null ? 200 : status.value();
        }

        @Override
        public Map<String, List<String>> getHeaders() {
            return HttpExchangeRecording.headers(response.getHeaders());
        }
    }

    private boolean isBootUiPath(String requestPath) {
        return BootUiMounts.contains(requestPath, path, apiPath);
    }
}
