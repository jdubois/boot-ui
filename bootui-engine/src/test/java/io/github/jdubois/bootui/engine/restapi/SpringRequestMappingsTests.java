package io.github.jdubois.bootui.engine.restapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AliasFor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

class SpringRequestMappingsTests {
    @Test
    void discoversUnstereotypedAncestryWithoutDuplicatingOverridesBridgesOrOverloads() {
        RestApiHandlerModelBuilder model = model(Controller.class);

        assertThat(model.incomplete()).isFalse();
        assertThat(model.controllers())
                .singleElement()
                .satisfies(controller -> assertThat(controller.handlerCount()).isEqualTo(6));
        assertThat(model.handlers())
                .extracting(RestApiModel.HandlerMethodModel::methodName)
                .containsExactlyInAnyOrder("item", "defaultItem", "base", "replaced", "overridden", "generic");
        assertThat(handler(model, "item").effectivePaths()).containsExactly("/v1/items/{id}", "/v2/items/{id}");
        assertThat(handler(model, "item").pathVariableNames()).containsExactly("id");
        assertThat(handler(model, "item").bodyTypeName()).isEqualTo(Payload.class.getName());
        assertThat(handler(model, "replaced").effectivePaths()).containsExactly("/v1/replacement", "/v2/replacement");
        assertThat(handler(model, "overridden").effectivePaths()).containsExactly("/v1/new", "/v2/new");
        assertThat(handler(model, "generic").bodyTypeName()).isEqualTo(Payload.class.getName());
        assertThat(handler(model, "base").effectiveProduces()).containsExactly("application/json");
    }

    @Test
    void composesAliasesAcrossMultipleAnnotationLevelsAndPreservesMappingConditions() {
        RestApiHandlerModelBuilder model = model(ComposedController.class);

        assertThat(model.incomplete()).isFalse();
        RestApiModel.HandlerMethodModel handler = handler(model, "read");
        assertThat(handler.effectivePaths()).containsExactly("/nested/records");
        assertThat(handler.httpMethods()).containsExactly("GET");
        assertThat(handler.effectiveProduces()).containsExactly("text/plain");
        assertThat(handler.params()).containsExactly("tenant");
        assertThat(handler.headers()).containsExactly("X-Api");
    }

    @Test
    void inheritsInterfaceTypeMappingsButConcreteMappingsTakePrecedence() {
        RestApiHandlerModelBuilder inherited = model(InterfaceController.class);
        assertThat(inherited.incomplete()).isFalse();
        assertThat(handler(inherited, "item").effectivePaths()).containsExactly("/contract/items/{id}");
        assertThat(handler(model(Controller.class), "item").effectivePaths()).doesNotContain("/contract/items/{id}");
    }

    @Test
    void recordsUnsupportedGenericParameterMergingRatherThanClaimingAnEmptyCompleteModel() {
        RestApiHandlerModelBuilder model = model(GenericBodyController.class);
        assertThat(model.incomplete()).isTrue();
        assertThat(model.controllers())
                .singleElement()
                .satisfies(controller -> assertThat(controller.handlerCount()).isZero());
        var scanner = new RestApiScanner(
                () -> List.of(getClass().getPackageName()),
                packages -> new ClassFileImporter().importClasses(GenericBodyController.class),
                () -> false,
                () -> false,
                Clock.systemUTC());
        var report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.evidence().limitations()).isNotEmpty();
    }

    @Test
    void genericParameterBridgesDispatchToTheMappedConcreteOverloadOnly() {
        RestApiHandlerModelBuilder model = model(BridgedController.class);
        assertThat(model.incomplete()).isFalse();
        assertThat(model.handlers()).singleElement().satisfies(handler -> {
            assertThat(handler.effectivePaths()).containsExactly("/own-body");
            assertThat(handler.hasRequestBody()).isTrue();
            assertThat(handler.bodyTypeName()).isEqualTo(Payload.class.getName());
        });
    }

    @Test
    void unresolvedInheritedSignaturesAndMixedParameterAnnotationsRemainIncomplete() {
        assertThat(model(InheritedGenericController.class).incomplete()).isTrue();
        assertThat(model(MixedParameterController.class).incomplete()).isTrue();
    }

    @Test
    void privateSuperclassMethodsAreNotOverridesOfSameSignaturePrivateControllerMethods() {
        RestApiHandlerModelBuilder model = model(PrivateController.class);
        assertThat(model.incomplete()).isFalse();
        assertThat(model.handlers())
                .extracting(RestApiModel.HandlerMethodModel::effectivePaths)
                .containsExactlyInAnyOrder(List.of("/private-base"), List.of("/private-controller"));
    }

    @Test
    void cyclicMetaAnnotationsTerminateAndExcessiveAncestryIsExplicitlyIncomplete() {
        assertThat(model(CyclicController.class).incomplete()).isFalse();
        JavaClass deep = new ClassFileImporter().importClass(Deep10.class);
        assertThatThrownBy(() -> SpringRequestMappings.handlers(deep)).isInstanceOf(IllegalStateException.class);
        assertThat(model(Deep10.class).incomplete()).isTrue();
    }

    private static RestApiHandlerModelBuilder model(Class<?> controller) {
        return RestApiHandlerModelBuilder.build(new ClassFileImporter().importClasses(controller));
    }

    private static RestApiModel.HandlerMethodModel handler(RestApiHandlerModelBuilder model, String method) {
        return model.handlers().stream()
                .filter(handler -> handler.methodName().equals(method))
                .findFirst()
                .orElseThrow();
    }

    record Payload(String name) {}

    @RequestMapping("/contract")
    interface Contract {
        @GetMapping("/items/{id}")
        Object item(@PathVariable("id") String id);

        @GetMapping("/default")
        default Payload defaultItem() {
            return new Payload("default");
        }

        @GetMapping("/old-interface")
        Payload overridden();
    }

    interface Generic<T> {
        @GetMapping("/generic")
        T generic();
    }

    static class Base {
        @GetMapping("/base")
        public Payload base() {
            return new Payload("base");
        }

        @GetMapping("/old-base")
        public Payload replaced() {
            return new Payload("old");
        }
    }

    @RestController
    @ApiPrefix(routes = {"/v1", "/v2"})
    static class Controller extends Base implements Contract, Generic<Payload> {
        @Override
        public Payload item(String id) {
            return new Payload(id);
        }

        public Payload item(Integer id) {
            return new Payload(id.toString());
        }

        @Override
        @GetMapping("/new")
        public Payload overridden() {
            return new Payload("new");
        }

        @Override
        @GetMapping("/replacement")
        public Payload replaced() {
            return new Payload("replacement");
        }

        @Override
        public Payload generic() {
            return new Payload("generic");
        }

        public Payload unmapped() {
            return new Payload("unmapped");
        }
    }

    @RestController
    static class InterfaceController implements Contract {
        @Override
        public Payload item(String id) {
            return new Payload(id);
        }

        @Override
        public Payload overridden() {
            return new Payload("override");
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
    @RequestMapping(produces = "application/json", params = "tenant", headers = "X-Api")
    @interface ApiPrefix {
        @AliasFor(annotation = RequestMapping.class, attribute = "path")
        String[] routes() default {};
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @ApiPrefix
    @interface NestedPrefix {
        @AliasFor(annotation = ApiPrefix.class, attribute = "routes")
        String[] value() default "/nested";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @RequestMapping(method = RequestMethod.GET, produces = "text/plain")
    @interface Read {
        @AliasFor(annotation = RequestMapping.class, attribute = "path")
        String[] value() default {};

        @AliasFor(annotation = RequestMapping.class, attribute = "path")
        String[] path() default {};
    }

    @RestController
    @NestedPrefix
    static class ComposedController {
        @Read("/records")
        public String read() {
            return "records";
        }
    }

    interface GenericBody<T> {
        @PostMapping("/body")
        T echo(@RequestBody T body);
    }

    @RestController
    static class GenericBodyController implements GenericBody<Payload> {
        @Override
        public Payload echo(Payload body) {
            return body;
        }
    }

    @RestController
    static class BridgedController implements GenericBody<Payload> {
        @Override
        @PostMapping("/own-body")
        public Payload echo(@RequestBody Payload body) {
            return body;
        }

        public Payload echo(String unmappedOverload) {
            return new Payload(unmappedOverload);
        }
    }

    static class GenericBase<T> {
        @GetMapping("/generic-base")
        public T inheritedGeneric() {
            return null;
        }
    }

    @RestController
    static class InheritedGenericController extends GenericBase<Payload> {}

    interface BodyContract {
        @PostMapping("/mixed")
        Payload mixed(@RequestBody Payload payload);
    }

    @RestController
    static class MixedParameterController implements BodyContract {
        @Override
        public Payload mixed(@Validated Payload payload) {
            return payload;
        }
    }

    static class PrivateBase {
        @GetMapping("/private-base")
        private String privateMethod() {
            return "base";
        }
    }

    @RestController
    static class PrivateController extends PrivateBase {
        @GetMapping("/private-controller")
        private String privateMethod() {
            return "controller";
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
    @CycleB
    @interface CycleA {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.ANNOTATION_TYPE)
    @CycleA
    @interface CycleB {}

    @RestController
    @CycleA
    static class CyclicController {}

    static class Deep0 {}

    static class Deep1 extends Deep0 {}

    static class Deep2 extends Deep1 {}

    static class Deep3 extends Deep2 {}

    static class Deep4 extends Deep3 {}

    static class Deep5 extends Deep4 {}

    static class Deep6 extends Deep5 {}

    static class Deep7 extends Deep6 {}

    static class Deep8 extends Deep7 {}

    static class Deep9 extends Deep8 {}

    @RestController
    static class Deep10 extends Deep9 {}
}
