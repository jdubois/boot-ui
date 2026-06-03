package io.github.jdubois.bootui.autoconfigure.graalvm;

import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.List;

/**
 * Read-only context shared by every readiness check during a single scan: the imported host
 * application classes plus the base packages they were imported from.
 */
record GraalVmContext(JavaClasses classes, List<String> basePackages) {

    GraalVmContext {
        basePackages = List.copyOf(basePackages);
    }
}
