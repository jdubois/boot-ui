package io.github.jdubois.bootui.autoconfigure.graphql;

import graphql.execution.ExecutionContext;
import graphql.language.OperationDefinition;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.util.Locale;
import org.springframework.graphql.observation.ExecutionRequestObservationContext;

/**
 * Names the GraphQL operation each request carried, such as {@code query ProductList}, from Spring for GraphQL's
 * {@code graphql.request} observation ({@code docs/PLAN-v2.md} §5.1), so each operation posted to one endpoint is a
 * route of its own. The operation comes from the document graphql-java parsed, never from the raw query text. Only
 * the operation's type and name are kept, never its variables or selection. Observes only.
 */
public final class GraphQlOperationObservationHandler
        implements ObservationHandler<ExecutionRequestObservationContext> {

    private final RequestPhases phases;

    public GraphQlOperationObservationHandler(RequestPhases phases) {
        this.phases = phases;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ExecutionRequestObservationContext;
    }

    @Override
    public void onStart(ExecutionRequestObservationContext context) {
        String requestId = BootUiCorrelation.current().requestId();
        if (requestId != null) {
            context.put(RequestIdKey.class, new RequestIdKey(requestId));
        }
    }

    @Override
    public void onStop(ExecutionRequestObservationContext context) {
        RequestIdKey request = context.get(RequestIdKey.class);
        if (request == null) {
            return;
        }
        try {
            phases.setOperation(request.requestId(), operationOf(context));
        } catch (RuntimeException | LinkageError ex) {
            // Naming the operation is diagnostics only.
        }
    }

    static String operationOf(ExecutionRequestObservationContext context) {
        ExecutionContext execution = context.getExecutionContext();
        OperationDefinition definition = execution == null ? null : execution.getOperationDefinition();
        if (definition == null) {
            return null;
        }
        String type = definition.getOperation() == null
                ? "query"
                : definition.getOperation().name().toLowerCase(Locale.ROOT);
        String name = definition.getName();
        return name == null || name.isBlank() ? type : type + " " + name;
    }

    private record RequestIdKey(String requestId) {}
}
