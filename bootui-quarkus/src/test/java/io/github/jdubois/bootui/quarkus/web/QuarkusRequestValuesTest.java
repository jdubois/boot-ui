package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.javaagent.AgentRequestValues;
import io.github.jdubois.bootui.engine.javaagent.RequestValuesBinding;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveContainerRequestContext;
import org.jboss.resteasy.reactive.server.spi.ServerHttpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Quarkus' push point of the request value holder ({@code docs/PLAN-v2.md} §5.16, M5-6b): the decoded query parameters
 * and the matched path parameters, never the body, removed on Vert.x's end handler, which is registered before the
 * push, so a response that ends on another thread, or a connection that closes, still removes them.
 */
class QuarkusRequestValuesTest {

    private static final String REQUEST = "00000000000000ab";

    @BeforeEach
    void bind() {
        FakeRequestValues.reset();
        RequestValuesBinding.bind(FakeRequestValues.class);
        AgentRequestValues.configure(true);
    }

    @AfterEach
    void unbind() {
        AgentRequestValues.configure(false);
        RequestValuesBinding.rebind();
        FakeRequestValues.reset();
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryAndPathParametersArePushedAndRemovedWhenTheResponseEnds() {
        RoutingContext routing = routing(false);
        ResteasyReactiveContainerRequestContext request = request(routing, "name", "Robert'); DROP");

        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest(REQUEST))) {
            QuarkusRequestPhaseFilter.pushRequestValues(request);
        }

        assertThat(FakeRequestValues.PUSHED).containsEntry("file", "q3-report").containsEntry("name", "Robert'); DROP");
        ArgumentCaptor<Handler<AsyncResult<Void>>> end = ArgumentCaptor.forClass(Handler.class);
        verify(routing).addEndHandler(end.capture());
        verify(request, never()).getEntityStream();
        assertThat(FakeRequestValues.CALLS).containsExactly("begin " + REQUEST);

        end.getValue().handle(Future.failedFuture("connection closed"));
        assertThat(FakeRequestValues.CALLS).containsExactly("begin " + REQUEST, "end " + REQUEST);
    }

    @Test
    void aConnectionThatClosedBeforeTheEndHandlerWasAddedRemovesTheValuesAtOnce() {
        RoutingContext routing = routing(true);

        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest(REQUEST))) {
            QuarkusRequestPhaseFilter.pushRequestValues(request(routing, "name", "alice"));
        }

        assertThat(FakeRequestValues.CALLS).containsExactly("begin " + REQUEST, "end " + REQUEST);
    }

    @Test
    void nothingIsPushedForBootUiWithoutParametersOrWhileMatchingIsOff() {
        RoutingContext routing = routing(false);
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.BOOTUI)) {
            QuarkusRequestPhaseFilter.pushRequestValues(request(routing, "name", "alice"));
        }
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest(REQUEST))) {
            QuarkusRequestPhaseFilter.pushRequestValues(request(routing, null, null));
            AgentRequestValues.configure(false);
            QuarkusRequestPhaseFilter.pushRequestValues(request(routing, "name", "alice"));
        }

        assertThat(FakeRequestValues.CALLS).isEmpty();
        verify(routing, never()).addEndHandler(any());
    }

    private static RoutingContext routing(boolean closed) {
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(response.closed()).thenReturn(closed);
        RoutingContext routing = mock(RoutingContext.class);
        when(routing.response()).thenReturn(response);
        return routing;
    }

    private static ResteasyReactiveContainerRequestContext request(RoutingContext routing, String name, String value) {
        ServerHttpRequest server = mock(ServerHttpRequest.class);
        when(server.unwrap(RoutingContext.class)).thenReturn(routing);
        ResteasyReactiveRequestContext context = mock(ResteasyReactiveRequestContext.class);
        when(context.serverRequest()).thenReturn(server);
        ResteasyReactiveContainerRequestContext request = mock(ResteasyReactiveContainerRequestContext.class);
        when(request.getServerRequestContext()).thenReturn(context);
        UriInfo uri = mock(UriInfo.class);
        MultivaluedHashMap<String, String> query = new MultivaluedHashMap<>();
        MultivaluedHashMap<String, String> path = new MultivaluedHashMap<>();
        if (name != null) {
            query.add(name, value);
            path.add("file", "q3-report");
        }
        when(uri.getQueryParameters()).thenReturn(query);
        when(uri.getPathParameters()).thenReturn(path);
        when(request.getUriInfo()).thenReturn(uri);
        return request;
    }
}
