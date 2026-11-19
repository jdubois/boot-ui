package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.insights.AnonymousSuccessOnRestrictedRoute;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import io.github.jdubois.bootui.engine.insights.RuntimeInsightsService;
import io.github.jdubois.bootui.engine.journal.AuthorizationPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationObservationContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class AuthorizationDecisionObservationHandlerTests {

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private final AuthorizationDecisionObservationHandler handler =
            new AuthorizationDecisionObservationHandler(() -> journal);

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void nativeMethodRestrictionsRemainMethodFactsRatherThanRequestMatcherEvidence() throws Exception {
        request("public", "/api/articles/public", "/api/articles/{id}", 200, true, true);
        request("draft", "/api/articles/draft", "/api/articles/{id}", 403, true, false);
        request("allowed", "/api/orders/42", "/api/orders/{id}", 200, true, true);
        request("restricted", "/api/orders/43", "/api/orders/{id}", 403, false, null);
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        var retained = journal.entries();

        assertThat(retained.stream()
                        .map(entry -> entry.event().payload())
                        .filter(AuthorizationPayload.class::isInstance)
                        .map(AuthorizationPayload.class::cast))
                .filteredOn(payload -> !payload.request())
                .extracting(AuthorizationPayload::subject)
                .containsExactly("ArticleService#read", "ArticleService#read", "ArticleService#read");
        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
        var report = service.report();
        assertThat(report.observations())
                .filteredOn(observation -> observation.kind().equals(AnonymousSuccessOnRestrictedRoute.KIND))
                .extracting(RuntimeObservationDto::subject)
                .containsExactly("GET /api/orders/{id}");
        report.observations().forEach(observation -> service.insight(observation.id()));
        assertThat(service.report()).isEqualTo(report);
        assertThat(journal.entries()).containsExactlyElementsOf(retained);
    }

    private void request(
            String id, String path, String template, int status, boolean requestGranted, Boolean methodGranted)
            throws Exception {
        CorrelationContext correlation = CorrelationContext.forRequest(id);
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(correlation)) {
            capture(new MockHttpServletRequest("GET", path), requestGranted);
            if (methodGranted != null) {
                MethodInvocation invocation = mock(MethodInvocation.class);
                when(invocation.getMethod()).thenReturn(ArticleService.class.getDeclaredMethod("read"));
                capture(invocation, methodGranted);
                verify(invocation).getMethod();
                verifyNoMoreInteractions(invocation);
            }
            journal.offer(RuntimeEvent.of(
                    JournalSource.HTTP,
                    System.currentTimeMillis(),
                    5_000_000,
                    correlation,
                    Thread.currentThread().getName(),
                    null,
                    false,
                    new HttpPayload("GET", path, template, null, status)));
        }
    }

    private void capture(Object target, boolean granted) {
        AuthorizationObservationContext<Object> context = new AuthorizationObservationContext<>(target);
        context.setAuthentication(new AnonymousAuthenticationToken(
                "test", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        handler.onStart(context);
        context.setAuthorizationResult(new AuthorizationDecision(granted));
        handler.onStop(context);
    }

    private static final class ArticleService {
        void read() {
            throw new AssertionError("An observation must not invoke application methods");
        }
    }
}
