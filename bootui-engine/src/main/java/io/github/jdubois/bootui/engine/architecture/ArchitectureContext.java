package io.github.jdubois.bootui.engine.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationCollector;
import io.github.jdubois.bootui.engine.archunit.ArchUnitBytecodeImports;
import java.util.List;

/**
 * Read-only context shared by every architecture rule during a single scan: the imported host
 * application classes plus the base packages they were imported from.
 */
record ArchitectureContext(
        JavaClasses classes,
        List<String> basePackages,
        ArchitecturePlatform platform,
        ArchitectureEvaluationEvidence evidence,
        AdvisorViolationCollector violationCollector,
        JavaClasses codingClasses,
        ArchUnitBytecodeImports.Bytecode bytecode) {

    ArchitectureContext(
            ArchUnitBytecodeImports.Result imported, List<String> basePackages, ArchitecturePlatform platform) {
        this(
                imported.classes(),
                basePackages,
                platform,
                new ArchitectureEvaluationEvidence(),
                new AdvisorViolationCollector(10000),
                imported.classes(),
                imported.bytecode());
    }

    ArchitectureContext(
            JavaClasses classes,
            List<String> basePackages,
            ArchitecturePlatform platform,
            ArchitectureEvaluationEvidence evidence,
            AdvisorViolationCollector violationCollector,
            JavaClasses codingClasses) {
        this(
                classes,
                basePackages,
                platform,
                evidence,
                violationCollector,
                codingClasses,
                ArchUnitBytecodeImports.Bytecode.EMPTY);
    }

    ArchitectureContext(JavaClasses classes, List<String> basePackages, ArchitecturePlatform platform) {
        this(classes, basePackages, platform, new ArchitectureEvaluationEvidence());
    }

    ArchitectureContext(
            JavaClasses classes,
            List<String> basePackages,
            ArchitecturePlatform platform,
            ArchitectureEvaluationEvidence evidence) {
        this(classes, basePackages, platform, evidence, new AdvisorViolationCollector(10000));
    }

    ArchitectureContext(
            JavaClasses classes,
            List<String> basePackages,
            ArchitecturePlatform platform,
            ArchitectureEvaluationEvidence evidence,
            AdvisorViolationCollector violationCollector) {
        this(classes, basePackages, platform, evidence, violationCollector, classes);
    }

    ArchitectureContext {
        basePackages = List.copyOf(basePackages);
    }

    ArchitectureContext forCategory(ArchitectureCategory category) {
        return category == ArchitectureCategory.CODING_PRACTICES
                ? new ArchitectureContext(
                        codingClasses, basePackages, platform, evidence, violationCollector, codingClasses, bytecode)
                : this;
    }

    /**
     * Scan-local applicability and completion bookkeeping for one architecture rule evaluation.
     *
     * <p>State is deliberately private. A rule marks what it actually looked at through {@link #observed()}
     * (usually via {@code ArchitectureRuleSupport.observed(predicate, context)}) and records a missing
     * required observation through {@link #markRequiredUnknown()}. Only {@link #complete} derives
     * usability, so it is decided in one place rather than by whichever caller last wrote a field.</p>
     */
    static final class ArchitectureEvaluationEvidence {
        private boolean applicable;
        private boolean usable;
        private boolean evaluated;
        private boolean requiredUnknown;

        boolean usable() {
            return usable;
        }

        boolean evaluated() {
            return evaluated;
        }

        boolean requiredUnknown() {
            return requiredUnknown;
        }

        void reset() {
            applicable = false;
            usable = false;
            evaluated = false;
            requiredUnknown = false;
        }

        void observed() {
            applicable = true;
        }

        void markRequiredUnknown() {
            requiredUnknown = true;
        }

        /** A rule that reports its own findings directly, without an ArchUnit evaluation result. */
        void markUsableIf(boolean hasFindings) {
            usable |= hasFindings;
        }

        void complete(boolean hasFindings) {
            evaluated = true;
            usable = hasFindings || applicable && !requiredUnknown;
        }
    }
}
