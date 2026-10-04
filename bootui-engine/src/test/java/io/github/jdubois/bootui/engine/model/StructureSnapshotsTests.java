package io.github.jdubois.bootui.engine.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.spi.MappingProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructureSnapshotsTests {

    @Test
    void eachRouteKeepsItsHandlerClassAndMethodAsSpringAndQuarkusDescribeThem() {
        MappingProvider mappings = new MappingProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public List<MappingDto> mappings() {
                return List.of(
                        new MappingDto(
                                "GET", "/api/products", "com.example.ProductController#list(Pageable)", null, null),
                        new MappingDto("GET", "/api/products/{id}", "com.example.ProductResource#get", null, null),
                        new MappingDto("POST", "/api/products", null, null, null),
                        new MappingDto("GET", " ", "com.example.Ignored#blank", null, null));
            }
        };

        StructureSnapshot structure = StructureSnapshots.read("run-1", null, mappings);

        assertThat(structure.routes())
                .extracting(
                        StructureSnapshot.RouteHandler::route,
                        StructureSnapshot.RouteHandler::handlerClass,
                        StructureSnapshot.RouteHandler::handlerMethod)
                .containsExactly(
                        tuple("GET /api/products", "com.example.ProductController", "list"),
                        tuple("GET /api/products/{id}", "com.example.ProductResource", "get"),
                        tuple("POST /api/products", null, null));
        assertThat(structure.beansUnavailable()).isNotNull();
    }

    @Test
    void aHandlerMethodIsItsNameWithoutParameterTypesAndUnknownWhenTheHandlerNamesNone() {
        assertThat(StructureSnapshots.handlerMethod("com.example.ProductController#list(Pageable, String)"))
                .isEqualTo("list");
        assertThat(StructureSnapshots.handlerMethod("com.example.ProductController#list()"))
                .isEqualTo("list");
        assertThat(StructureSnapshots.handlerMethod("com.example.ProductResource#get"))
                .isEqualTo("get");
        assertThat(StructureSnapshots.handlerMethod("com.example.ProductController"))
                .isNull();
        assertThat(StructureSnapshots.handlerMethod("com.example.ProductController#()"))
                .isNull();
        assertThat(StructureSnapshots.handlerMethod(null)).isNull();
        assertThat(new StructureSnapshot.RouteHandler("GET /a", "com.example.A").handlerMethod())
                .isNull();
    }
}
