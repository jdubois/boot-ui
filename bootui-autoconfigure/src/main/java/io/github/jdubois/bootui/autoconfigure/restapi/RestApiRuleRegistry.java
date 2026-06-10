package io.github.jdubois.bootui.autoconfigure.restapi;

import java.util.List;

/**
 * Fixed, reviewable registry of the curated REST API Advisor rules. Adding a rule means adding one
 * focused class plus an entry here; the panel never derives rules from project-specific input.
 */
final class RestApiRuleRegistry {

    private static final List<RestApiRule> ACTIVE_RULES = List.of(
            // Routing & HTTP method mapping
            new UseHttpMethodSpecificMappingsRule(),
            new NoDuplicateRouteMappingsRule(),
            new StateChangingHandlersNotOnGetRule(),
            new PreferClassLevelBasePathRule(),
            new ConsistentPathStyleRule(),
            new PathVariablesAreBoundRule(),
            new NoRequestBodyOnBodylessMethodsRule(),
            new MutatingItemMethodsTargetResourceRule(),
            new DuplicatePathVariableTokenRule(),
            new CatchAllPatternRule(),
            new DeepResourceNestingRule(),
            // Naming & resource design
            new ResourcePathsAreNounsRule(),
            new CollectionsUsePluralNounsRule(),
            new PathSegmentsAreKebabCaseRule(),
            new FormatSuffixInPathRule(),
            // Status codes & responses
            new CreationReturns201Rule(),
            new VoidDeleteReturns204Rule(),
            new NoUntypedResponseEntityRule(),
            new ReadEndpointsReturnRepresentationRule(),
            new VoidReadEndpointsReturnContentRule(),
            new NoContentResponsesHaveNoBodyRule(),
            new ResponseStatusIgnoredWithResponseEntityRule(),
            new CreatedResponsesExposeLocationRule(),
            // Input validation & binding
            new RequestBodyIsValidatedRule(),
            new NoMassAssignmentViaEntitiesRule(),
            new OptionalPrimitiveRequestParamRule(),
            new UnboundedMapRequestParamRule(),
            // DTO & payload contracts
            new NoEntitiesInResponsesRule(),
            new NoUntypedResponseBodiesRule(),
            new DtosAreImmutableRule(),
            new LegacyDateInDtoRule(),
            // Pagination & collections
            new CollectionReadsArePaginatedRule(),
            new ReturnPagedTypeRule(),
            // Versioning & content negotiation
            new ApiIsVersionedRule(),
            new MutatingEndpointsDeclareMediaTypesRule(),
            new NoWildcardMediaTypesRule(),
            new PatchUsesPatchMediaTypeRule(),
            new ResponseProducingEndpointsDeclareProducesRule(),
            new MixedVersioningStrategiesRule(),
            // Error handling & documentation
            new CentralizedExceptionHandlingRule(),
            new NoBroadThrowsOnHandlersRule(),
            new PreferProblemDetailRule(),
            new ExceptionHandlersSetErrorStatusRule(),
            new BroadExceptionHandlerRule(),
            new ResponseStatusOnExceptionRule(),
            new EndpointsAreDocumentedRule(),
            new ControllersAreTaggedRule());

    private RestApiRuleRegistry() {}

    static List<RestApiRule> activeRules() {
        return ACTIVE_RULES;
    }
}
