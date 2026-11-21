package io.github.jdubois.bootui.engine.restapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AliasFor;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

class SpringMappingDiscoveryTests {
    @Test
    void inheritedMappingRetainsItsDeclaredResponseStatusOnBothNativeStacks() throws Exception {
        MockMvcBuilders.standaloneSetup(new Controller())
                .build()
                .perform(get("/api/value"))
                .andExpect(status().isAccepted());
        WebTestClient.bindToController(new Controller())
                .build()
                .get()
                .uri("/api/value")
                .exchange()
                .expectStatus()
                .isAccepted();
        RestApiHandlerModelBuilder model =
                RestApiHandlerModelBuilder.build(new ClassFileImporter().importClasses(Controller.class));
        assertThat(model.handlers())
                .filteredOn(handler -> handler.methodName().equals("value"))
                .singleElement()
                .satisfies(handler -> {
                    assertThat(handler.methodHasResponseStatus()).isTrue();
                    assertThat(handler.responseStatusValue()).isEqualTo("ACCEPTED");
                });
    }

    @Test
    void modelMatchesNativeMvcAndWebFluxHierarchyAndComposedMappingDiscovery() {
        RestApiHandlerModelBuilder model = RestApiHandlerModelBuilder.build(
                new ClassFileImporter().importClasses(Controller.class, InheritedTypeController.class));
        Set<String> modeled = new TreeSet<>();
        model.handlers()
                .forEach(handler -> handler.effectivePaths()
                        .forEach(path -> modeled.add(path + " " + String.join(",", handler.httpMethods()))));

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(Controller.class);
            context.registerBean(InheritedTypeController.class);
            context.refresh();
            var mvc = new org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping();
            mvc.setApplicationContext(context);
            mvc.afterPropertiesSet();
            Set<String> servlet = new TreeSet<>();
            mvc.getHandlerMethods()
                    .forEach((mapping, method) -> mapping.getPatternValues()
                            .forEach(path -> servlet.add(path + " "
                                    + mapping.getMethodsCondition().getMethods().stream()
                                            .map(Enum::name)
                                            .sorted()
                                            .reduce((a, b) -> a + "," + b)
                                            .orElse(""))));

            var webFlux = new org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping();
            webFlux.setApplicationContext(context);
            webFlux.afterPropertiesSet();
            Set<String> reactive = new TreeSet<>();
            webFlux.getHandlerMethods()
                    .forEach((mapping, method) -> mapping.getPatternsCondition()
                            .getPatterns()
                            .forEach(path -> reactive.add(path.getPatternString() + " "
                                    + mapping.getMethodsCondition().getMethods().stream()
                                            .map(Enum::name)
                                            .sorted()
                                            .reduce((a, b) -> a + "," + b)
                                            .orElse(""))));

            assertThat(model.incomplete()).isFalse();
            assertThat(servlet)
                    .containsExactlyInAnyOrder(
                            "/api/base GET",
                            "/api/default GET",
                            "/api/new GET",
                            "/api/value GET",
                            "/api/converted GET",
                            "/api/private-base GET",
                            "/api/private-controller GET",
                            "/inherited/default GET",
                            "/inherited/old GET",
                            "/inherited/value GET");
            assertThat(reactive).isEqualTo(servlet);
            assertThat(modeled).isEqualTo(servlet);
            assertThat(model.handlers())
                    .filteredOn(handler -> handler.methodName().equals("value"))
                    .allSatisfy(handler -> assertThat(handler.bodyTypeName()).isEqualTo(String.class.getName()));
        }
    }

    @RequestMapping("/inherited")
    interface Contract<T> {
        @GetMapping("/value")
        @ResponseStatus(HttpStatus.ACCEPTED)
        T value();

        @GetMapping("/old")
        String changed();

        @GetMapping("/default")
        default String inherited() {
            return "default";
        }
    }

    interface Conversion<T> {
        @GetMapping("/old-conversion")
        T convert(@RequestParam("value") T value);
    }

    static class Base {
        @GetMapping("/base")
        public String base() {
            return "base";
        }

        @GetMapping("/private-base")
        private String privateMethod() {
            return "base";
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
    @RequestMapping
    @interface Prefix {
        @AliasFor(annotation = RequestMapping.class, attribute = "path")
        String[] routes() default {};
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Prefix
    @interface Api {
        @AliasFor(annotation = Prefix.class, attribute = "routes")
        String[] value() default "/api";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @RequestMapping(method = RequestMethod.GET)
    @interface Read {
        @AliasFor(annotation = RequestMapping.class, attribute = "path")
        String[] value() default {};

        @AliasFor(annotation = RequestMapping.class, attribute = "path")
        String[] path() default {};
    }

    @RestController
    @Api
    static class Controller extends Base implements Contract<String>, Conversion<String> {
        @Override
        public String value() {
            return "value";
        }

        public String value(Integer ignored) {
            return "unmapped overload";
        }

        @Override
        @Read(path = "/new")
        public String changed() {
            return "changed";
        }

        @Override
        @GetMapping("/converted")
        public String convert(@RequestParam("value") String value) {
            return value;
        }

        @GetMapping("/private-controller")
        private String privateMethod() {
            return "controller";
        }

        public String unmapped() {
            return "unmapped";
        }
    }

    @RestController
    static class InheritedTypeController implements Contract<String> {
        @Override
        public String value() {
            return "value";
        }

        @Override
        public String changed() {
            return "changed";
        }
    }
}
