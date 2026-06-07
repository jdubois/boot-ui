package io.github.jdubois.bootui.autoconfigure.restadvisor;

import io.github.jdubois.bootui.autoconfigure.restadvisor.RestApiAdvisorModel.ControllerModel;
import io.github.jdubois.bootui.autoconfigure.restadvisor.RestApiAdvisorModel.ExceptionHandlerModel;
import io.github.jdubois.bootui.autoconfigure.restadvisor.RestApiAdvisorModel.HandlerMethodModel;
import java.util.List;

/**
 * Read-only context shared by every REST API Advisor rule during a single scan: the base packages
 * plus the derived, bounded handler model and a few classpath/aggregate flags rules need.
 */
record RestApiAdvisorContext(
        List<String> basePackages,
        List<ControllerModel> controllers,
        List<HandlerMethodModel> handlers,
        List<ExceptionHandlerModel> exceptionHandlers,
        boolean springdocPresent,
        boolean hasExceptionHandling) {

    RestApiAdvisorContext {
        basePackages = List.copyOf(basePackages);
        controllers = List.copyOf(controllers);
        handlers = List.copyOf(handlers);
        exceptionHandlers = List.copyOf(exceptionHandlers);
    }
}
