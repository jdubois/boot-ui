package io.github.jdubois.bootui.engine.crac;

import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.List;

/**
 * Read-only context shared by every readiness check during a single scan: the imported host
 * application classes, the base packages they were imported from, and a live runtime inventory of
 * resources (such as connection pools) that are auto-configured by Spring rather than present in the
 * application's own bytecode.
 */
record CracContext(JavaClasses classes, List<String> basePackages, CracRuntimeInventory runtime) {

    CracContext {
        basePackages = List.copyOf(basePackages);
        runtime = runtime == null ? CracRuntimeInventory.empty() : runtime;
    }
}
