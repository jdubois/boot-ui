package io.github.jdubois.bootui.engine.crac;

import io.github.jdubois.bootui.core.dto.CracFindingDto;

/**
 * One curated CRaC checkpoint/restore readiness check. Implementations describe themselves through a
 * stable {@link CracCheckDefinition} and evaluate a single outcome against the imported classes.
 */
interface CracCheck {

    CracCheckDefinition definition();

    CracFindingDto evaluate(CracContext context);
}
