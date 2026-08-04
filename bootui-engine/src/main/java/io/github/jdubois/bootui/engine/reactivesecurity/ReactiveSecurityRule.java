package io.github.jdubois.bootui.engine.reactivesecurity;

import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;

/**
 * Rule interface for the reactive (WebFlux) Spring Security advisor, bound to
 * {@link ReactiveSecurityContext}.
 */
interface ReactiveSecurityRule {

    ReactiveSecurityRuleDefinition definition();

    SecurityRuleResultDto evaluate(ReactiveSecurityContext context);
}
