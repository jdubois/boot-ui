package io.github.jdubois.bootui.autoconfigure.restadvisor;

import java.util.List;

/**
 * Fixed, reviewable registry of the curated REST API Advisor rules. Adding a rule means adding one
 * focused class plus an entry here; the panel never derives rules from project-specific input.
 */
final class RestApiAdvisorRuleRegistry {

    private static final List<RestApiAdvisorRule> ACTIVE_RULES = List.of(
            // Routing & HTTP method mapping
            new UseHttpMethodSpecificMappingsRule(),
            new NoDuplicateRouteMappingsRule(),
            new StateChangingHandlersNotOnGetRule(),
            new PreferClassLevelBasePathRule(),
            new ConsistentPathStyleRule(),
            // Naming & resource design
            new ResourcePathsAreNounsRule(),
            new CollectionsUsePluralNounsRule(),
            new PathSegmentsAreKebabCaseRule(),
            // Status codes & responses
            new CreationReturns201Rule(),
            new VoidDeleteReturns204Rule(),
            new NoUntypedResponseEntityRule(),
            new ReadEndpointsReturnRepresentationRule(),
            // Input validation & binding
            new RequestBodyIsValidatedRule(),
            new ControllerValidatedForParamConstraintsRule(),
            new NoMassAssignmentViaEntitiesRule(),
            new ExplicitRequestParamBindingRule(),
            // DTO & payload contracts
            new NoEntitiesInResponsesRule(),
            new NoUntypedResponseBodiesRule(),
            new WrapTopLevelCollectionsRule(),
            new DtosAreImmutableRule(),
            // Pagination & collections
            new CollectionReadsArePaginatedRule(),
            new ReturnPagedTypeRule(),
            // Versioning & content negotiation
            new ApiIsVersionedRule(),
            new MutatingEndpointsDeclareMediaTypesRule(),
            new NoWildcardMediaTypesRule(),
            // Error handling & documentation
            new CentralizedExceptionHandlingRule(),
            new NoBroadThrowsOnHandlersRule(),
            new PreferProblemDetailRule(),
            new EndpointsAreDocumentedRule(),
            new ControllersAreTaggedRule());

    private RestApiAdvisorRuleRegistry() {}

    static List<RestApiAdvisorRule> activeRules() {
        return ACTIVE_RULES;
    }
}
