package org.acme.nativeconsole.orm;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@Path("/native-fixture/validation")
@Blocking
public class ValidationFixtureResource {

    private static final Logger LOG = Logger.getLogger(ValidationFixtureResource.class);

    @Inject
    Validator validator;

    @Inject
    @ConfigProperty(name = "fixture.database.password")
    String invalidValue;

    @GET
    public void reject() {
        var violations = validator.validate(new NativeValidationWitness(invalidValue));
        if (violations.isEmpty()) {
            throw new IllegalStateException("The native validation witness unexpectedly passed");
        }
        var failure = new ConstraintViolationException("native-validation-witness", violations);
        LOG.error("native-validation-witness", failure);
        throw failure;
    }

    public static final class NativeValidationWitness {

        @Pattern(regexp = "approved", message = "native field rejected")
        private final String value;

        public NativeValidationWitness(String value) {
            this.value = value;
        }
    }
}
