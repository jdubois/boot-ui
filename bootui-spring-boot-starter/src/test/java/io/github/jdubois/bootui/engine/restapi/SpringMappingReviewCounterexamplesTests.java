package io.github.jdubois.bootui.engine.restapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.engine.restapi.reviewfixtures.child.PackagePrivateMappingController;
import io.github.jdubois.bootui.engine.restapi.reviewfixtures.visibility.VisibilityBridgeController;
import jakarta.persistence.Entity;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AliasFor;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

class SpringMappingReviewCounterexamplesTests {
    @Test
    void crossClassCovariantBridgeRetainsItsRealDispatchTarget() {
        assertThat(List.of(CovariantController.class.getDeclaredMethods()))
                .anyMatch(java.lang.reflect.Method::isBridge);
        assertThat(nativePaths(CovariantController.class)).containsExactly("/covariant-base");
        RestApiHandlerModelBuilder model = model(CovariantController.class);
        assertThat(model.incomplete()).isFalse();
        assertThat(model.handlers()).singleElement().satisfies(handler -> {
            assertThat(handler.effectivePaths()).containsExactly("/covariant-base");
            assertThat(handler.bodyTypeName()).isEqualTo(String.class.getName());
        });
    }

    @Test
    void packagePrivateOverrideInTheSamePackageKeepsItsConcreteMapping() {
        assertThat(nativePaths(SamePackageController.class)).containsExactly("/same-package-child");
        RestApiHandlerModelBuilder model = model(SamePackageController.class);
        assertThat(model.incomplete()).isFalse();
        assertThat(model.handlers())
                .singleElement()
                .satisfies(handler -> assertThat(handler.effectivePaths()).containsExactly("/same-package-child"));
    }

    @Test
    void productionImporterRetainsVisibilityBridgeAncestry() {
        RestApiHandlerModelBuilder model = RestApiHandlerModelBuilder.build(new ClassFileRestApiImporter()
                .importPackages(List.of(VisibilityBridgeController.class.getPackageName())));
        assertThat(model.incomplete()).isFalse();
        assertThat(model.handlers())
                .extracting(RestApiModel.HandlerMethodModel::effectivePaths)
                .containsExactlyInAnyOrder(List.of("/visibility-base"), List.of("/visibility-own"));
    }

    @Test
    void annotatedInterfaceBlueprintDoesNotDuplicateItsConcreteControllerEndpoints() {
        assertThat(nativePaths(BlueprintController.class)).containsExactly("/blueprint");
        RestApiHandlerModelBuilder model = RestApiHandlerModelBuilder.build(
                new ClassFileImporter().importClasses(Blueprint.class, BlueprintController.class));
        assertThat(model.controllers())
                .singleElement()
                .satisfies(controller ->
                        assertThat(controller.className()).isEqualTo(BlueprintController.class.getName()));
        assertThat(model.handlers()).singleElement();
        assertThat(model(Blueprint.class).handlers()).singleElement();
        var scanner = new RestApiScanner(
                () -> List.of(BlueprintController.class.getPackageName()),
                ignored -> new ClassFileImporter().importClasses(Blueprint.class, BlueprintController.class),
                () -> false,
                () -> false,
                Clock.systemUTC());
        var report = scanner.scan();
        assertThat(report.controllersAnalyzed()).isEqualTo(1);
        assertThat(report.handlersAnalyzed()).isEqualTo(1);
    }

    @Test
    void composedAliasesPreserveNativeMediaAndDispatchConditions() {
        RestApiHandlerModelBuilder model = model(MediaController.class);
        assertThat(model.incomplete()).isFalse();
        assertThat(model.handlers()).singleElement().satisfies(handler -> {
            assertThat(handler.effectiveProduces()).containsExactly("application/problem+json");
            assertThat(handler.effectiveConsumes()).containsExactly("application/json");
            assertThat(handler.params()).containsExactly("tenant");
            assertThat(handler.headers()).containsExactly("X-Api");
        });
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(MediaController.class);
            context.refresh();
            var mvc = new org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping();
            mvc.setApplicationContext(context);
            mvc.afterPropertiesSet();
            var servlet = mvc.getHandlerMethods().keySet().iterator().next();
            assertThat(servlet.getProducesCondition().getExpressions())
                    .extracting(Object::toString)
                    .containsExactly("application/problem+json");
            assertThat(servlet.getConsumesCondition().getExpressions())
                    .extracting(Object::toString)
                    .containsExactly("application/json");
            assertThat(servlet.getParamsCondition().getExpressions())
                    .extracting(Object::toString)
                    .containsExactly("tenant");
            assertThat(servlet.getHeadersCondition().getExpressions())
                    .extracting(Object::toString)
                    .containsExactly("X-Api");
            var flux = new org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping();
            flux.setApplicationContext(context);
            flux.afterPropertiesSet();
            var reactive = flux.getHandlerMethods().keySet().iterator().next();
            assertThat(reactive.getProducesCondition().getExpressions())
                    .extracting(Object::toString)
                    .containsExactly("application/problem+json");
            assertThat(reactive.getConsumesCondition().getExpressions())
                    .extracting(Object::toString)
                    .containsExactly("application/json");
            assertThat(reactive.getParamsCondition().getExpressions())
                    .extracting(Object::toString)
                    .containsExactly("tenant");
            assertThat(reactive.getHeadersCondition().getExpressions())
                    .extracting(Object::toString)
                    .containsExactly("X-Api");
        }
    }

    @Test
    void publicControllerVisibilityBridgeRetainsInheritedAndOwnHandlers() throws Exception {
        assertThat(List.of(VisibilityBridgeController.class.getDeclaredMethods()))
                .anyMatch(method -> method.isBridge() && method.getName().equals("read"));
        Set<String> nativePaths = nativePaths(VisibilityBridgeController.class);
        assertThat(nativePaths).containsExactly("/visibility-base", "/visibility-own");
        MockMvcBuilders.standaloneSetup(new VisibilityBridgeController())
                .build()
                .perform(get("/visibility-base"))
                .andExpect(status().isOk())
                .andExpect(content().string("base"));
        WebTestClient.bindToController(new VisibilityBridgeController())
                .build()
                .get()
                .uri("/visibility-base")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .isEqualTo("base");
        RestApiHandlerModelBuilder model = model(VisibilityBridgeController.class);
        assertThat(model.incomplete()).isFalse();
        assertThat(model.handlers())
                .extracting(RestApiModel.HandlerMethodModel::effectivePaths)
                .containsExactlyInAnyOrder(List.of("/visibility-base"), List.of("/visibility-own"));
    }

    @Test
    void crossPackagePackagePrivateSameSignaturePreservesNativeRoutesOrReportsIncomplete() {
        Set<String> nativePaths = nativePaths(PackagePrivateMappingController.class);
        assertThat(nativePaths).containsExactly("/package-base", "/package-child");
        RestApiHandlerModelBuilder model = model(PackagePrivateMappingController.class);
        Set<String> modeled = new TreeSet<>();
        model.handlers().forEach(handler -> modeled.addAll(handler.effectivePaths()));
        assertThat(model.incomplete() || modeled.equals(nativePaths)).isTrue();
        assertThat(modeled).contains("/package-child");
    }

    @Test
    void unsupportedMultipleMappingOnOneMethodDoesNotDiscardItsReliableSibling() {
        Set<String> nativePaths = nativePaths(MultipleMappingController.class);
        assertThat(nativePaths).contains("/known-sibling").hasSize(2);
        RestApiHandlerModelBuilder model = model(MultipleMappingController.class);
        assertThat(model.incomplete()).isTrue();
        assertThat(model.handlers())
                .anySatisfy(handler -> assertThat(handler.effectivePaths()).containsExactly("/known-sibling"));
    }

    @Test
    void inheritedClassMethodMappingWinsOverUnrelatedImplementedInterfaceMapping() {
        Set<String> nativePaths = nativePaths(ClassDispatchController.class);
        assertThat(nativePaths).containsExactly("/base-read");
        RestApiHandlerModelBuilder model = model(ClassDispatchController.class);
        assertThat(model.handlers())
                .singleElement()
                .satisfies(handler -> assertThat(handler.effectivePaths()).containsExactly("/base-read"));
    }

    @Test
    void genericCollectionParameterRetainsConcreteElementTypeOrReportsIncomplete() throws Exception {
        MockMvcBuilders.standaloneSetup(new GenericListController())
                .build()
                .perform(post("/generic-list").contentType("application/json").content("[{\"name\":\"body\"}]"))
                .andExpect(status().isOk())
                .andExpect(content().string("MyEntity:body"));
        WebTestClient.bindToController(new GenericListController())
                .build()
                .post()
                .uri("/generic-list")
                .header("Content-Type", "application/json")
                .bodyValue("[{\"name\":\"body\"}]")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .isEqualTo("MyEntity:body");
        RestApiHandlerModelBuilder model = model(GenericListController.class);
        assertThat(model.incomplete()
                        || model.handlers().stream().anyMatch(RestApiModel.HandlerMethodModel::requestBodyIsEntity))
                .isTrue();
    }

    @Test
    void responseStatusAndReasonAreResolvedIndependentlyFromTheMappingDeclaration() throws Exception {
        MockMvcBuilders.standaloneSetup(new StatusController())
                .build()
                .perform(get("/independent-status"))
                .andExpect(status().isAccepted())
                .andExpect(result ->
                        assertThat(result.getResponse().getErrorMessage()).isEqualTo("ancestor reason"));
        WebTestClient.bindToController(new StatusController())
                .build()
                .get()
                .uri("/independent-status")
                .exchange()
                .expectStatus()
                .isAccepted();
        RestApiHandlerModelBuilder model = model(StatusController.class);
        assertThat(model.handlers()).singleElement().satisfies(handler -> {
            assertThat(handler.responseStatusValue()).isEqualTo("ACCEPTED");
            assertThat(handler.responseStatusReason()).isEqualTo("ancestor reason");
        });
    }

    @Test
    void moreSpecificDefaultMethodWinsRegardlessOfRedundantInterfaceDeclarationOrder() {
        for (Class<?> controller : List.of(ParentFirstController.class, ChildFirstController.class)) {
            Set<String> nativePaths = nativePaths(controller);
            assertThat(nativePaths).containsExactly("/child-default");
            RestApiHandlerModelBuilder model = model(controller);
            assertThat(model.handlers()).singleElement().satisfies(handler -> {
                assertThat(handler.effectivePaths()).containsExactly("/child-default");
                assertThat(handler.bodyTypeName()).isEqualTo(String.class.getName());
            });
        }
    }

    @Test
    void parameterBindingsOnASeparateInterfaceAreMergedOrReportedIncomplete() throws Exception {
        MockMvcBuilders.standaloneSetup(new SplitParameterController())
                .build()
                .perform(post("/split-body").contentType("application/json").content("{\"name\":\"split\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("split"));
        WebTestClient.bindToController(new SplitParameterController())
                .build()
                .post()
                .uri("/split-body")
                .header("Content-Type", "application/json")
                .bodyValue("{\"name\":\"split\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .isEqualTo("split");
        RestApiHandlerModelBuilder model = model(SplitParameterController.class);
        assertThat(model.incomplete()
                        || model.handlers().stream().anyMatch(RestApiModel.HandlerMethodModel::hasRequestBody))
                .isTrue();
    }

    private static RestApiHandlerModelBuilder model(Class<?> controller) {
        return RestApiHandlerModelBuilder.build(new ClassFileImporter().importClasses(controller));
    }

    private static Set<String> nativePaths(Class<?> controller) {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(controller);
            context.refresh();
            var mvc = new org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping();
            mvc.setApplicationContext(context);
            mvc.afterPropertiesSet();
            Set<String> servlet = new TreeSet<>();
            mvc.getHandlerMethods().keySet().forEach(mapping -> servlet.addAll(mapping.getPatternValues()));
            var flux = new org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping();
            flux.setApplicationContext(context);
            flux.afterPropertiesSet();
            Set<String> reactive = new TreeSet<>();
            flux.getHandlerMethods()
                    .keySet()
                    .forEach(mapping -> mapping.getPatternsCondition()
                            .getPatterns()
                            .forEach(path -> reactive.add(path.getPatternString())));
            assertThat(reactive).isEqualTo(servlet);
            return servlet;
        }
    }

    interface ClassDispatchApi {
        @GetMapping("/api-read")
        String read();
    }

    static class ClassDispatchBase {
        @GetMapping("/base-read")
        public String read() {
            return "base";
        }
    }

    @RestController
    static class ClassDispatchController extends ClassDispatchBase implements ClassDispatchApi {}

    @Entity
    public static class MyEntity {
        public String name;
    }

    interface GenericListApi<T> {
        @PostMapping("/generic-list")
        String read(@RequestBody List<T> body);
    }

    @RestController
    static class GenericListController implements GenericListApi<MyEntity> {
        @Override
        public String read(List<MyEntity> body) {
            return body.get(0).getClass().getSimpleName() + ":" + body.get(0).name;
        }
    }

    interface StatusApi {
        @ResponseStatus(code = HttpStatus.ACCEPTED, reason = "ancestor reason")
        String read();
    }

    interface RoutesApi extends StatusApi {
        @Override
        @GetMapping("/independent-status")
        String read();
    }

    @RestController
    static class StatusController implements RoutesApi {
        @Override
        public String read() {
            return "status";
        }
    }

    interface ParentDefault {
        @GetMapping("/parent-default")
        default String read() {
            return "parent";
        }
    }

    interface ChildDefault extends ParentDefault {
        @Override
        @GetMapping("/child-default")
        default String read() {
            return "child";
        }
    }

    @RestController
    static class ParentFirstController implements ParentDefault, ChildDefault {}

    @RestController
    static class ChildFirstController implements ChildDefault, ParentDefault {}

    public record Payload(String name) {}

    interface SplitRoutes {
        @PostMapping("/split-body")
        String read(Payload body);
    }

    interface SplitBindings {
        String read(@RequestBody Payload body);
    }

    @RestController
    static class SplitParameterController implements SplitRoutes, SplitBindings {
        @Override
        public String read(Payload body) {
            return body.name();
        }
    }

    @RestController
    static class MultipleMappingController {
        @GetMapping("/multiple-get")
        @PostMapping("/multiple-post")
        public String multiple() {
            return "multiple";
        }

        @GetMapping("/known-sibling")
        public String known() {
            return "known";
        }
    }

    @RestController
    interface Blueprint {
        @GetMapping("/blueprint")
        String read();
    }

    @RestController
    static class BlueprintController implements Blueprint {
        @Override
        public String read() {
            return "blueprint";
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @RequestMapping(params = "tenant", headers = "X-Api", consumes = "application/json")
    @interface MediaPrefix {
        @AliasFor(annotation = RequestMapping.class, attribute = "path")
        String[] path() default "/media";

        @AliasFor(annotation = RequestMapping.class, attribute = "produces")
        String[] media() default "text/plain";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @RequestMapping
    @interface MediaRoute {
        @AliasFor(annotation = RequestMapping.class, attribute = "path")
        String[] value() default "/record";

        @AliasFor(annotation = RequestMapping.class, attribute = "produces")
        String[] produces() default {};
    }

    @RestController
    @MediaPrefix(media = "application/xml")
    static class MediaController {
        @MediaRoute(produces = "application/problem+json")
        public String read() {
            return "{}";
        }
    }

    interface CovariantApi {
        @GetMapping("/covariant-api")
        CharSequence read();
    }

    static class CovariantBase {
        @GetMapping("/covariant-base")
        public String read() {
            return "base";
        }
    }

    @RestController
    public static class CovariantController extends CovariantBase implements CovariantApi {}

    static class SamePackageBase {
        @GetMapping("/same-package-base")
        String read() {
            return "base";
        }
    }

    @RestController
    static class SamePackageController extends SamePackageBase {
        @Override
        @GetMapping("/same-package-child")
        String read() {
            return "child";
        }
    }
}
