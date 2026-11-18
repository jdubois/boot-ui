package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Provider-level counterproof for REST payload metadata; no application or web stack is bootstrapped. */
class RestApiValidationSemanticsTests {
    @Test
    void transientFieldsStillCarryConstraintsAndValidationCascades() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.getClass().getName()).startsWith("org.hibernate.validator.");
            var violations = validator.validate(new TransientPayload());
            assertThat(violations)
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("name", "child.value");
        }
    }

    @Test
    void customTypeUseConstraintsAreNotTheSameAsJSpecifyNullability() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new NullabilityPayload(null))).isEmpty();
            assertThat(validator.validate(new TypeUsePayload(List.of(""))))
                    .extracting(ConstraintViolation::getMessage)
                    .hasSize(1);
        }
    }

    @Test
    void defaultGroupValidationDoesNotSelectAnUnrelatedUpdateGroup() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var payload = new GroupPayload("");
            assertThat(validator.validate(payload)).isEmpty();
            assertThat(validator.validate(payload, Update.class)).hasSize(1);
        }
    }

    static class TransientPayload {
        @jakarta.validation.constraints.NotBlank
        transient String name = "";

        @Valid
        transient Child child = new Child("");
    }

    record Child(@jakarta.validation.constraints.NotBlank String value) {}

    record NullabilityPayload(
            @org.jspecify.annotations.NonNull String value) {}

    @Target(ElementType.TYPE_USE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = {})
    @jakarta.validation.constraints.NotBlank
    @interface NonBlankElement {
        String message() default "invalid";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    record TypeUsePayload(List<@NonBlankElement String> values) {}

    interface Update {}

    record GroupPayload(
            @jakarta.validation.constraints.NotBlank(groups = Update.class)
            String name) {}
}
