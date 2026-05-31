package io.github.jdubois.bootui.autoconfigure.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.List;

/**
 * Read-only context shared by every architecture rule during a single scan: the imported host
 * application classes plus the base packages they were imported from.
 */
record ArchitectureContext(JavaClasses classes, List<String> basePackages) {

    ArchitectureContext {
        basePackages = List.copyOf(basePackages);
    }
}
