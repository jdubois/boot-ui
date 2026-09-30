package io.github.jdubois.bootui.engine.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.MappingDto;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteTemplateResolverTests {

    @Test
    void resolvesAPathParameterValueBackToItsDeclaredTemplate() {
        RouteTemplateResolver resolver = resolver("/api/orders/{id}", "/api/orders");

        assertThat(resolver.resolve("/api/orders/4711")).isEqualTo("/api/orders/{id}");
        assertThat(resolver.resolve("/api/orders")).isEqualTo("/api/orders");
    }

    @Test
    void aLazyResolverReadsTheMappingsOnlyWhenAPathNeedsResolvingAndOnlyOnce() {
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        RouteTemplateResolver resolver = RouteTemplateResolver.lazy(() -> {
            reads.incrementAndGet();
            return List.of(new MappingDto("GET", "/api/orders/{id}", "handler", null, null));
        });

        assertThat(RouteLabel.of("GET", "/api/orders/1", "/api/orders/{id}", resolver)
                        .source())
                .isEqualTo(RouteLabel.Source.FRAMEWORK_TEMPLATE);
        assertThat(reads).hasValue(0);

        assertThat(resolver.resolve("/api/orders/1")).isEqualTo("/api/orders/{id}");
        assertThat(resolver.resolve("/api/orders/2")).isEqualTo("/api/orders/{id}");
        assertThat(resolver.isEmpty()).isFalse();
        assertThat(reads).hasValue(1);
    }

    @Test
    void aLazyResolverWhoseMappingsFailResolvesNothing() {
        RouteTemplateResolver resolver = RouteTemplateResolver.lazy(() -> {
            throw new IllegalStateException("mappings endpoint unavailable");
        });

        assertThat(resolver.resolve("/api/orders/1")).isNull();
        assertThat(resolver.isEmpty()).isTrue();
    }

    @Test
    void resolvesAWordShapedPathParameterThatMaskingAloneWouldKeepVerbatim() {
        RouteTemplateResolver resolver = resolver("/api/users/{name}", "/api/users");

        assertThat(resolver.resolve("/api/users/alice")).isEqualTo("/api/users/{name}");
        assertThat(resolver.resolve("/api/users/alice")).doesNotContain("alice");
    }

    @Test
    void prefersTheMoreLiteralTemplateWhenTwoDeclarationsMatch() {
        RouteTemplateResolver resolver = resolver("/api/orders/{id}", "/api/orders/latest");

        assertThat(resolver.resolve("/api/orders/latest")).isEqualTo("/api/orders/latest");
        assertThat(resolver.resolve("/api/orders/9")).isEqualTo("/api/orders/{id}");
    }

    @Test
    void refusesToChooseBetweenTwoEquallyPlausibleTemplates() {
        RouteTemplateResolver resolver = resolver("/api/{kind}/list", "/api/orders/{action}");

        assertThat(resolver.resolve("/api/orders/list")).isNull();
    }

    @Test
    void doesNotMatchAPathWithADifferentSegmentCount() {
        RouteTemplateResolver resolver = resolver("/api/orders/{id}");

        assertThat(resolver.resolve("/api/orders")).isNull();
        assertThat(resolver.resolve("/api/orders/4711/items")).isNull();
    }

    @Test
    void rendersARegularExpressionConstraintAsAPlainPlaceholder() {
        RouteTemplateResolver resolver = resolver("/api/orders/{id:[0-9]+}");

        assertThat(resolver.resolve("/api/orders/12")).isEqualTo("/api/orders/{id}");
    }

    @Test
    void ignoresAQueryStringOnTheCapturedPath() {
        RouteTemplateResolver resolver = resolver("/api/orders/{id}");

        assertThat(resolver.resolve("/api/orders/7?token=secret")).isEqualTo("/api/orders/{id}");
    }

    @Test
    void isEmptyWithoutDeclaredMappings() {
        assertThat(RouteTemplateResolver.empty().isEmpty()).isTrue();
        assertThat(RouteTemplateResolver.of(null).resolve("/api/orders/1")).isNull();
        assertThat(RouteTemplateResolver.of(List.of()).isEmpty()).isTrue();
    }

    @Test
    void boundsTheNumberOfIndexedTemplates() {
        List<MappingDto> mappings = new ArrayList<>();
        for (int i = 0; i < RouteTemplateResolver.MAX_TEMPLATES + 500; i++) {
            mappings.add(new MappingDto("GET", "/api/route" + i + "/{id}", "Handler", null, null));
        }

        RouteTemplateResolver resolver = RouteTemplateResolver.of(mappings);

        assertThat(resolver.resolve("/api/route0/1")).isEqualTo("/api/route0/{id}");
        assertThat(resolver.resolve("/api/route" + (RouteTemplateResolver.MAX_TEMPLATES + 100) + "/1"))
                .isNull();
    }

    @Test
    void toleratesBlankAndNullPatterns() {
        RouteTemplateResolver resolver = RouteTemplateResolver.of(java.util.Arrays.asList(
                null,
                new MappingDto("GET", "  ", "Handler", null, null),
                new MappingDto("GET", "/a/{b}", "H", null, null)));

        assertThat(resolver.resolve("/a/1")).isEqualTo("/a/{b}");
    }

    private static RouteTemplateResolver resolver(String... patterns) {
        List<MappingDto> mappings = new ArrayList<>();
        for (String pattern : patterns) {
            mappings.add(new MappingDto("GET", pattern, "Handler", null, null));
        }
        return RouteTemplateResolver.of(mappings);
    }
}
