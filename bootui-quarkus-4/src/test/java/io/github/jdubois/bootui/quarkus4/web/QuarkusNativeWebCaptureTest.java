package io.github.jdubois.bootui.quarkus4.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.correlation.TraceIdSource;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.javaagent.AgentCodePaths;
import io.github.jdubois.bootui.engine.javaagent.AgentThreadLocals;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.security.SecurityEventBuffer;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.quarkus4.BootUiQuarkusSafetyFilter;
import io.github.jdubois.bootui.quarkus4.nativeconsole.BootUiNativeActivation;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

class QuarkusNativeWebCaptureTest {

    @Test
    void deniedHooksPassThroughWithoutResolvingCaptureOrOpeningAgentScopes() {
        Instance<HttpExchangeBuffer> exchanges = instance();
        Instance<ExceptionStore> exceptions = instance();
        Instance<TraceIdSource> traceIds = instance();
        Instance<RequestPhases> phases = instance();
        Instance<RuntimeJournal> journals = instance();
        Instance<BootUiQuarkusSafetyFilter> safety = instance();
        Instance<SecurityEventBuffer> security = instance();
        Instance<EmailCaptureService> email = instance();
        Config config = mock(Config.class);
        RoutingContext routing = mock(RoutingContext.class);
        ContainerRequestContext request = mock(ContainerRequestContext.class);

        try (var activation = mockStatic(BootUiNativeActivation.class);
                var codePaths = mockStatic(AgentCodePaths.class);
                var threadLocals = mockStatic(AgentThreadLocals.class)) {
            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(true);
            new QuarkusHttpExchangeCaptureFilter(exchanges, traceIds, config, phases, journals, safety).handle(routing);
            new QuarkusExceptionCaptureFilter(exceptions, traceIds, config).handle(routing);
            new QuarkusSecurityEventCapture(security, traceIds).onSecurityEvent(null);
            new QuarkusEmailCapture(email).onSentMail(null);
            QuarkusRequestPhaseFilter phaseFilter = new QuarkusRequestPhaseFilter(phases);
            phaseFilter.filter(request);
            phaseFilter.filter(request, null);
            QuarkusRequestPhaseFilter.pushRequestValues(request);
            QuarkusRequestSegments.enter(request);
            QuarkusThreadLocalsFilter locals = new QuarkusThreadLocalsFilter();
            locals.filter(request);
            locals.filter(request, null);

            verify(routing, times(2)).next();
            verifyNoMoreInteractions(routing);
            verifyNoInteractions(
                    exchanges, exceptions, traceIds, phases, journals, safety, security, email, config, request);
            codePaths.verifyNoInteractions();
            threadLocals.verifyNoInteractions();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instance() {
        return mock(Instance.class);
    }
}
