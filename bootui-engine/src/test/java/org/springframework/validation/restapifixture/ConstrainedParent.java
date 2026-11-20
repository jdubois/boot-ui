package org.springframework.validation.restapifixture;

import jakarta.validation.constraints.NotNull;

/** Namespace regression fixture: a framework prefix is not evidence that a parent has no constraints. */
public class ConstrainedParent {
    @NotNull
    private String value;
}
