package io.github.jdubois.bootui.engine.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChangeImpactFixtureTests {

    @Test
    void aChangeToOneRepositoryReachesTheSevenBeansAndTheRequestsThePocFound() {
        RuntimeModel model = RuntimeModelProjectionTests.project(PocFixture.journal(), PocFixture.structure());
        int repository = model.node(NodeType.REPOSITORY, PocFixture.REPOSITORY)
                .orElseThrow()
                .id();

        Map<Integer, Integer> reached =
                ReverseClosure.of(model, repository, Set.of(EdgeType.DEPENDS_ON, EdgeType.HANDLED_BY), 5);

        assertThat(reached.keySet().stream().map(model::node).filter(node -> node.type() == NodeType.BEAN))
                .extracting(ModelNode::key)
                .containsExactlyInAnyOrder(
                        "ownerService",
                        "clinicService",
                        "reportService",
                        "ownerController",
                        "petController",
                        "visitController",
                        "reportController");
        long requests = reached.keySet().stream()
                .filter(id -> model.node(id).type() == NodeType.ROUTE)
                .mapToLong(model::executions)
                .sum();
        assertThat(requests).isEqualTo(571);
        assertThat(reached.keySet().stream().map(model::node).map(ModelNode::key))
                .doesNotContain("GET /vets", "vetController", "vetRepository");
        assertThat(reached.values()).allSatisfy(depth -> assertThat(depth).isBetween(1, 5));
    }
}
