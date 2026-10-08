package io.github.jdubois.bootui.core.dto;

/**
 * Cross-link from a retained exception group to the declared handler that would produce its HTTP error
 * response, shown by the Exceptions panel and resolved against the REST API panel's error-contract
 * catalogue.
 *
 * <p>The link is deliberately conservative: it is attached only when the retained evidence identifies
 * <em>exactly one</em> applicable declaration. An exception type with no declaration, with several
 * competing global declarations, or handled only by a controller-scoped declaration that the retained
 * request evidence cannot confirm stays unlinked rather than inventing a relationship.</p>
 *
 * @param entryId the {@link ErrorContractEntryDto#id()} of the matched catalogue entry
 * @param component the declaring component's fully-qualified class name
 * @param componentSimpleName the declaring component's simple class name, for compact display
 * @param method the declaring method name
 * @param scope the matched entry's scope ({@code GLOBAL}, {@code SCOPED} or {@code CONTROLLER})
 * @param status the matched entry's resolved or declared HTTP status, or {@code null} when unresolved
 * @param bodyCategory the matched entry's response-body category
 */
public record ErrorContractLinkDto(
        String entryId,
        String component,
        String componentSimpleName,
        String method,
        String scope,
        String status,
        String bodyCategory) {}
