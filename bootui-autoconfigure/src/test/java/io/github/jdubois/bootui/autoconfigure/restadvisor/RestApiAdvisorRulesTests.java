package io.github.jdubois.bootui.autoconfigure.restadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.RestApiAdvisorRuleResultDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class RestApiAdvisorRulesTests {

    private static final String FIXTURES = "io.github.jdubois.bootui.autoconfigure.restadvisor.fixtures";
    private static final String GOOD = "io.github.jdubois.bootui.autoconfigure.restadvisor.fixtures.good";
    private static final String BAD = "io.github.jdubois.bootui.autoconfigure.restadvisor.fixtures.bad";

    private RestApiAdvisorContext context(boolean springdocPresent, String... packages) {
        JavaClasses classes = new ClassFileImporter().importPackages(packages);
        RestApiHandlerModelBuilder model = RestApiHandlerModelBuilder.build(classes);
        return new RestApiAdvisorContext(
                List.of(packages),
                model.controllers(),
                model.handlers(),
                model.exceptionHandlers(),
                springdocPresent,
                model.hasExceptionHandling());
    }

    private String status(RestApiAdvisorRule rule, RestApiAdvisorContext context) {
        return rule.evaluate(context).status();
    }

    @Test
    void routingRulesFlagBadController() {
        RestApiAdvisorContext context = context(false, FIXTURES);

        assertThat(status(new UseHttpMethodSpecificMappingsRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new ConsistentPathStyleRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new StateChangingHandlersNotOnGetRule(), context)).isEqualTo("PASS");
    }

    @Test
    void namingRulesFlagVerbsAndCase() {
        RestApiAdvisorContext context = context(false, FIXTURES);

        assertThat(status(new ResourcePathsAreNounsRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new PathSegmentsAreKebabCaseRule(), context)).isEqualTo("VIOLATION");
    }

    @Test
    void responseRulesFlagCreationAndScalarReads() {
        RestApiAdvisorContext context = context(false, FIXTURES);

        assertThat(status(new CreationReturns201Rule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new ReadEndpointsReturnRepresentationRule(), context)).isEqualTo("VIOLATION");
    }

    @Test
    void validationRulesFlagUnvalidatedAndMassAssignment() {
        RestApiAdvisorContext context = context(false, FIXTURES);

        assertThat(status(new RequestBodyIsValidatedRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new NoMassAssignmentViaEntitiesRule(), context)).isEqualTo("VIOLATION");
    }

    @Test
    void payloadRulesFlagEntitiesCollectionsAndMutableDtos() {
        RestApiAdvisorContext context = context(false, FIXTURES);

        assertThat(status(new NoEntitiesInResponsesRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new WrapTopLevelCollectionsRule(), context)).isEqualTo("VIOLATION");
        assertThat(status(new DtosAreImmutableRule(), context)).isEqualTo("VIOLATION");
    }

    @Test
    void paginationRuleFlagsUnpaginatedCollectionRead() {
        RestApiAdvisorContext context = context(false, FIXTURES);

        assertThat(status(new CollectionReadsArePaginatedRule(), context)).isEqualTo("VIOLATION");
    }

    @Test
    void versioningRulesFlagWildcardAndMissingVersion() {
        assertThat(status(new NoWildcardMediaTypesRule(), context(false, FIXTURES))).isEqualTo("VIOLATION");
        // A version signal (/api/v1) exists across the full fixtures, so the versioned rule passes.
        assertThat(status(new ApiIsVersionedRule(), context(false, FIXTURES))).isEqualTo("PASS");
        // The bad controller alone has no version signal.
        assertThat(status(new ApiIsVersionedRule(), context(false, BAD))).isEqualTo("VIOLATION");
    }

    @Test
    void errorHandlingRulesReflectCentralizedHandling() {
        assertThat(status(new NoBroadThrowsOnHandlersRule(), context(false, FIXTURES))).isEqualTo("VIOLATION");
        // The advice in the good package provides centralized handling for the full scan.
        assertThat(status(new CentralizedExceptionHandlingRule(), context(false, FIXTURES))).isEqualTo("PASS");
        // The bad controller alone has no @ControllerAdvice.
        assertThat(status(new CentralizedExceptionHandlingRule(), context(false, BAD))).isEqualTo("VIOLATION");
    }

    @Test
    void documentationRulesAreSkippedWithoutSpringdocAndFireWithIt() {
        assertThat(status(new EndpointsAreDocumentedRule(), context(false, FIXTURES))).isEqualTo("SKIPPED");
        assertThat(status(new ControllersAreTaggedRule(), context(false, FIXTURES))).isEqualTo("SKIPPED");
        assertThat(status(new EndpointsAreDocumentedRule(), context(true, FIXTURES))).isEqualTo("VIOLATION");
        assertThat(status(new ControllersAreTaggedRule(), context(true, FIXTURES))).isEqualTo("VIOLATION");
    }

    @Test
    void cleanControllerPassesCoreRules() {
        RestApiAdvisorContext context = context(false, GOOD);

        assertThat(status(new NoEntitiesInResponsesRule(), context)).isEqualTo("PASS");
        assertThat(status(new RequestBodyIsValidatedRule(), context)).isEqualTo("PASS");
        assertThat(status(new CreationReturns201Rule(), context)).isEqualTo("PASS");
        assertThat(status(new VoidDeleteReturns204Rule(), context)).isEqualTo("PASS");
        assertThat(status(new NoMassAssignmentViaEntitiesRule(), context)).isEqualTo("PASS");
        assertThat(status(new ResourcePathsAreNounsRule(), context)).isEqualTo("PASS");
    }

    @Test
    void ruleThatThrowsDegradesToError() {
        RestApiAdvisorRule throwingRule = new AbstractRestApiAdvisorRule(new RestApiAdvisorRuleDefinition(
                "RAPI-TEST-001",
                "Throwing rule",
                RestApiAdvisorCategory.ROUTING,
                "LOW",
                "Test rule that throws.",
                "n/a",
                "")) {
            @Override
            RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
                throw new IllegalStateException("boom");
            }
        };

        RestApiAdvisorRuleResultDto result = throwingRule.evaluate(context(false, FIXTURES));
        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.sampleViolations()).isNotEmpty();
    }
}
