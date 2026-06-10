package io.github.jdubois.bootui.autoconfigure.restapi;

import io.github.jdubois.bootui.autoconfigure.restapi.RestApiModel.ControllerModel;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiModel.ExceptionHandlerModel;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiModel.HandlerMethodModel;
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
        boolean springdocPresent,
        boolean hasExceptionHandling,
        List<String> responseStatusExceptionClasses) {

    RestApiContext {
        basePackages = List.copyOf(basePackages);
        controllers = List.copyOf(controllers);
        handlers = List.copyOf(handlers);
        exceptionHandlers = List.copyOf(exceptionHandlers);
        responseStatusExceptionClasses = List.copyOf(responseStatusExceptionClasses);
    }
}
