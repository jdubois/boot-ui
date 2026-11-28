package io.github.jdubois.bootui.quarkus4.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.jdubois.bootui.engine.correlation.TraceIdSource;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.quarkus4.nativeconsole.BootUiNativeActivation;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.Config;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.junit.jupiter.api.Test;

class QuarkusNativeExceptionCaptureTest {

    @Test
    void denialInstallsNoHandlerAndSkipsPreMappingAndLogCallbacks() throws Exception {
        Instance<ExceptionStore> stores = instance();
        Instance<TraceIdSource> traceIds = instance();
        ExceptionStore store = mock(ExceptionStore.class);
        Config config = mock(Config.class);
        CurrentVertxRequest current = mock(CurrentVertxRequest.class);
        ResteasyReactiveRequestContext request = mock(ResteasyReactiveRequestContext.class);
        var before = Logger.getLogger("").getHandlers();
        LogRecord record = new LogRecord(Level.SEVERE, "host failure");
        record.setThrown(new IllegalArgumentException("host failure"));

        try (var activation = mockStatic(BootUiNativeActivation.class)) {
            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(true);
            QuarkusExceptionCapture capture = new QuarkusExceptionCapture(stores, traceIds, current, config);
            capture.onStart(null);
            capture.onStop(null);
            new QuarkusExceptionLogHandler(store, new InternalPackageMatcher(List.of()), null, current, config)
                    .publish(record);
            new QuarkusPreMappingExceptionCaptureHandler().handle(request);

            assertThat(Logger.getLogger("").getHandlers()).containsExactly(before);
            verifyNoInteractions(stores, traceIds, store, config, current, request);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instance() {
        return mock(Instance.class);
    }
}
