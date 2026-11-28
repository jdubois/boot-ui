package io.github.jdubois.bootui.quarkus4.beans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuarkusBeanDependenciesTest {

    @Test
    void roundTripsAStableSortedGraph() {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        graph.put("service", List.of("repository", "client", "repository"));
        graph.put("controller", List.of("service"));

        assertThat(QuarkusBeanDependencies.decode(QuarkusBeanDependencies.encode(graph)))
                .containsExactly(
                        Map.entry("controller", List.of("service")),
                        Map.entry("service", List.of("client", "repository")));
    }

    @Test
    void rejectsInvalidResources() {
        assertThatThrownBy(() -> QuarkusBeanDependencies.decode(new byte[] {0, 0, 0, 0, 0, 0, 0, 1}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("header");
    }
}
