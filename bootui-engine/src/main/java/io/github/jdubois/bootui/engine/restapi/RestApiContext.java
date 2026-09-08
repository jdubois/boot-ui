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
        if (!selected.isEmpty()) evidence.applicable = true;
        return selected;
    }
}

final class RestApiEvaluationEvidence {
    boolean completeExceptionModel = true;
    boolean openApiKnown = true;
    boolean versioningKnown = true;
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

    void complete(io.github.jdubois.bootui.core.dto.RestApiRuleResultDto result) {
        evaluated = true;
        usable = applicable
                && !requiredUnknown
                && (RestApiRuleSupport.PASS.equals(result.status())
                        || RestApiRuleSupport.VIOLATION.equals(result.status()));
        usable |= RestApiRuleSupport.VIOLATION.equals(result.status()) && result.violationCount() > 0;
    }
}
