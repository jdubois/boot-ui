package io.github.jdubois.bootui.engine.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.List;

/**
 * Read-only context shared by every architecture rule during a single scan: the imported host
 * application classes plus the base packages they were imported from.
 */
record ArchitectureContext(
        JavaClasses classes,
        List<String> basePackages,
        ArchitecturePlatform platform,
        ArchitectureEvaluationEvidence evidence) {

    ArchitectureContext(JavaClasses classes, List<String> basePackages, ArchitecturePlatform platform) {
        this(classes, basePackages, platform, new ArchitectureEvaluationEvidence());
    }

    ArchitectureContext {
        basePackages = List.copyOf(basePackages);
    }

    static final class ArchitectureEvaluationEvidence {
        boolean applicable;
        boolean usable;
        boolean evaluated;
        boolean requiredUnknown;

        void reset() {
            applicable = false;
            usable = false;
            evaluated = false;
            requiredUnknown = false;
        }

        void observed() {
            applicable = true;
        }

        void complete(boolean hasFindings) {
            evaluated = true;
            usable = hasFindings || applicable && !requiredUnknown;
        }
    }
}
