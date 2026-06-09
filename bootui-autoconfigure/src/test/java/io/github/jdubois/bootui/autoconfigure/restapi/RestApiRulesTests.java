package io.github.jdubois.bootui.autoconfigure.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class RestApiRulesTests {

    private static final String FIXTURES = "io.github.jdubois.bootui.autoconfigure.restapi.fixtures";
    private static final String GOOD = "io.github.jdubois.bootui.autoconfigure.restapi.fixtures.good";
    private static final String BAD = "io.github.jdubois.bootui.autoconfigure.restapi.fixtures.bad";
    private static final String EDGE = "io.github.jdubois.bootui.autoconfigure.restapi.edgecases";
    private static final String PHASE2_BAD = "io.github.jdubois.bootui.autoconfigure.restapi.phase2.bad";
    private static final String PHASE2_GOOD = "io.github.jdubois.bootui.autoconfigure.restapi.phase2.good";
    private static final String PHASE3_BAD = "io.github.jdubois.bootui.autoconfigure.restapi.phase3.bad";
    private static final String PHASE3_GOOD = "io.github.jdubois.bootui.autoconfigure.restapi.phase3.good";
    private static final String PHASE3_FIXES = "io.github.jdubois.bootui.autoconfigure.restapi.phase3.fixes";

    private RestApiContext context(boolean springdocPresent, String... packages) {
        JavaClasses classes = new ClassFileImporter().importPackages(packages);
        RestApiHandlerModelBuilder model = RestApiHandlerModelBuilder.build(classes);
        return new RestApiContext(
                List.of(packages),
                model.controllers(),
                model.handlers(),
                model.exceptionHandlers(),
                springdocPresent,
                model.hasExceptionHandling(),
                model.responseStatusExceptionClasses());
    }

    private String status(RestApiRule rule, RestApiContext context) {
        return rule.evaluate(context).status();
    }

    @Test
    void routingRulesFlagBadController() {
        RestApiContext context = context(false, FIXTURES);

        assertThat(status(new UseHttpMethodSpecificMappingsRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new ConsistentPathStyleRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new StateChangingHandlersNotOnGetRule(), context)).isEqualTo("PASS");
    }

    @Test
    void namingRulesFlagVerbsAndCase() {
        RestApiContext context = context(false, FIXTURES);

        assertThat(status(new ResourcePathsAreNounsRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new PathSegmentsAreKebabCaseRule(), context)).isEqualTo("VIOLATION");
    }

    @Test
    void responseRulesFlagCreationAndScalarReads() {
        RestApiContext context = context(false, FIXTURES);

        assertThat(status(new CreationReturns201Rule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new ReadEndpointsReturnRepresentationRule(), context)).isEqualTo("VIOLATION");
    }

    @Test
    void validationRulesFlagUnvalidatedAndMassAssignment() {
        RestApiContext context = context(false, FIXTURES);

        assertThat(status(new RequestBodyIsValidatedRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new NoMassAssignmentViaEntitiesRule(), context)).isEqualTo("VIOLATION");
    }

    @Test
    void payloadRulesFlagEntitiesCollectionsAndMutableDtos() {
        RestApiContext context = context(false, FIXTURES);

        assertThat(status(new NoEntitiesInResponsesRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new DtosAreImmutableRule(), context)).isEqualTo("VIOLATION");
    }

    @Test
    void paginationRuleFlagsUnpaginatedCollectionRead() {
        RestApiContext context = context(false, FIXTURES);

        assertThat(status(new CollectionReadsArePaginatedRule(), context)).isEqualTo("VIOLATION");
    }

    @Test
    void versioningRulesFlagWildcardAndMissingVersion() {
        assertThat(status(new NoWildcardMediaTypesRule(), context(false, FIXTURES)))
                .isEqualTo("VIOLATION");
        // The full fixtures mix versioned (/api/v1) and unversioned controllers, so the
        // consistent-strategy rule reports the inconsistency.
        assertThat(status(new ApiIsVersionedRule(), context(false, FIXTURES))).isEqualTo("VIOLATION");
        // The bad controller alone has no version signal at all.
        assertThat(status(new ApiIsVersionedRule(), context(false, BAD))).isEqualTo("VIOLATION");
        // The good controller alone applies /api/v1 consistently.
        assertThat(status(new ApiIsVersionedRule(), context(false, GOOD))).isEqualTo("PASS");
    }

    @Test
    void errorHandlingRulesReflectCentralizedHandling() {
        assertThat(status(new NoBroadThrowsOnHandlersRule(), context(false, FIXTURES)))
                .isEqualTo("VIOLATION");
        // The advice in the good package provides centralized handling for the full scan.
        assertThat(status(new CentralizedExceptionHandlingRule(), context(false, FIXTURES)))
                .isEqualTo("PASS");
        // The bad controller alone has no @ControllerAdvice.
        assertThat(status(new CentralizedExceptionHandlingRule(), context(false, BAD)))
                .isEqualTo("VIOLATION");
    }

    @Test
    void documentationRulesAreSkippedWithoutSpringdocAndFireWithIt() {
        assertThat(status(new EndpointsAreDocumentedRule(), context(false, FIXTURES)))
                .isEqualTo("SKIPPED");
        assertThat(status(new ControllersAreTaggedRule(), context(false, FIXTURES)))
                .isEqualTo("SKIPPED");
        assertThat(status(new EndpointsAreDocumentedRule(), context(true, FIXTURES)))
                .isEqualTo("VIOLATION");
        assertThat(status(new ControllersAreTaggedRule(), context(true, FIXTURES)))
                .isEqualTo("VIOLATION");
    }

    @Test
    void cleanControllerPassesCoreRules() {
        RestApiContext context = context(false, GOOD);

        assertThat(status(new NoEntitiesInResponsesRule(), context)).isEqualTo("PASS");
        assertThat(status(new RequestBodyIsValidatedRule(), context)).isEqualTo("PASS");
        assertThat(status(new CreationReturns201Rule(), context)).isEqualTo("PASS");
        assertThat(status(new VoidDeleteReturns204Rule(), context)).isEqualTo("PASS");
        assertThat(status(new NoMassAssignmentViaEntitiesRule(), context)).isEqualTo("PASS");
        assertThat(status(new ResourcePathsAreNounsRule(), context)).isEqualTo("PASS");
    }

    @Test
    void cleanControllerPassesNewerRules() {
        RestApiContext context = context(false, GOOD);

        assertThat(status(new PathVariablesAreBoundRule(), context)).isEqualTo("PASS");
        assertThat(status(new NoRequestBodyOnBodylessMethodsRule(), context)).isEqualTo("PASS");
        assertThat(status(new VoidReadEndpointsReturnContentRule(), context)).isEqualTo("PASS");
        assertThat(status(new NoContentResponsesHaveNoBodyRule(), context)).isEqualTo("PASS");
        assertThat(status(new ResponseStatusIgnoredWithResponseEntityRule(), context))
                .isEqualTo("PASS");
        assertThat(status(new OptionalPrimitiveRequestParamRule(), context)).isEqualTo("PASS");
        assertThat(status(new MutatingEndpointsDeclareMediaTypesRule(), context))
                .isEqualTo("PASS");
        assertThat(status(new PatchUsesPatchMediaTypeRule(), context)).isEqualTo("PASS");
        assertThat(status(new ExceptionHandlersSetErrorStatusRule(), context)).isEqualTo("PASS");
    }

    @Test
    void routingEdgeRulesFlagPathVariableAndBodyMisuse() {
        RestApiContext context = context(false, EDGE);

        assertThat(status(new PathVariablesAreBoundRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new NoRequestBodyOnBodylessMethodsRule(), context)).isEqualTo("VIOLATION");
    }

    @Test
    void responseEdgeRulesFlagStatusMismatches() {
        RestApiContext context = context(false, EDGE);

        assertThat(status(new VoidReadEndpointsReturnContentRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new NoContentResponsesHaveNoBodyRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new ResponseStatusIgnoredWithResponseEntityRule(), context))
                .isEqualTo("VIOLATION");
    }

    @Test
    void validationEdgeRuleFlagsOptionalPrimitiveParam() {
        RestApiContext context = context(false, EDGE);

        assertThat(status(new OptionalPrimitiveRequestParamRule(), context)).isEqualTo("VIOLATION");
    }

    @Test
    void versioningEdgeRulesFlagMissingConsumesAndPatchMediaType() {
        RestApiContext context = context(false, EDGE);

        assertThat(status(new MutatingEndpointsDeclareMediaTypesRule(), context))
                .isEqualTo("VIOLATION");
        assertThat(status(new PatchUsesPatchMediaTypeRule(), context)).isEqualTo("VIOLATION");
    }

    @Test
    void errorHandlingEdgeRuleFlagsBodyWithoutErrorStatus() {
        RestApiContext context = context(false, EDGE);

        assertThat(status(new ExceptionHandlersSetErrorStatusRule(), context)).isEqualTo("VIOLATION");
    }

    @Test
    void phase2RulesFlagBadPhase2Controller() {
        RestApiContext context = context(false, PHASE2_BAD);

        assertThat(status(new MutatingItemMethodsTargetResourceRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new CreatedResponsesExposeLocationRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new ResponseProducingEndpointsDeclareProducesRule(), context))
                .isEqualTo("VIOLATION");
    }

    @Test
    void phase2RulesPassCleanPhase2Controller() {
        RestApiContext context = context(false, PHASE2_GOOD);

        assertThat(status(new MutatingItemMethodsTargetResourceRule(), context)).isEqualTo("PASS");
        assertThat(status(new CreatedResponsesExposeLocationRule(), context)).isEqualTo("PASS");
        assertThat(status(new ResponseProducingEndpointsDeclareProducesRule(), context))
                .isEqualTo("PASS");
    }

    @Test
    void requestBodyValidationDistinguishesValidatedFromUnvalidatedBodies() {
        // Unvalidated @RequestBody parameters are flagged; @Validated bodies pass.
        assertThat(status(new RequestBodyIsValidatedRule(), context(false, PHASE2_BAD)))
                .isEqualTo("VIOLATION");
        assertThat(status(new RequestBodyIsValidatedRule(), context(false, PHASE2_GOOD)))
                .isEqualTo("PASS");
    }

    @Test
    void ruleThatThrowsDegradesToError() {
        RestApiRule throwingRule =
                new AbstractRestApiRule(new RestApiRuleDefinition(
                        "RAPI-TEST-001",
                        "Throwing rule",
                        RestApiCategory.ROUTING,
                        "LOW",
                        "Test rule that throws.",
                        "n/a",
                        "")) {
                    @Override
                    RestApiRuleResultDto doEvaluate(RestApiContext context) {
                        throw new IllegalStateException("boom");
                    }
                };

        RestApiRuleResultDto result = throwingRule.evaluate(context(false, FIXTURES));
        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.sampleViolations()).isNotEmpty();
    }

    @Test
    void phase3NewRulesFlagBadController() {
        RestApiContext context = context(false, PHASE3_BAD);

        assertThat(status(new DuplicatePathVariableTokenRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new CatchAllPatternRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new DeepResourceNestingRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new FormatSuffixInPathRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new UnboundedMapRequestParamRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new LegacyDateInDtoRule(), context)).isEqualTo("VIOLATION");
    }

    @Test
    void phase3NewRulesPassGoodController() {
        RestApiContext context = context(false, PHASE3_GOOD);

        assertThat(status(new DuplicatePathVariableTokenRule(), context)).isEqualTo("PASS");
        assertThat(status(new CatchAllPatternRule(), context)).isEqualTo("PASS");
        assertThat(status(new DeepResourceNestingRule(), context)).isEqualTo("PASS");
        assertThat(status(new FormatSuffixInPathRule(), context)).isEqualTo("PASS");
        assertThat(status(new UnboundedMapRequestParamRule(), context)).isEqualTo("PASS");
    }

    @Test
    void phase3ErrorHandlingRulesFlagAndPass() {
        RestApiContext badContext = context(false, PHASE3_BAD);

        // ERR-005: broad @ExceptionHandler(Exception.class) with fixed status.
        assertThat(status(new BroadExceptionHandlerRule(), badContext)).isEqualTo("VIOLATION");

        // ERR-006: @ResponseStatus exception (BizException) + ProblemDetail handler present.
        assertThat(status(new ResponseStatusOnExceptionRule(), badContext)).isEqualTo("VIOLATION");

        // Good fixtures: no broad catch-all advice.
        RestApiContext goodContext = context(false, PHASE3_GOOD);
        assertThat(status(new BroadExceptionHandlerRule(), goodContext)).isEqualTo("PASS");
        assertThat(status(new ResponseStatusOnExceptionRule(), goodContext)).isEqualTo("PASS");
    }

    @Test
    void phase3MixedVersioningRuleFlagsAndPasses() {
        // Bad: MixedVersionController uses /v1/ path AND versioned media type.
        RestApiContext mixed = context(false, PHASE3_BAD);
        assertThat(status(new MixedVersioningStrategiesRule(), mixed)).isEqualTo("VIOLATION");

        // Good: all handlers share one versioning strategy.
        RestApiContext consistent = context(false, PHASE3_GOOD);
        assertThat(status(new MixedVersioningStrategiesRule(), consistent)).isEqualTo("PASS");
    }

    @Test
    void classLevelRequestMappingMethodIsInherited() {
        // TypeLevelMethodController has @RequestMapping(method=GET) at class level and
        // @RequestMapping("/{id}") at method level — the handler must be considered explicitly mapped
        // (GET) so MAP-001 does NOT fire.
        RestApiContext context = context(false, PHASE3_FIXES);

        assertThat(status(new UseHttpMethodSpecificMappingsRule(), context)).isEqualTo("PASS");
    }

    @Test
    void classLevelResponseStatusDoesNotTriggerResp007() {
        // ClassStatusController has @ResponseStatus at class level and a ResponseEntity method —
        // RESP-007 should only flag method-level @ResponseStatus, so this must PASS.
        RestApiContext context = context(false, PHASE3_FIXES);

        assertThat(status(new ResponseStatusIgnoredWithResponseEntityRule(), context)).isEqualTo("PASS");
    }

    @Test
    void name001DoesNotFlagAmbiguousNounSegments() {
        // /blog/post/{id} from Phase3GoodController: "post" is an ambiguous noun (blog post),
        // not a clear HTTP-method verb, so NAME-001 must not flag it.
        RestApiContext context = context(false, PHASE3_GOOD);

        assertThat(status(new ResourcePathsAreNounsRule(), context)).isEqualTo("PASS");
    }

    @Test
    void name002DoesNotFlagUncountableNouns() {
        // /history from Phase3GoodController returns a collection but the noun is uncountable —
        // NAME-002 must not flag it.
        RestApiContext context = context(false, PHASE3_GOOD);

        assertThat(status(new CollectionsUsePluralNounsRule(), context)).isEqualTo("PASS");
    }
}
