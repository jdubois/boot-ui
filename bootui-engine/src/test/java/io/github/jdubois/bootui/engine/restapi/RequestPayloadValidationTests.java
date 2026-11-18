package io.github.jdubois.bootui.engine.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import jakarta.persistence.Entity;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

class RequestPayloadValidationTests {
    @Test
    void transientConstraintsAndCascadesParticipateWhileStaticFieldsDoNot() {
        RestApiContext context = context(
                TransientBodies.class,
                TransientConstraints.class,
                TransientCascade.class,
                StaticConstraints.class,
                Constrained.class);
        var result = new RequestBodyIsValidatedRule().evaluate(context);
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations())
                .anyMatch(detail -> detail.contains("#direct"))
                .anyMatch(detail -> detail.contains("#cascade"))
                .noneMatch(detail -> detail.contains("#staticOnly"));
        assertThat(context.evidence().requiredUnknown()).isFalse();
    }

    @Test
    void jdkMarkerParentsAndCascadedEnumsDoNotMakeUnconstrainedPayloadsUnknown() {
        RestApiContext clean = context(CleanMarkerBodies.class, MarkerPayload.class, CascadedEnum.class, Choice.class);
        assertThat(new RequestBodyIsValidatedRule().evaluate(clean).status()).isEqualTo("PASS");
        assertThat(clean.evidence().requiredUnknown()).isFalse();
        assertThat(clean.evidence().usable()).isTrue();

        RestApiContext constrained = context(ConstrainedMarkerBody.class, ConstrainedMarker.class);
        assertThat(new RequestBodyIsValidatedRule().evaluate(constrained).violationCount())
                .isEqualTo(1);
        assertThat(constrained.evidence().requiredUnknown()).isFalse();
    }

    @Test
    void availableApplicationAndFrameworkParentsSupplyTheirActualConstraints() {
        RestApiContext context = context(
                InheritedBodies.class,
                InheritedConstrained.class,
                ConstrainedParent.class,
                InheritedPlain.class,
                PlainParent.class,
                InterfacePayload.class,
                RequiredName.class,
                FrameworkPayload.class,
                org.springframework.validation.restapifixture.ConstrainedParent.class);
        var result = new RequestBodyIsValidatedRule().evaluate(context);
        assertThat(result.violationCount()).isEqualTo(3);
        assertThat(result.sampleViolations())
                .anyMatch(detail -> detail.contains("#inherited"))
                .anyMatch(detail -> detail.contains("#interfaceGetter"))
                .anyMatch(detail -> detail.contains("#frameworkParent"))
                .noneMatch(detail -> detail.contains("#plain"));
        assertThat(context.evidence().requiredUnknown()).isFalse();
    }

    @Test
    void unresolvedParentMetadataDoesNotAcquireAFrameworkOrNamespaceWaiver() {
        ArchConfiguration.withThreadLocalScope(configuration -> {
            configuration.setResolveMissingDependenciesFromClassPath(false);
            RestApiContext context = context(new ClassFileImporter()
                    .importClasses(
                            UnknownParentBody.class,
                            FrameworkPayload.class,
                            RestController.class,
                            Controller.class,
                            Component.class,
                            PostMapping.class,
                            RequestMapping.class,
                            AliasFor.class,
                            RequestBody.class));
            assertThat(context.handlers()).hasSize(1);
            assertThat(new RequestBodyIsValidatedRule().evaluate(context).status())
                    .isEqualTo("SKIPPED");
            assertThat(context.evidence().requiredUnknown()).isTrue();
            return null;
        });
    }

    @Test
    void heldTypeUseMetadataDistinguishesConstraintsFromMarkersAndJSpecifyNullability() {
        RestApiContext context = context(
                TypeUseBodies.class,
                NullabilityPayload.class,
                MarkerTypeUsePayload.class,
                PlainTypeUse.class,
                ConstraintTypeUsePayload.class,
                NonBlankElement.class);
        var result = new RequestBodyIsValidatedRule().evaluate(context);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).singleElement().asString().contains("#constrained");
        assertThat(context.evidence().requiredUnknown()).isFalse();

        RestApiContext unknown = context(UnknownTypeUseBody.class, ConstraintTypeUsePayload.class);
        assertThat(new RequestBodyIsValidatedRule().evaluate(unknown).status()).isEqualTo("SKIPPED");
        assertThat(unknown.evidence().requiredUnknown()).isTrue();
    }

    @Test
    void reportsOnlyPositivelyConstrainedPayloadsAcrossSpringAndJaxRsModels() {
        RestApiContext context = context(
                SpringBodies.class,
                JaxBodies.class,
                Constrained.class,
                Unconstrained.class,
                FieldConstraints.class,
                GetterConstraints.class,
                TypeConstraints.class,
                PayloadConstraint.class,
                ContainerConstraints.class,
                GroupConstraints.class);
        RestApiRuleResultDto result = new RequestBodyIsValidatedRule().evaluate(context);

        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(result.violationCount()).isEqualTo(8);
        assertThat(result.sampleViolations())
                .anyMatch(detail -> detail.contains("#recordBody"))
                .anyMatch(detail -> detail.contains("#reactiveBody"))
                .anyMatch(detail -> detail.contains("#fieldBody"))
                .anyMatch(detail -> detail.contains("#getterBody"))
                .anyMatch(detail -> detail.contains("#typeBody"))
                .anyMatch(detail -> detail.contains("#containerBody"))
                .anyMatch(detail -> detail.contains("#groupBody"))
                .anyMatch(detail -> detail.contains("#jaxBody"))
                .noneMatch(detail -> detail.contains("#plainBody")
                        || detail.contains("#referenceConstraintOnly")
                        || detail.contains("#validBody")
                        || detail.contains("#validatedGroup")
                        || detail.contains("#jaxValid"));
        assertThat(context.evidence().requiredUnknown()).isFalse();
    }

    @Test
    void aCascadeMarkerWithoutReachableConstraintsDoesNotJustifyBoundaryValidation() {
        RestApiContext context = context(
                CascadeBodies.class,
                Constrained.class,
                Unconstrained.class,
                Cascaded.class,
                CascadedPlain.class,
                Uncascaded.class,
                CascadedMapKey.class);
        var result = new RequestBodyIsValidatedRule().evaluate(context);

        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations())
                .anyMatch(detail -> detail.contains("#cascadeBody"))
                .anyMatch(detail -> detail.contains("#mapKey"))
                .noneMatch(detail -> detail.contains("#emptyCascade")
                        || detail.contains("#uncascaded")
                        || detail.contains("#repaired")
                        || detail.contains("#nestedGap"));
        var nested = new NestedDtoValidationCascadeRule().evaluate(context);
        assertThat(nested.violationCount()).isEqualTo(1);
        assertThat(nested.sampleViolations()).singleElement().asString().contains("#nestedGap");
    }

    @Test
    void unconstrainedEntityStillBelongsToTheSeparateEntityBoundaryRule() {
        RestApiContext context = context(EntityBody.class, UnconstrainedEntity.class);
        assertThat(new RequestBodyIsValidatedRule().evaluate(context).status()).isEqualTo("PASS");
        assertThat(new NoMassAssignmentViaEntitiesRule().evaluate(context).violationCount())
                .isEqualTo(1);
    }

    @Test
    void unresolvedPayloadMetadataIsSkippedRatherThanHealthyAndKnownFindingsSurvive() {
        ArchConfiguration.withThreadLocalScope(configuration -> {
            configuration.setResolveMissingDependenciesFromClassPath(false);
            JavaClasses unknownClasses = new ClassFileImporter()
                    .importClasses(
                            UnknownBody.class,
                            RestController.class,
                            Controller.class,
                            Component.class,
                            PostMapping.class,
                            RequestMapping.class,
                            AliasFor.class,
                            RequestBody.class);
            RestApiContext unknown = context(unknownClasses);
            assertThat(unknown.handlers()).hasSize(1);
            var result = new RequestBodyIsValidatedRule().evaluate(unknown);
            assertThat(result.status()).isEqualTo("SKIPPED");
            assertThat(unknown.evidence().requiredUnknown()).isTrue();
            assertThat(unknown.evidence().usable()).isFalse();
            var unknownReport = new RestApiScanner(
                            () -> List.of(getClass().getPackageName()),
                            packages -> unknownClasses,
                            () -> false,
                            () -> false,
                            Clock.systemUTC(),
                            List.of(new RequestBodyIsValidatedRule()))
                    .scan();
            assertThat(unknownReport.evidence().coverageComplete()).isFalse();
            assertThat(unknownReport.evidence().usable()).isFalse();

            JavaClasses mixedClasses = new ClassFileImporter()
                    .importClasses(
                            UnknownBody.class,
                            KnownBody.class,
                            Constrained.class,
                            RestController.class,
                            Controller.class,
                            Component.class,
                            PostMapping.class,
                            RequestMapping.class,
                            AliasFor.class,
                            RequestBody.class,
                            NotBlank.class);
            RestApiContext mixed = context(mixedClasses);
            assertThat(mixed.handlers()).hasSize(2);
            var mixedResult = new RequestBodyIsValidatedRule().evaluate(mixed);
            assertThat(mixedResult.violationCount()).isEqualTo(1);
            assertThat(mixedResult.sampleViolations())
                    .singleElement()
                    .asString()
                    .contains("#known");
            assertThat(mixed.evidence().requiredUnknown()).isTrue();
            assertThat(mixed.evidence().usable()).isTrue();
            return null;
        });
    }

    @Test
    void scannerCountsConstrainedFindingsInTheMediumBucket() {
        var scanner = new RestApiScanner(
                () -> List.of(getClass().getPackageName()),
                packages -> new ClassFileImporter().importClasses(KnownBody.class, Constrained.class),
                () -> false,
                () -> false,
                Clock.systemUTC(),
                List.of(new RequestBodyIsValidatedRule()));
        var report = scanner.scan();
        assertThat(report.violationsFound()).isEqualTo(1);
        assertThat(report.results())
                .singleElement()
                .extracting(RestApiRuleResultDto::severity)
                .isEqualTo("MEDIUM");
        assertThat(report.severityCounts())
                .allSatisfy(bucket -> assertThat(bucket.count()).isEqualTo("MEDIUM".equals(bucket.severity()) ? 1 : 0));
    }

    private static RestApiContext context(Class<?>... types) {
        return context(new ClassFileImporter().importClasses(types));
    }

    private static RestApiContext context(JavaClasses classes) {
        var model = RestApiHandlerModelBuilder.build(classes);
        assertThat(model.handlers())
                .as("fixture request mappings remain observable")
                .isNotEmpty();
        return new RestApiContext(
                List.of(RequestPayloadValidationTests.class.getPackageName()),
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

    record Constrained(@NotBlank String name) {}

    record Unconstrained(String name) {}

    static class TransientConstraints {
        @NotBlank
        transient String value;
    }

    static class TransientCascade {
        @Valid
        transient Constrained child;
    }

    static class StaticConstraints {
        @NotBlank
        static String value;
    }

    record MarkerPayload(String name) implements Serializable, Comparable<MarkerPayload>, Cloneable {
        @Override
        public int compareTo(MarkerPayload other) {
            return name.compareTo(other.name);
        }
    }

    record ConstrainedMarker(@NotBlank String name) implements Serializable {}

    enum Choice {
        FIRST
    }

    record CascadedEnum(@Valid Choice choice) {}

    static class ConstrainedParent {
        @NotNull
        String value;
    }

    static class InheritedConstrained extends ConstrainedParent implements Serializable {}

    static class PlainParent {
        String value;
    }

    static class InheritedPlain extends PlainParent implements Serializable {}

    interface RequiredName {
        @NotBlank
        String getName();
    }

    static class InterfacePayload implements RequiredName {
        @Override
        public String getName() {
            return "";
        }
    }

    static class FrameworkPayload extends org.springframework.validation.restapifixture.ConstrainedParent {}

    record NullabilityPayload(
            @org.jspecify.annotations.Nullable String name, List<@org.jspecify.annotations.NonNull String> names) {}

    @Target(ElementType.TYPE_USE)
    @Retention(RetentionPolicy.RUNTIME)
    @interface PlainTypeUse {}

    record MarkerTypeUsePayload(List<@PlainTypeUse String> names) {}

    @Target(ElementType.TYPE_USE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = {})
    @NotBlank
    @interface NonBlankElement {
        String message() default "invalid";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    record ConstraintTypeUsePayload(List<@NonBlankElement String> names) {}

    static class FieldConstraints {
        @NotNull
        String value;
    }

    static class GetterConstraints {
        @NotBlank
        String getName() {
            return "";
        }
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = {})
    @interface PayloadConstraint {
        String message() default "invalid";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    @PayloadConstraint
    record TypeConstraints(String name) {}

    record ContainerConstraints(List<@NotBlank String> names) {}

    interface Update {}

    record GroupConstraints(@NotBlank(groups = Update.class) String name) {}

    record Cascaded(@Valid Constrained child) {}

    record CascadedPlain(@Valid Unconstrained child) {}

    record Uncascaded(Constrained child) {}

    record CascadedMapKey(Map<@Valid Constrained, String> children) {}

    @Entity
    static class UnconstrainedEntity {
        String name;
    }

    @RestController
    static class SpringBodies {
        @PostMapping("/record")
        void recordBody(@RequestBody Constrained body) {}

        @PostMapping("/reactive")
        void reactiveBody(@RequestBody Mono<Constrained> body) {}

        @PostMapping("/field")
        void fieldBody(@RequestBody FieldConstraints body) {}

        @PostMapping("/getter")
        void getterBody(@RequestBody GetterConstraints body) {}

        @PostMapping("/type")
        void typeBody(@RequestBody TypeConstraints body) {}

        @PostMapping("/container")
        void containerBody(@RequestBody ContainerConstraints body) {}

        @PostMapping("/group")
        void groupBody(@RequestBody GroupConstraints body) {}

        @PostMapping("/plain")
        void plainBody(@RequestBody Unconstrained body) {}

        @PostMapping("/reference")
        void referenceConstraintOnly(@NotNull @RequestBody Unconstrained body) {}

        @PostMapping("/valid")
        void validBody(@Valid @RequestBody Constrained body) {}

        @PostMapping("/validated-group")
        void validatedGroup(@Validated(Update.class) @RequestBody GroupConstraints body) {}
    }

    @jakarta.ws.rs.Path("/jax")
    static class JaxBodies {
        @jakarta.ws.rs.POST
        public void jaxBody(Constrained body) {}

        @jakarta.ws.rs.POST
        @jakarta.ws.rs.Path("/valid")
        public void jaxValid(@Valid Constrained body) {}
    }

    @RestController
    static class CascadeBodies {
        @PostMapping("/cascade")
        void cascadeBody(@RequestBody Cascaded body) {}

        @PostMapping("/empty-cascade")
        void emptyCascade(@RequestBody CascadedPlain body) {}

        @PostMapping("/uncascaded")
        void uncascaded(@RequestBody Uncascaded body) {}

        @PostMapping("/repaired")
        void repaired(@Valid @RequestBody Cascaded body) {}

        @PostMapping("/nested-gap")
        void nestedGap(@Valid @RequestBody Uncascaded body) {}

        @PostMapping("/map-key")
        void mapKey(@RequestBody CascadedMapKey body) {}
    }

    @RestController
    static class EntityBody {
        @PostMapping("/entity")
        void entity(@RequestBody UnconstrainedEntity body) {}
    }

    @RestController
    static class UnknownBody {
        @PostMapping("/unknown")
        void unknown(@RequestBody Unconstrained body) {}
    }

    @RestController
    static class KnownBody {
        @PostMapping("/known")
        void known(@RequestBody Constrained body) {}
    }

    @RestController
    static class TransientBodies {
        @PostMapping("/transient")
        void direct(@RequestBody TransientConstraints body) {}

        @PostMapping("/transient-cascade")
        void cascade(@RequestBody TransientCascade body) {}

        @PostMapping("/static")
        void staticOnly(@RequestBody StaticConstraints body) {}
    }

    @RestController
    static class CleanMarkerBodies {
        @PostMapping("/marker")
        void marker(@RequestBody MarkerPayload body) {}

        @PostMapping("/enum")
        void enumCascade(@RequestBody CascadedEnum body) {}
    }

    @RestController
    static class ConstrainedMarkerBody {
        @PostMapping("/constrained-marker")
        void constrained(@RequestBody ConstrainedMarker body) {}
    }

    @RestController
    static class InheritedBodies {
        @PostMapping("/inherited")
        void inherited(@RequestBody InheritedConstrained body) {}

        @PostMapping("/plain-parent")
        void plain(@RequestBody InheritedPlain body) {}

        @PostMapping("/interface")
        void interfaceGetter(@RequestBody InterfacePayload body) {}

        @PostMapping("/framework-parent")
        void frameworkParent(@RequestBody FrameworkPayload body) {}
    }

    @RestController
    static class UnknownParentBody {
        @PostMapping("/unknown-parent")
        void unknown(@RequestBody FrameworkPayload body) {}
    }

    @RestController
    static class TypeUseBodies {
        @PostMapping("/nullable")
        void nullability(@RequestBody NullabilityPayload body) {}

        @PostMapping("/marker-type-use")
        void marker(@RequestBody MarkerTypeUsePayload body) {}

        @PostMapping("/constraint-type-use")
        void constrained(@RequestBody ConstraintTypeUsePayload body) {}
    }

    @RestController
    static class UnknownTypeUseBody {
        @PostMapping("/unknown-type-use")
        void unknown(@RequestBody ConstraintTypeUsePayload body) {}
    }
}
