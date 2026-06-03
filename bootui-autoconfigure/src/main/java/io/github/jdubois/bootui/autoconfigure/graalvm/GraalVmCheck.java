package io.github.jdubois.bootui.autoconfigure.graalvm;

import io.github.jdubois.bootui.core.dto.GraalVmFindingDto;

/**
 * One curated native-image readiness check. Implementations describe themselves through a stable
 * {@link GraalVmCheckDefinition} and evaluate a single outcome against the imported classes.
 */
interface GraalVmCheck {

    GraalVmCheckDefinition definition();

    GraalVmFindingDto evaluate(GraalVmContext context);
}
