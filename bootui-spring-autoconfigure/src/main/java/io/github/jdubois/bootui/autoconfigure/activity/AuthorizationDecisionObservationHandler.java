package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveRequestCorrelationFilter;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.journal.AuthorizationPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorityAuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationObservationContext;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.method.MethodInvocationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;

/**
 * Records each authorization decision Spring Security observes in the runtime journal's {@code authorization} source
 * ({@code docs/PLAN-v2.md} §5.18), on Spring MVC and WebFlux: what was checked, the rule when the decision names it, how
 * the caller was authenticated, and whether access was granted. A request's decision joins its request by the id
 * BootUI's correlation filter set, on the thread or on the exchange; a method's names its {@code Class#method}. It
 * keeps a count of authorities, never their names or the principal. Observes only.
 */
public final class AuthorizationDecisionObservationHandler
        implements ObservationHandler<AuthorizationObservationContext<?>> {

    private static final String REACTIVE_AUTHORIZATION_CONTEXT =
            "org.springframework.security.web.server.authorization.AuthorizationContext";

    private final Supplier<RuntimeJournal> journal;

    public AuthorizationDecisionObservationHandler(Supplier<RuntimeJournal> journal) {
        this.journal = journal;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof AuthorizationObservationContext<?>;
    }

    @Override
    public void onStart(AuthorizationObservationContext<?> context) {
        context.put(
                Started.class,
                new Started(
                        System.nanoTime(),
                        System.currentTimeMillis(),
                        BootUiCorrelation.current(),
                        Thread.currentThread().getName()));
    }

    @Override
    public void onStop(AuthorizationObservationContext<?> context) {
        try {
            record(context);
        } catch (RuntimeException | LinkageError ex) {
            // Recording never changes the decision it observes.
        }
    }

    private void record(AuthorizationObservationContext<?> context) {
        Started started = context.get(Started.class);
        RuntimeJournal target = journal.get();
        AuthorizationResult result = context.getAuthorizationResult();
        if (started == null || target == null || result == null || !target.records(JournalSource.AUTHORIZATION)) {
            return;
        }
        Object object = context.getObject();
        ServerWebExchange exchange = exchange(object);
        CorrelationContext correlation = started.correlation();
        if (exchange != null) {
            CorrelationContext fromExchange = ReactiveCorrelation.of(exchange);
            if (fromExchange.requestId() != null) {
                correlation = fromExchange;
            }
        }
        Authentication authentication = context.getAuthentication();
        if (authentication == null && exchange == null) {
            // On a servlet thread, the request's security context holds the caller even when the rule did not ask.
            authentication = SecurityContextHolder.getContext().getAuthentication();
        }
        boolean method = object instanceof MethodInvocation || object instanceof MethodInvocationResult;
        AuthorizationPayload payload = new AuthorizationPayload(
                method ? AuthorizationPayload.METHOD : AuthorizationPayload.REQUEST,
                method ? subject(object) : null,
                rule(result),
                authentication == null && exchange != null
                        ? AuthorizationPayload.UNKNOWN
                        : authenticationOf(authentication),
                result.isGranted(),
                authentication == null ? 0 : authentication.getAuthorities().size());
        target.offer(RuntimeEvent.of(
                JournalSource.AUTHORIZATION,
                started.epochMillis(),
                Math.max(0, System.nanoTime() - started.nanos()),
                correlation,
                started.thread(),
                null,
                !result.isGranted(),
                payload));
    }

    static String authenticationOf(Authentication authentication) {
        if (authentication == null) {
            return AuthorizationPayload.NONE;
        }
        if (authentication instanceof AnonymousAuthenticationToken) {
            return AuthorizationPayload.ANONYMOUS;
        }
        return authentication.isAuthenticated() ? AuthorizationPayload.AUTHENTICATED : AuthorizationPayload.NONE;
    }

    /** The rule a decision names: the authorities it required, or its type when that says more than granted. */
    static String rule(AuthorizationResult result) {
        if (result instanceof AuthorityAuthorizationDecision authority) {
            return "hasAnyAuthority("
                    + authority.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority)
                            .sorted()
                            .collect(Collectors.joining(", "))
                    + ")";
        }
        return result.getClass() == AuthorizationDecision.class
                ? null
                : result.getClass().getSimpleName();
    }

    private static String subject(Object object) {
        MethodInvocation invocation = object instanceof MethodInvocationResult invocationResult
                ? invocationResult.getMethodInvocation()
                : (MethodInvocation) object;
        Method method = invocation.getMethod();
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }

    private static ServerWebExchange exchange(Object object) {
        if (object instanceof ServerWebExchange exchange) {
            return exchange;
        }
        if (object != null
                && REACTIVE_AUTHORIZATION_CONTEXT.equals(object.getClass().getName())) {
            try {
                Object exchange = object.getClass().getMethod("getExchange").invoke(object);
                return exchange instanceof ServerWebExchange value ? value : null;
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return null;
            }
        }
        return null;
    }

    private record Started(long nanos, long epochMillis, CorrelationContext correlation, String thread) {}

    /** Loaded only for a reactive decision, so a servlet application never loads the reactive filter. */
    private static final class ReactiveCorrelation {

        static CorrelationContext of(ServerWebExchange exchange) {
            return ReactiveRequestCorrelationFilter.correlation(exchange);
        }
    }
}
