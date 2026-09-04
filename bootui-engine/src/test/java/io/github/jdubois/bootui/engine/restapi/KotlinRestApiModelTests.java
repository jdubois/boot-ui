package io.github.jdubois.bootui.engine.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.HandlerMethodModel;
import org.junit.jupiter.api.Test;

/**
 * Pins how the REST API advisor models Kotlin suspending handler methods (see {@code src/test/kotlin}).
 * Every {@code suspend fun} compiles to a method that returns {@code Object} and takes an extra
 * {@code Continuation} parameter, so without unwrapping, each one would be modelled as an untyped
 * body with a mystery parameter — and the DTO, status-code and pagination rules would judge it on
 * that instead of on what the developer wrote.
 */
class KotlinRestApiModelTests {

    private static final String KOTLIN_FIXTURES = "io.github.jdubois.bootui.engine.restapi.kotlinfixtures";

    private RestApiHandlerModelBuilder model() {
        JavaClasses classes = new ClassFileImporter().importPackages(KOTLIN_FIXTURES);
        return RestApiHandlerModelBuilder.build(classes);
    }

    private HandlerMethodModel handler(String name) {
        return model().handlers().stream()
                .filter(handler -> handler.methodName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No handler named " + name));
    }

    @Test
    void suspendingHandlersAreDetectedAndTypedByTheirDeclaredResult() {
        HandlerMethodModel findOrder = handler("findOrder");

        assertThat(findOrder.httpMethods()).containsExactly("GET");
        assertThat(findOrder.effectivePaths()).containsExactly("/api/kotlin-orders/{id}");
        assertThat(findOrder.returnTypeName())
                .isEqualTo("io.github.jdubois.bootui.engine.restapi.kotlinfixtures.KotlinOrderDto");
        assertThat(findOrder.bodyTypeName())
                .isEqualTo("io.github.jdubois.bootui.engine.restapi.kotlinfixtures.KotlinOrderDto");
        assertThat(findOrder.bodyIsUntyped()).isFalse();
        assertThat(findOrder.returnsVoid()).isFalse();
        assertThat(findOrder.pathVariableNames()).containsExactly("id");
    }

    @Test
    void suspendingCollectionHandlersKeepTheirElementType() {
        HandlerMethodModel listOrders = handler("listOrders");

        assertThat(listOrders.returnsCollection()).isTrue();
        assertThat(listOrders.bodyTypeName())
                .isEqualTo("io.github.jdubois.bootui.engine.restapi.kotlinfixtures.KotlinOrderDto");
        assertThat(listOrders.hasExplicitPageParam()).isTrue();
    }

    @Test
    void aSuspendingHandlerWithNoResultIsModelledAsVoid() {
        HandlerMethodModel createOrder = handler("createOrder");

        // The JVM return type is Object and the declared Kotlin result is Unit: neither is a body.
        assertThat(createOrder.returnsVoid()).isTrue();
        assertThat(createOrder.bodyIsUntyped()).isFalse();
        assertThat(createOrder.hasRequestBody()).isTrue();
        assertThat(createOrder.responseStatusValue()).contains("CREATED");
    }

    @Test
    void compilerGeneratedMembersDoNotBecomeHandlers() {
        assertThat(model().handlers())
                .extracting(HandlerMethodModel::methodName)
                .containsExactlyInAnyOrder("findOrder", "listOrders", "createOrder");
    }
}
