package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.javaagent.AgentThreadLocals;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * The {@code thread-locals} scope of a Quarkus REST request on its worker ({@code docs/PLAN-v2.md} §5.16, M5-5f): the
 * request filter opens it and keeps its token on the request, the response filter closes that token once, and without
 * the sensor nothing is opened.
 */
class QuarkusThreadLocalsFilterTest {

    private final QuarkusThreadLocalsFilter filter = new QuarkusThreadLocalsFilter();
    private final Map<String, Object> properties = new HashMap<>();
    private final ContainerRequestContext request = request(properties);
    private final ContainerResponseContext response = mock(ContainerResponseContext.class);

    @Test
    void aWorkerRequestsScopeOpensAtItsRequestAndClosesAtItsResponse() {
        try (MockedStatic<AgentThreadLocals> agent = mockStatic(AgentThreadLocals.class)) {
            agent.when(AgentThreadLocals::bound).thenReturn(true);
            agent.when(AgentThreadLocals::open).thenReturn(11L);

            filter.filter(request);
            assertThat(properties).containsEntry(QuarkusThreadLocalsFilter.SCOPE, 11L);

            filter.filter(request, response);
            agent.verify(() -> AgentThreadLocals.close(11L));
            assertThat(properties).doesNotContainKey(QuarkusThreadLocalsFilter.SCOPE);

            filter.filter(request, response);
            agent.verify(() -> AgentThreadLocals.close(anyLong()), org.mockito.Mockito.times(1));
        }
    }

    @Test
    void withoutTheSensorNothingIsOpened() {
        try (MockedStatic<AgentThreadLocals> agent = mockStatic(AgentThreadLocals.class)) {
            agent.when(AgentThreadLocals::bound).thenReturn(false);

            filter.filter(request);
            filter.filter(request, response);

            agent.verify(AgentThreadLocals::open, never());
            agent.verify(() -> AgentThreadLocals.close(anyLong()), never());
            assertThat(properties).isEmpty();
        }
    }

    @Test
    void aScopeTheBridgeDidNotOpenIsNeverKeptNorClosed() {
        try (MockedStatic<AgentThreadLocals> agent = mockStatic(AgentThreadLocals.class)) {
            agent.when(AgentThreadLocals::bound).thenReturn(true);
            agent.when(AgentThreadLocals::open).thenReturn(0L);

            filter.filter(request);
            filter.filter(request, response);

            assertThat(properties).isEmpty();
            agent.verify(() -> AgentThreadLocals.close(anyLong()), never());
        }
    }

    private static ContainerRequestContext request(Map<String, Object> properties) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getProperty(anyString())).thenAnswer(invocation -> properties.get(invocation.getArgument(0)));
        doAnswer(invocation -> properties.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(request)
                .setProperty(anyString(), org.mockito.ArgumentMatchers.any());
        doAnswer(invocation -> properties.remove(invocation.getArgument(0)))
                .when(request)
                .removeProperty(anyString());
        return request;
    }
}
