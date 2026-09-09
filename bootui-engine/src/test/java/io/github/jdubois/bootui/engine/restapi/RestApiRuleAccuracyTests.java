package io.github.jdubois.bootui.engine.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import io.github.jdubois.bootui.engine.restapi.ruleaccuracy.RestApiRuleAccuracyFixtures;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RestApiRuleAccuracyTests {

    private static RestApiContext context(Class<?>... types) {
        var model = RestApiHandlerModelBuilder.build(new ClassFileImporter().importClasses(types));
        return new RestApiContext(
                List.of(RestApiRuleAccuracyFixtures.class.getPackageName()),
                model.controllers(),
                model.handlers(),
                model.exceptionHandlers(),
                true,
                false,
                model.hasExceptionHandling(),
                model.responseStatusExceptionClasses(),
                model.thrownExceptions(),
                model.framework());
    }

    private static RestApiContext onlyHandlers(RestApiContext context, boolean globalVersioning, String... names) {
        Set<String> selected = Set.of(names);
        return new RestApiContext(
                context.basePackages(),
                context.controllers(),
                context.handlers().stream()
                        .filter(handler -> selected.contains(handler.methodName()))
                        .toList(),
                context.exceptionHandlers(),
                context.openApiAnnotationsPresent(),
                globalVersioning,
                context.hasExceptionHandling(),
                context.responseStatusExceptionClasses(),
                context.thrownExceptions(),
                context.framework());
    }

    private static void violations(RestApiRule rule, RestApiContext context, String... methodNames) {
        RestApiRuleResultDto result = rule.evaluate(context);
        assertThat(result.status())
                .as(rule.definition().id())
                .isEqualTo(methodNames.length == 0 ? "PASS" : "VIOLATION");
        assertThat(result.violationCount()).as(rule.definition().id()).isEqualTo(methodNames.length);
        for (String method : methodNames) {
            assertThat(result.sampleViolations())
                    .as(rule.definition().id() + " " + method)
                    .anyMatch(detail -> detail.contains("#" + method + " ") || detail.contains("#" + method + ")"));
        }
    }

    @Test
    void responseRulesRespectExplicitDynamicAndImperativeStatusSelection() {
        RestApiContext context = context(RestApiRuleAccuracyFixtures.Responses.class);
        violations(new CreationReturns201Rule(), context, "createDefault");
        violations(new VoidDeleteReturns204Rule(), context, "deleteDefault");
        violations(new VoidReadEndpointsReturnContentRule(), context, "emptyRead");
        violations(new NoContentResponsesHaveNoBodyRule(), context, "noContentBody");
        violations(
                new ResponseStatusIgnoredWithResponseEntityRule(),
                context,
                "dynamicNoContent",
                "futureStatus",
                "dynamicRetry",
                "reasonStatus");
        violations(
                new NoUntypedResponseEntityRule(),
                context,
                "untypedEnvelope",
                "untypedHttpEntity",
                "nestedUntypedHttpEntity");
        violations(new CreatedResponsesExposeLocationRule(), context, "created");
        violations(new RetryAfterOnThrottlingResponsesRule(), context, "retry");
        assertThat(new ResponseStatusIgnoredWithResponseEntityRule()
                        .definition()
                        .description())
                .contains("reason may short-circuit")
                .doesNotContain("silently ignored");
    }

    @Test
    void headReviewsOnlyDedicatedContentCapableDeclarations() {
        RestApiContext context = context(RestApiRuleAccuracyFixtures.Responses.class);
        violations(new HeadHandlersDoNotReturnBodiesRule(), context, "dedicatedHead");
        assertThat(new HeadHandlersDoNotReturnBodiesRule().evaluate(context).description())
                .contains("Frameworks suppress HEAD content");
    }

    @Test
    void patchAllowsConcreteXmlVendorAndBinaryFormats() {
        violations(
                new PatchUsesPatchMediaTypeRule(),
                context(RestApiRuleAccuracyFixtures.Responses.class),
                "unspecifiedPatch",
                "broadPatch");
    }

    @Test
    void payloadAndPaginationRulesDistinguishEntitiesBinaryAndExplicitStreams() {
        RestApiContext context = context(RestApiRuleAccuracyFixtures.Responses.class);
        violations(new NoEntitiesInResponsesRule(), context, "entityArray");
        violations(new NoMassAssignmentViaEntitiesRule(), context, "entityInput");
        violations(new RequestBodyIsValidatedRule(), context, "entityInput");
        violations(
                new CollectionReadsArePaginatedRule(),
                context,
                "entityArray",
                "finiteEvents",
                "finiteEnvelopeEvents",
                "collection");
        violations(new ReturnPagedTypeRule(), context, "page");
        assertThat(new ReturnPagedTypeRule().definition().recommendation()).contains("stable", "PageImpl");
        assertThat(new CollectionReadsArePaginatedRule().definition().description())
                .doesNotContain("entire result set");
    }

    @Test
    void requiredBindingsAreCheckedPerCompleteAlternativeWithFrameworkSemantics() {
        RestApiContext context =
                context(RestApiRuleAccuracyFixtures.Bindings.class, RestApiRuleAccuracyFixtures.JaxResource.class);
        violations(new PathVariablesAreBoundRule(), context, "required");
        violations(new DuplicatePathVariableTokenRule(), context, "duplicateTokens");
        violations(new OptionalPrimitiveRequestParamRule(), context, "numericParam");
        violations(new UnboundedMapRequestParamRule(), context, "aggregateMap");
        assertThat(RestApiRuleHelp.pathVariableTokens("/a/{first:[a-z/]{2}}/{second:[a-z]{2}}"))
                .containsExactly("first", "second");
        assertThat(RestApiRuleHelp.segments("/things/{id:[A-Z/]{2}}")).containsExactly("things", "{id:[A-Z/]{2}}");
        violations(new PathSegmentsAreKebabCaseRule(), context);
    }

    @Test
    void routeIdentityKeepsTrailingSlashesAndIgnoresJaxRsVersionBindingsAsDispatchConditions() {
        RestApiContext spring = context(RestApiRuleAccuracyFixtures.Bindings.class);
        violations(new NoDuplicateRouteMappingsRule(), spring);
        var result =
                new NoDuplicateRouteMappingsRule().evaluate(context(RestApiRuleAccuracyFixtures.JaxResource.class));
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).singleElement().asString().contains("#duplicateOne", "#duplicateTwo");
        violations(
                new NoDuplicateRouteMappingsRule(),
                context(
                        RestApiRuleAccuracyFixtures.SpringSharedRoute.class,
                        RestApiRuleAccuracyFixtures.JaxSharedRoute.class));
    }

    @Test
    void repeatedPrefixRequiresEveryMappingAlternativeToAgree() {
        violations(new PreferClassLevelBasePathRule(), context(RestApiRuleAccuracyFixtures.PrefixAliases.class));
        var result =
                new PreferClassLevelBasePathRule().evaluate(context(RestApiRuleAccuracyFixtures.RepeatedPrefix.class));
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.violationCount()).isEqualTo(1);
    }

    @Test
    void versionSignalsRequirePositiveVersionHintsNotVendorBranding() {
        RestApiContext context = context(RestApiRuleAccuracyFixtures.Versions.class);
        for (var handler : context.handlers()) {
            assertThat(RestApiRuleHelp.hasVersionSignal(handler))
                    .as(handler.methodName())
                    .isEqualTo(!Set.of("vendorOnly", "negated").contains(handler.methodName()));
        }
        violations(new ApiIsVersionedRule(), onlyHandlers(context, false, "header", "mediaVersion", "nativeVersion"));
        var negative = new ApiIsVersionedRule().evaluate(onlyHandlers(context, false, "vendorOnly", "negated"));
        assertThat(negative.status()).isEqualTo("VIOLATION");
        assertThat(negative.sampleViolations()).singleElement().asString().contains("No API version signal");
        var bound = context.handlers().stream()
                .filter(handler -> handler.methodName().equals("springVersionBindings"))
                .findFirst()
                .orElseThrow();
        assertThat(RestApiRuleHelp.versioningStrategies(bound)).containsExactlyInAnyOrder("HEADER", "QUERY");
    }

    @Test
    void nativeSpringVersionConditionIsNotAnExtraTransport() {
        RestApiContext context = context(RestApiRuleAccuracyFixtures.Versions.class);
        violations(new MixedVersioningStrategiesRule(), onlyHandlers(context, false, "nativeVersion", "header"));
        assertThat(new MixedVersioningStrategiesRule()
                        .evaluate(onlyHandlers(context, false, "header", "query"))
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(new MixedVersioningStrategiesRule()
                        .evaluate(onlyHandlers(context, false, "mediaVersion", "header"))
                        .status())
                .isEqualTo("VIOLATION");
    }

    @Test
    void springGlobalVersionConfigurationDoesNotSilenceJaxRsHandlersInMixedImports() {
        RestApiContext context =
                context(RestApiRuleAccuracyFixtures.Versions.class, RestApiRuleAccuracyFixtures.JaxResource.class);
        var result = new ApiIsVersionedRule().evaluate(onlyHandlers(context, true, "vendorOnly", "typedResponse"));
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.sampleViolations())
                .singleElement()
                .asString()
                .contains("#typedResponse")
                .doesNotContain("#vendorOnly");
        violations(new ApiIsVersionedRule(), onlyHandlers(context, false, "headerVersion"));
    }

    @Test
    void jaxRsVoidDynamicResponseAndNativeMappersDoNotAcquireSpringConclusions() {
        RestApiContext context = context(RestApiRuleAccuracyFixtures.JaxResource.class);
        violations(new VoidDeleteReturns204Rule(), context);
        violations(new NoUntypedResponseEntityRule(), context);
        violations(new ExceptionHandlersSetErrorStatusRule(), context);
        assertThat(new PreferProblemDetailRule().evaluate(context).status()).isEqualTo("SKIPPED");
        assertThat(context.evidence().usable()).isFalse();
        violations(new BroadExceptionHandlerRule(), context);
        violations(new CollectionReadsArePaginatedRule(), context);
    }

    @Test
    void errorStatusReviewRequiresObservableFixedNonServerError() {
        RestApiContext context = context(
                RestApiRuleAccuracyFixtures.DynamicAdvice.class,
                RestApiRuleAccuracyFixtures.FixedAdvice.class,
                RestApiRuleAccuracyFixtures.FallbackAdvice.class,
                RestApiRuleAccuracyFixtures.ImperativeAdvice.class,
                RestApiRuleAccuracyFixtures.BodyAdvice.class,
                RestApiRuleAccuracyFixtures.UnknownAdvice.class);
        violations(new BroadExceptionHandlerRule(), context, "fixed");
        violations(new ExceptionHandlersSetErrorStatusRule(), context, "missingStatus");
    }

    @Test
    void aggregateExceptionHandlingDoesNotTransferPresenceBetweenFrameworks() {
        RestApiRule rule = new CentralizedExceptionHandlingRule();
        var mixed = rule.evaluate(
                context(RestApiRuleAccuracyFixtures.Responses.class, RestApiRuleAccuracyFixtures.JaxResource.class));
        assertThat(mixed.status()).isEqualTo("SKIPPED");
        assertThat(mixed.sampleViolations()).singleElement().asString().contains("cannot be attributed per framework");

        var springAdviceOnly = rule.evaluate(context(
                RestApiRuleAccuracyFixtures.DynamicAdvice.class, RestApiRuleAccuracyFixtures.JaxWithoutMapper.class));
        assertThat(springAdviceOnly.status()).isEqualTo("SKIPPED");

        var neither = rule.evaluate(context(
                RestApiRuleAccuracyFixtures.Responses.class, RestApiRuleAccuracyFixtures.JaxWithoutMapper.class));
        assertThat(neither.status()).isEqualTo("VIOLATION");

        violations(rule, context(RestApiRuleAccuracyFixtures.JaxResource.class));
        RestApiContext spring = context(RestApiRuleAccuracyFixtures.Responses.class);
        RestApiContext inheritedAdvice = new RestApiContext(
                spring.basePackages(),
                spring.controllers(),
                spring.handlers(),
                List.of(),
                true,
                false,
                true,
                List.of(),
                List.of(),
                RestApiModel.Framework.SPRING);
        violations(rule, inheritedAdvice);

        RestApiContext both = context(
                RestApiRuleAccuracyFixtures.Responses.class, RestApiRuleAccuracyFixtures.JaxWithoutMapper.class);
        RestApiContext inheritedMixedAdvice = new RestApiContext(
                both.basePackages(),
                both.controllers(),
                both.handlers(),
                List.of(),
                true,
                false,
                true,
                List.of(),
                List.of(),
                RestApiModel.Framework.SPRING);
        assertThat(rule.evaluate(inheritedMixedAdvice).status()).isEqualTo("SKIPPED");
    }

    @Test
    void dynamicUnknownViewAndEmptyErrorsAreNotProblemDetailNonconformanceEvidence() {
        RestApiContext context = context(
                RestApiRuleAccuracyFixtures.DynamicAdvice.class,
                RestApiRuleAccuracyFixtures.ImperativeAdvice.class,
                RestApiRuleAccuracyFixtures.ProblemAdvice.class,
                RestApiRuleAccuracyFixtures.Views.class,
                RestApiRuleAccuracyFixtures.UnknownAdvice.class);
        violations(new PreferProblemDetailRule(), context);
        violations(new ConsistentErrorContractRule(), context);
        violations(new ExceptionHandlersDoNotReturnRawStringsRule(), context);
        violations(
                new PreferProblemDetailRule(), context(RestApiRuleAccuracyFixtures.BodyAdvice.class), "missingStatus");
    }

    @Test
    void declaredExceptionCoverageHonorsAliasesWithoutClaimingRuntimeResolution() {
        RestApiContext context = context(
                RestApiRuleAccuracyFixtures.DeclaredFailures.class,
                RestApiRuleAccuracyFixtures.CoveredFailure.class,
                RestApiRuleAccuracyFixtures.UncoveredFailure.class,
                RestApiRuleAccuracyFixtures.AnnotatedFailure.class,
                RestApiRuleAccuracyFixtures.AliasAdvice.class);
        violations(new DeclaredExceptionsHaveHandlersRule(), context, "uncovered");
        assertThat(new DeclaredExceptionsHaveHandlersRule().evaluate(context).sampleViolations())
                .singleElement()
                .asString()
                .contains("no handler declaration was found in the imported model");
        assertThat(new ResponseStatusOnExceptionRule().evaluate(context).status())
                .isEqualTo("VIOLATION");

        RestApiContext covered = context(
                RestApiRuleAccuracyFixtures.DeclaredFailures.class,
                RestApiRuleAccuracyFixtures.AnnotatedFailure.class,
                RestApiRuleAccuracyFixtures.ProblemAdvice.class,
                RestApiRuleAccuracyFixtures.FallbackAdvice.class);
        violations(new ResponseStatusOnExceptionRule(), covered);
    }

    @Test
    void unresolvedMapperExceptionTypesCannotEstablishMissingDeclarations() {
        RestApiContext context = context(
                RestApiRuleAccuracyFixtures.DeclaredFailures.class,
                RestApiRuleAccuracyFixtures.UncoveredFailure.class,
                RestApiRuleAccuracyFixtures.UnresolvedGenericMapper.class);
        assertThat(context.exceptionHandlers()).isNotEmpty();
        assertThat(context.exceptionHandlers())
                .anyMatch(handler -> handler.handledExceptionTypes().isEmpty());
        var result = new DeclaredExceptionsHaveHandlersRule().evaluate(context);
        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations()).singleElement().asString().contains("unresolved handled-exception types");
    }

    @Test
    void errorContractsCompareKnownShapesWithoutTreatingNegotiationAsAContradiction() {
        violations(
                new ConsistentErrorContractRule(),
                context(
                        RestApiRuleAccuracyFixtures.ProblemAdvice.class,
                        RestApiRuleAccuracyFixtures.DynamicAdvice.class));
        violations(new ConsistentErrorContractRule(), context(RestApiRuleAccuracyFixtures.NegotiatedAdvice.class));
        violations(new ConsistentErrorContractRule(), context(RestApiRuleAccuracyFixtures.SameSchemaAdvice.class));
        var result = new ConsistentErrorContractRule()
                .evaluate(context(
                        RestApiRuleAccuracyFixtures.ProblemAdvice.class, RestApiRuleAccuracyFixtures.TextAdvice.class));
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.sampleViolations())
                .singleElement()
                .asString()
                .contains("different error body declaration categories", "problem details", "raw string");
    }

    @Test
    void duplicateRouteSamplesDoNotExposeDispatchConditionValues() {
        var result = new NoDuplicateRouteMappingsRule()
                .evaluate(context(RestApiRuleAccuracyFixtures.SensitiveDispatchConditions.class));
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.sampleViolations())
                .singleElement()
                .asString()
                .contains("/same-condition", "#first", "#second")
                .doesNotContain("fixture-value-not-for-output", "X-Api-Key");
    }

    @Test
    void documentationRecognizesTagContainersAndOperationTagsWithoutRequiringAnnotationsForGeneration() {
        RestApiContext context = context(
                RestApiRuleAccuracyFixtures.ContainerTags.class,
                RestApiRuleAccuracyFixtures.OperationTags.class,
                RestApiRuleAccuracyFixtures.MicroProfileTags.class);
        violations(new ControllersAreTaggedRule(), context);
        var operations = new EndpointsAreDocumentedRule().evaluate(context);
        assertThat(operations.status()).isEqualTo("VIOLATION");
        assertThat(operations.violationCount()).isEqualTo(2);
        assertThat(operations.description()).contains("Generated, static or filtered documentation");
    }

    @Test
    void retiredSignalsNeverEmitEvenWhenTheOldPredicatesWouldMatch() {
        RestApiContext context = context(RestApiRuleAccuracyFixtures.RetiredSignals.class);
        for (RestApiRule rule : Arrays.asList(
                new MutatingItemMethodsTargetResourceRule(),
                new FormatSuffixInPathRule(),
                new ExceptionHandlersDoNotExposeStackTracesRule(),
                new DeprecatedEndpointsSignalDeprecationRule())) {
            var result = rule.evaluate(context);
            assertThat(result.status()).as(result.id()).isEqualTo("SKIPPED");
            assertThat(result.violationCount()).isZero();
            assertThat(result.withDismissed(true).id()).isEqualTo(result.id());
            assertThat(result.withDismissed(true).dismissed()).isTrue();
        }
    }
}
