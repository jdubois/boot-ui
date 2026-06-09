package io.github.jdubois.bootui.autoconfigure.crac;

import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.List;

/**
 * Read-only context shared by every readiness check during a single scan: the imported host
 * application classes plus the base packages they were imported from.
 */
record CracContext(JavaClasses classes, List<String> basePackages) {

    CracContext {
        basePackages = List.copyOf(basePackages);
    }
}
