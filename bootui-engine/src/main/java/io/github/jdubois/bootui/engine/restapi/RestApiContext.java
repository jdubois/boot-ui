package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.engine.restapi.RestApiModel.ControllerModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ExceptionHandlerModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.HandlerMethodModel;
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
        List<String> responseStatusExceptionClasses,
        RestApiModel.Framework framework) {

    RestApiContext {
        basePackages = List.copyOf(basePackages);
        controllers = List.copyOf(controllers);
        handlers = List.copyOf(handlers);
        exceptionHandlers = List.copyOf(exceptionHandlers);
        responseStatusExceptionClasses = List.copyOf(responseStatusExceptionClasses);
    }

    boolean jaxRs() {
        return framework == RestApiModel.Framework.JAX_RS;
    }
}
