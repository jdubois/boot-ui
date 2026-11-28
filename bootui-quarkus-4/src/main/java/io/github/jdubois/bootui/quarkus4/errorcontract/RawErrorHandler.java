package io.github.jdubois.bootui.quarkus4.errorcontract;

import io.quarkus.runtime.annotations.RecordableConstructor;
import java.util.List;

/**
 * One declared exception-handling method, captured at <em>build time</em> by the deployment processor from
 * the Jandex index and replayed into the runtime via a {@code @Recorder} (see
 * {@code ErrorContractRecorder}).
 *
 * <p>Build-time capture is used because Quarkus exposes no reliable runtime enumeration of resolved
 * Jakarta REST {@code ExceptionMapper} providers and Quarkus REST {@code @ServerExceptionMapper} methods —
 * the same strategy the Mappings, Scheduled Tasks and Vulnerabilities panels use. BootUI's own components
 * are already excluded from the captured list, so this carrier only ever holds host-application
 * declarations.</p>
 *
 * <p>This record is serialized into the Quarkus bytecode recorder, so its canonical constructor is
 * annotated {@link RecordableConstructor}; the module compiles with {@code -parameters} so the constructor
 * parameter names match the record components. The components map one-to-one onto the neutral
 * {@code ErrorHandlerDescriptor} SPI contract that the engine consumes.</p>
 *
 * @param source the declaration family ({@code JAKARTA_REST_EXCEPTION_MAPPER} or
 *     {@code QUARKUS_SERVER_EXCEPTION_MAPPER})
 * @param componentClassName fully-qualified name of the declaring class
 * @param methodName the declaring method name
 * @param exceptionTypeNames fully-qualified names of the handled exception types
 * @param scope {@code GLOBAL}, {@code CONTROLLER} or {@code UNKNOWN}
 * @param scopeTarget the declaring resource's fully-qualified name for a resource-local mapper, else
 *     {@code null}
 * @param declaredOrder the declared ordering value — {@code @Priority} for a {@code @Provider}
 *     {@code ExceptionMapper}, the {@code priority} attribute of {@code @ServerExceptionMapper} — or
 *     {@code null} when the declaration relies on the framework default
 * @param declaredStatus the Quarkus REST {@code @ResponseStatus} value, or {@code null}
 * @param dynamicStatus {@code true} when the status is built at runtime from a {@code Response} or
 *     {@code RestResponse}
 * @param returnTypeName erased fully-qualified return type of the declaring method, or {@code null}
 * @param bodyTypeName fully-qualified response-body type where the signature exposes it, else {@code null}
 * @param produces the declared {@code @Produces} media types (method-level, falling back to class-level)
 */
public record RawErrorHandler(
        String source,
        String componentClassName,
        String methodName,
        List<String> exceptionTypeNames,
        String scope,
        String scopeTarget,
        Integer declaredOrder,
        String declaredStatus,
        boolean dynamicStatus,
        String returnTypeName,
        String bodyTypeName,
        List<String> produces) {

    @RecordableConstructor
    public RawErrorHandler(
            String source,
            String componentClassName,
            String methodName,
            List<String> exceptionTypeNames,
            String scope,
            String scopeTarget,
            Integer declaredOrder,
            String declaredStatus,
            boolean dynamicStatus,
            String returnTypeName,
            String bodyTypeName,
            List<String> produces) {
        this.source = source;
        this.componentClassName = componentClassName;
        this.methodName = methodName;
        this.exceptionTypeNames = exceptionTypeNames == null ? List.of() : List.copyOf(exceptionTypeNames);
        this.scope = scope;
        this.scopeTarget = scopeTarget;
        this.declaredOrder = declaredOrder;
        this.declaredStatus = declaredStatus;
        this.dynamicStatus = dynamicStatus;
        this.returnTypeName = returnTypeName;
        this.bodyTypeName = bodyTypeName;
        this.produces = produces == null ? List.of() : List.copyOf(produces);
    }
}
