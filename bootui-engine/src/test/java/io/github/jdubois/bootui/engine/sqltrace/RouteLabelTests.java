package io.github.jdubois.bootui.engine.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.MappingDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteLabelTests {

    private static final RouteTemplateResolver DECLARED = RouteTemplateResolver.of(List.of(
            mapping("GET", "/api/orders/{id}"),
            mapping("GET", "/api/orders/recent"),
            mapping("GET", "/api/items/{a}"),
            mapping("GET", "/api/items/{b}")));

    @Test
    void frameworkTemplateWinsOverDeclaredMappings() {
        RouteLabel label = RouteLabel.of("get", "/api/orders/42", "/api/orders/{orderId}", DECLARED);

        assertThat(label.source()).isEqualTo(RouteLabel.Source.FRAMEWORK_TEMPLATE);
        assertThat(label.route()).isEqualTo("/api/orders/{orderId}");
        assertThat(label.method()).isEqualTo("GET");
        assertThat(label.id()).isEqualTo("GET /api/orders/{orderId}");
    }

    @Test
    void declaredMappingLabelsAPathWithoutAFrameworkTemplate() {
        RouteLabel label = RouteLabel.of("GET", "/api/orders/42", null, DECLARED);

        assertThat(label.source()).isEqualTo(RouteLabel.Source.DECLARED_MAPPING);
        assertThat(label.route()).isEqualTo("/api/orders/{id}");
        assertThat(label.source().isTemplate()).isTrue();
    }

    @Test
    void theMostLiteralDeclarationWins() {
        assertThat(RouteLabel.of("GET", "/api/orders/recent", " ", DECLARED).route())
                .isEqualTo("/api/orders/recent");
    }

    @Test
    void ambiguousDeclarationsFallBackToAMaskedPath() {
        RouteLabel label = RouteLabel.of("GET", "/api/items/sku-7", null, DECLARED);

        assertThat(label.source()).isEqualTo(RouteLabel.Source.MASKED_PATH);
        assertThat(label.source().isTemplate()).isFalse();
        assertThat(label.route()).isEqualTo("/api/items/{value}");
    }

    @Test
    void ambiguousDeclarationsStillMaskAWordShapedValueInTheirParameterPosition() {
        RouteLabel label = RouteLabel.of("GET", "/api/items/alice", null, DECLARED);

        assertThat(label.source()).isEqualTo(RouteLabel.Source.MASKED_PATH);
        assertThat(label.route()).isEqualTo("/api/items/{value}");
        assertThat(label.id()).doesNotContain("alice");
    }

    @Test
    void aBraceDelimitedSegmentOnARealRequestIsAValueNotTemplateSyntax() {
        RouteLabel label = RouteLabel.of("GET", "/users/{alice}/tokens", null, RouteTemplateResolver.empty());

        assertThat(label.route()).isEqualTo("/users/{value}/tokens");
    }

    @Test
    void aFrameworkTemplateIsRenderedExactlyAsTheSameDeclaredTemplate() {
        RouteTemplateResolver constrained =
                RouteTemplateResolver.of(List.of(mapping("GET", "/api/orders/{id:[0-9]+}")));

        RouteLabel framework = RouteLabel.of("GET", "/api/orders/42", "/api/orders/{id:[0-9]+}", constrained);
        RouteLabel declared = RouteLabel.of("GET", "/api/orders/43", null, constrained);

        assertThat(framework.route()).isEqualTo("/api/orders/{id}");
        assertThat(framework.id()).isEqualTo(declared.id());
        assertThat(framework.source()).isEqualTo(RouteLabel.Source.FRAMEWORK_TEMPLATE);
        assertThat(declared.source()).isEqualTo(RouteLabel.Source.DECLARED_MAPPING);
        assertThat(RouteLabel.of("GET", "/css/site.css", "/**", DECLARED).route())
                .isEqualTo("/" + RoutePathMasker.PLACEHOLDER);
        assertThat(RouteLabel.of("GET", "/", "/", DECLARED).route()).isEqualTo("/");
    }

    @Test
    void maskedPathNeverExposesAPathParameterValueOrQueryString() {
        RouteLabel label = RouteLabel.of(
                "DELETE",
                "/users/alice@example.com/tokens/3f2b8c1e-0a4d-4e8b-9c55-1d2e3f4a5b6c?secret=hunter2",
                null,
                RouteTemplateResolver.empty());

        assertThat(label.route()).isEqualTo("/users/{value}/tokens/{value}");
        assertThat(label.id())
                .doesNotContain("alice")
                .doesNotContain("3f2b8c1e")
                .doesNotContain("hunter2")
                .doesNotContain("?");
    }

    @Test
    void blankMethodAndPathAreStillGrouped() {
        RouteLabel label = RouteLabel.of(null, null, null, null);

        assertThat(label.method()).isEqualTo(RouteLabel.UNKNOWN_METHOD);
        assertThat(label.route()).isEqualTo("/");
        assertThat(label.id()).isEqualTo("UNKNOWN /");
        assertThat(RouteLabel.idOf("post", "/x")).isEqualTo("POST /x");
    }

    private static MappingDto mapping(String method, String pattern) {
        return new MappingDto(method, pattern, "handler", null, null);
    }
}
