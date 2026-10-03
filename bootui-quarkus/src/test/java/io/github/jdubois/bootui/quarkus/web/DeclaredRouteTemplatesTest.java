package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.spi.MappingProvider;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeclaredRouteTemplatesTest {

    private static final List<MappingDto> DECLARED = List.of(
            new MappingDto("GET", "/users/{name}", "UserResource#get", null, null),
            new MappingDto("GET", "/", "RootResource#index", null, null));

    @Test
    void mountsDeclaredPatternsUnderTheRootAndRestPathsRequestsArriveOn() {
        RouteTemplateResolver resolver = RouteTemplateResolver.of(DeclaredRouteTemplates.mounted(DECLARED, "/app/api"));

        RouteLabel label = RouteLabel.of("GET", "/app/api/users/alice", null, resolver);

        assertThat(label.source()).isEqualTo(RouteLabel.Source.DECLARED_MAPPING);
        assertThat(label.route()).isEqualTo("/app/api/users/{name}");
        assertThat(label.id()).doesNotContain("alice");
        assertThat(resolver.resolve("/app/api")).isEqualTo("/app/api");
    }

    @Test
    void leavesPatternsAloneWithoutAMountPrefix() {
        assertThat(DeclaredRouteTemplates.mounted(DECLARED, "")).isSameAs(DECLARED);
        assertThat(DeclaredRouteTemplates.mounted(null, "/app")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anInventoryThatCannotBeReadIsNullRatherThanEmpty() {
        Instance<MappingProvider> absent = mock(Instance.class);
        when(absent.isResolvable()).thenReturn(false);
        MappingProvider unavailable = mock(MappingProvider.class);
        when(unavailable.available()).thenReturn(false);
        Instance<MappingProvider> unavailableInstance = mock(Instance.class);
        when(unavailableInstance.isResolvable()).thenReturn(true);
        when(unavailableInstance.get()).thenReturn(unavailable);

        assertThat(DeclaredRouteTemplates.declared(null).get()).isNull();
        assertThat(DeclaredRouteTemplates.declared(absent).get()).isNull();
        assertThat(DeclaredRouteTemplates.declared(unavailableInstance).get()).isNull();
    }

    @Test
    void normalizesMountPathsLikeQuarkusDoes() {
        assertThat(DeclaredRouteTemplates.normalize(null)).isEmpty();
        assertThat(DeclaredRouteTemplates.normalize("/")).isEmpty();
        assertThat(DeclaredRouteTemplates.normalize("app/")).isEqualTo("/app");
        assertThat(DeclaredRouteTemplates.normalize(" /api// ")).isEqualTo("/api");
    }
}
