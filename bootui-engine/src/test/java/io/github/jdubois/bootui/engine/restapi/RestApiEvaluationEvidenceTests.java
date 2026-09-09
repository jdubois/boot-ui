package io.github.jdubois.bootui.engine.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

class RestApiEvaluationEvidenceTests {
    @Test
    void untypedResponseDoesNotEstablishAnInspectedImmutableDto() {
        RestApiContext context = context(UntypedController.class);
        var result = new DtosAreImmutableRule().evaluate(context);
        assertThat(result.status()).isEqualTo("PASS");
        assertThat(context.evidence().usable()).isFalse();
        assertThat(context.evidence().requiredUnknown()).isTrue();
    }

    @Test
    void dedicatedHeadSelectorSuppliesBothEvaluationAndCompletion() {
        RestApiContext get = context(ReadController.class);
        assertThat(new HeadHandlersDoNotReturnBodiesRule().evaluate(get).status())
                .isEqualTo("PASS");
        assertThat(get.evidence().usable()).isFalse();
        RestApiContext head = context(HeadController.class);
        var finding = new HeadHandlersDoNotReturnBodiesRule().evaluate(head);
        assertThat(finding.status()).isEqualTo("VIOLATION");
        assertThat(finding.severity()).isEqualTo("INFO");
        assertThat(head.evidence().usable()).isTrue();
        RestApiContext clean = context(EmptyHeadController.class);
        assertThat(new HeadHandlersDoNotReturnBodiesRule().evaluate(clean).status())
                .isEqualTo("PASS");
        assertThat(clean.evidence().usable()).isTrue();
    }

    @Test
    void requiredObservationsAreCheckedByTheRuleEvenOutsideTheScanner() {
        RestApiContext context = context(ReadController.class);
        context.evidence().observations(false, true, true);
        assertThat(new CentralizedExceptionHandlingRule().evaluate(context).status())
                .isEqualTo("SKIPPED");
        assertThat(context.evidence().requiredUnknown()).isTrue();
        assertThat(context.evidence().usable()).isFalse();
        context.evidence().observations(false, false, true);
        assertThat(new EndpointsAreDocumentedRule().evaluate(context).status()).isEqualTo("SKIPPED");
        assertThat(context.evidence().requiredUnknown()).isTrue();
    }

    @Test
    void partialReportRetainsRealInfoEvidenceThroughDismissal() {
        RestApiScanner scanner = new RestApiScanner(
                () -> List.of(getClass().getPackageName()),
                packages -> new ClassFileImporter().importClasses(HeadController.class),
                () -> {
                    throw new IllegalStateException("unavailable");
                },
                () -> false,
                Clock.systemUTC(),
                List.of(new HeadHandlersDoNotReturnBodiesRule(), new EndpointsAreDocumentedRule()));
        var report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(scanner.applyDismissals(report, Set.of("RAPI-RESP-009")).evidence())
                .isEqualTo(report.evidence());
    }

    private static RestApiContext context(Class<?> controller) {
        var model = RestApiHandlerModelBuilder.build(new ClassFileImporter().importClasses(controller));
        return new RestApiContext(
                List.of(controller.getPackageName()),
                model.controllers(),
                model.handlers(),
                model.exceptionHandlers(),
                false,
                false,
                model.hasExceptionHandling(),
                model.responseStatusExceptionClasses(),
                model.thrownExceptions(),
                model.framework());
    }

    @RestController
    static class UntypedController {
        @GetMapping("/unknown")
        Object get() {
            return new Object();
        }
    }

    @RestController
    static class ReadController {
        @GetMapping("/items")
        String get() {
            return "items";
        }
    }

    @RestController
    static class HeadController {
        @RequestMapping(path = "/items", method = RequestMethod.HEAD)
        String head() {
            return "items";
        }
    }

    @RestController
    static class EmptyHeadController {
        @RequestMapping(path = "/items", method = RequestMethod.HEAD)
        void head() {}
    }
}
