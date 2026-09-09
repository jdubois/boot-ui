package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.engine.restapi.RestApiModel.ControllerModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ExceptionHandlerModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.HandlerMethodModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ThrownExceptionModel;
import java.util.List;

/**
 * Read-only context shared by every REST API Advisor rule during a single scan: the base packages
 * plus the derived, bounded handler model and a few classpath/aggregate flags rules need.
 */
record RestApiContext(
        List<String> basePackages,
        List<ControllerModel> controllers,
        List<HandlerMethodModel> handlers,
        List<ExceptionHandlerModel> exceptionHandlers,
        boolean openApiAnnotationsPresent,
        boolean globalVersioningConfigured,
        boolean hasExceptionHandling,
        List<String> responseStatusExceptionClasses,
        List<ThrownExceptionModel> thrownExceptions,
        RestApiModel.Framework framework,
        RestApiEvaluationEvidence evidence) {

    RestApiContext(
            List<String> basePackages,
            List<ControllerModel> controllers,
            List<HandlerMethodModel> handlers,
            List<ExceptionHandlerModel> exceptionHandlers,
            boolean openApiAnnotationsPresent,
            boolean globalVersioningConfigured,
            boolean hasExceptionHandling,
            List<String> responseStatusExceptionClasses,
            List<ThrownExceptionModel> thrownExceptions,
            RestApiModel.Framework framework) {
        this(
                basePackages,
                controllers,
                handlers,
                exceptionHandlers,
                openApiAnnotationsPresent,
                globalVersioningConfigured,
                hasExceptionHandling,
                responseStatusExceptionClasses,
                thrownExceptions,
                framework,
                new RestApiEvaluationEvidence());
    }

    RestApiContext {
        basePackages = List.copyOf(basePackages);
        controllers = List.copyOf(controllers);
        handlers = List.copyOf(handlers);
        exceptionHandlers = List.copyOf(exceptionHandlers);
        responseStatusExceptionClasses = List.copyOf(responseStatusExceptionClasses);
        thrownExceptions = List.copyOf(thrownExceptions);
    }

    boolean jaxRs() {
        return framework == RestApiModel.Framework.JAX_RS;
    }

    <T> List<T> targets(List<T> values) {
        return targets(values, value -> true);
    }

    <T> List<T> targets(List<T> values, java.util.function.Predicate<T> applicable) {
        List<T> selected = values.stream().filter(applicable).toList();
        evidence.markApplicableIf(!selected.isEmpty());
        return selected;
    }
}

/**
 * Scan-local applicability and completion bookkeeping for one REST API rule evaluation.
 *
 * <p>State is deliberately private. Scan-wide observations are installed once through
 * {@link #observations}, a rule marks what it actually looked at through {@link #markApplicableIf} (usually
 * via {@link RestApiContext#targets}), and records a missing required observation through
 * {@link #markRequiredUnknown()}. Only {@link #complete} derives usability, so it is decided in one place
 * rather than by whichever caller last wrote a field.</p>
 */
final class RestApiEvaluationEvidence {
    private boolean completeExceptionModel = true;
    private boolean openApiKnown = true;
    private boolean versioningKnown = true;
    private boolean applicable;
    private boolean usable;
    private boolean evaluated;
    private boolean requiredUnknown;

    boolean completeExceptionModel() {
        return completeExceptionModel;
    }

    boolean openApiKnown() {
        return openApiKnown;
    }

    boolean versioningKnown() {
        return versioningKnown;
    }

    boolean usable() {
        return usable;
    }

    boolean evaluated() {
        return evaluated;
    }

    boolean requiredUnknown() {
        return requiredUnknown;
    }

    /** Scan-wide observations, established once before any rule runs and unaffected by {@link #reset()}. */
    void observations(boolean completeExceptionModel, boolean openApiKnown, boolean versioningKnown) {
        this.completeExceptionModel = completeExceptionModel;
        this.openApiKnown = openApiKnown;
        this.versioningKnown = versioningKnown;
    }

    void markApplicable() {
        applicable = true;
    }

    void markApplicableIf(boolean value) {
        applicable |= value;
    }

    void markRequiredUnknown() {
        requiredUnknown = true;
    }

    void reset() {
        applicable = false;
        usable = false;
        evaluated = false;
        requiredUnknown = false;
    }

    void complete(io.github.jdubois.bootui.core.dto.RestApiRuleResultDto result) {
        evaluated = true;
        usable = applicable
                && !requiredUnknown
                && (RestApiRuleSupport.PASS.equals(result.status())
                        || RestApiRuleSupport.VIOLATION.equals(result.status()));
        usable |= RestApiRuleSupport.VIOLATION.equals(result.status()) && result.violationCount() > 0;
    }
}
