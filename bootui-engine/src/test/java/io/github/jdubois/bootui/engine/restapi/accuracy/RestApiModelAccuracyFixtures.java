package io.github.jdubois.bootui.engine.restapi.accuracy;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

record AccuracyDto(String value) {}

@RequestMapping("/accounts/{accountId}")
abstract class BaseAccountController {}

@RequestMapping("/interfaces/{interfaceId}")
interface AccountApi {}

@RestController
class InheritedMappingController extends BaseAccountController {

    @GetMapping("/widgets")
    AccuracyDto inheritedPath(@PathVariable("accountId") String accountId) {
        return new AccuracyDto(accountId);
    }
}

@RestController
class InterfaceMappingController implements AccountApi {

    @GetMapping("/widgets")
    AccuracyDto interfacePath(@PathVariable("interfaceId") String interfaceId) {
        return new AccuracyDto(interfaceId);
    }
}

@RestController
class RestApiModelAccuracyController {

    @GetMapping("/widgets/{id}")
    AccuracyDto explicitPathVariableMismatch(@PathVariable("widgetId") String widgetId) {
        return new AccuracyDto(widgetId);
    }

    @GetMapping("/${api.base_path}/widgets")
    AccuracyDto propertyPlaceholderPath() {
        return new AccuracyDto("placeholder");
    }

    @GetMapping(value = "/conditional", params = "format=json")
    AccuracyDto conditionFromParameter() {
        return new AccuracyDto("parameter");
    }

    @GetMapping(value = "/conditional", headers = "format=json")
    AccuracyDto conditionFromHeader() {
        return new AccuracyDto("header");
    }

    @PostMapping(value = "/representations", consumes = "application/json", produces = "text/plain")
    String consumesJsonProducesText() {
        return "text";
    }

    @PostMapping(value = "/representations", consumes = "text/plain", produces = "application/json")
    AccuracyDto consumesTextProducesJson() {
        return new AccuracyDto("json");
    }

    @PostMapping("/created")
    @ResponseStatus(HttpStatus.CREATED)
    Mono<AccuracyDto> createWithReactiveResponse(ServerWebExchange exchange) {
        return Mono.just(new AccuracyDto("created"));
    }
}

@RestControllerAdvice
class ReactiveResponseAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    AccuracyDto handle(IllegalArgumentException exception, ServerHttpResponse response) {
        return new AccuracyDto(exception.getMessage());
    }
}
