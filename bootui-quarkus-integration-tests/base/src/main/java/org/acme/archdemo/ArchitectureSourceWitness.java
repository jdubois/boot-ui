package org.acme.archdemo;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Deliberate INFO finding that gives the source-location contract a main-output witness. */
@ApplicationScoped
public class ArchitectureSourceWitness {
    @Inject
    SampleApplicationBean dependency;
}
