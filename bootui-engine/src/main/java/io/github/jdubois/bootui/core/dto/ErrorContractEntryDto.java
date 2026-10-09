package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One row of the REST API panel's error-contract catalogue: a single declared (exception type,
 * handler method) pair.
 *
 * <p>The catalogue is derived from <em>declarations only</em>. BootUI never instantiates a handler,
 * never invokes it, and never throws an exception to observe what it produces, so every field that
 * a framework does not expose statically is reported as explicitly unresolved rather than guessed.</p>
 *
 * <p>Field semantics:</p>
 * <ul>
 *   <li>{@code id} — stable synthetic identifier ({@code component#method(exceptionType)}), used by the
 *       UI for keys and by the Exceptions panel cross-link.</li>
 *   <li>{@code source} — where the declaration came from: {@code SPRING_CONTROLLER_ADVICE},
 *       {@code SPRING_CONTROLLER}, {@code JAKARTA_REST_EXCEPTION_MAPPER}, or
 *       {@code QUARKUS_SERVER_EXCEPTION_MAPPER}.</li>
 *   <li>{@code scope} — {@code GLOBAL} (applies application-wide), {@code SCOPED} (an advice narrowed by
 *       {@code basePackages}/{@code assignableTypes}/{@code annotations}), {@code CONTROLLER} (declared on
 *       the controller or resource it serves), or {@code UNKNOWN}.</li>
 *   <li>{@code scopeTarget} — the narrowing evidence for {@code SCOPED}/{@code CONTROLLER}, or {@code null}.</li>
 *   <li>{@code precedence} — BootUI's resolved ordering rank (lower wins) among the catalogue entries;
 *       {@code precedenceSource} says whether it came from a {@code DECLARED} order/priority annotation or
 *       BootUI's {@code DEFAULT} scope-then-name ordering.</li>
 *   <li>{@code status} — the resolved or declared HTTP status, or {@code null} when unresolved;
 *       {@code statusSource} is {@code ANNOTATION}, {@code DYNAMIC} (built at runtime from a
 *       {@code ResponseEntity}/{@code Response} builder), or {@code UNRESOLVED}.</li>
 *   <li>{@code bodyCategory} — {@code PROBLEM_DETAIL}, {@code CUSTOM_OBJECT}, {@code STRING}, {@code EMPTY},
 *       {@code DYNAMIC}, or {@code UNRESOLVED}; {@code bodyType} is the declared body type when one exists.</li>
 *   <li>{@code produces} — declared media types; empty when the framework leaves them to content negotiation.</li>
 * </ul>
 */
public record ErrorContractEntryDto(
        String id,
        String exceptionType,
        String exceptionSimpleName,
        String component,
        String componentSimpleName,
        String method,
        String source,
        String scope,
        String scopeTarget,
        int precedence,
        String precedenceSource,
        String status,
        String statusSource,
        String bodyCategory,
        String bodyType,
        List<String> produces) {

    public ErrorContractEntryDto {
        produces = DtoCollections.immutableCopy(produces);
    }
}
