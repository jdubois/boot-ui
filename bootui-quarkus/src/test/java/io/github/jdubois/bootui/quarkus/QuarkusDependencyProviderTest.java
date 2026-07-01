package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DependencyDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins how {@link QuarkusDependencyProvider} parses the build-time-captured
 * {@code bootui.internal.dependencies} config default into the local dependency inventory: coordinate
 * splitting, de-duplication, ordering, fail-soft on a missing/blank key, and skipping of malformed entries.
 * It needs no Quarkus runtime — the provider is a pure function of the config string (driven here by
 * {@link StubConfig}).
 */
class QuarkusDependencyProviderTest {

    private List<DependencyDto> dependencies(String raw) {
        return new QuarkusDependencyProvider(new StubConfig(Map.of(QuarkusDependencyProvider.DEPENDENCIES_KEY, raw)))
                .dependencies();
    }

    @Test
    void parsesCoordinatesIntoDependencyDtos() {
        List<DependencyDto> dependencies = dependencies("org.acme:widget:1.2.3,io.quarkus:quarkus-arc:3.20.0");

        assertThat(dependencies).hasSize(2);
        DependencyDto widget = dependencies.stream()
                .filter(d -> d.artifactId().equals("widget"))
                .findFirst()
                .orElseThrow();
        assertThat(widget.groupId()).isEqualTo("org.acme");
        assertThat(widget.artifactId()).isEqualTo("widget");
        assertThat(widget.version()).isEqualTo("1.2.3");
        assertThat(widget.packageName()).isEqualTo("org.acme:widget");
        assertThat(widget.source()).isEqualTo("Quarkus application model");
        assertThat(widget.vulnerabilityCount()).isZero();
        assertThat(widget.highestSeverity()).isEqualTo("NONE");
        assertThat(widget.vulnerabilities()).isEmpty();
    }

    @Test
    void sortsByPackageNameThenVersion() {
        List<DependencyDto> dependencies =
                dependencies("org.zed:alpha:2.0.0,org.acme:widget:1.0.0,org.acme:widget:1.0.1");

        assertThat(dependencies)
                .extracting(d -> d.packageName() + ":" + d.version())
                .containsExactly("org.acme:widget:1.0.0", "org.acme:widget:1.0.1", "org.zed:alpha:2.0.0");
    }

    @Test
    void deduplicatesRepeatedCoordinates() {
        List<DependencyDto> dependencies = dependencies("org.acme:widget:1.0.0,org.acme:widget:1.0.0");

        assertThat(dependencies).hasSize(1);
    }

    @Test
    void preservesColonsInTheVersionRemainder() {
        // A version containing a ':' must be kept intact: only the first two ':' delimit group/artifact.
        List<DependencyDto> dependencies = dependencies("org.acme:widget:1.0.0:redhat-00001");

        assertThat(dependencies).hasSize(1);
        assertThat(dependencies.get(0).version()).isEqualTo("1.0.0:redhat-00001");
    }

    @Test
    void skipsMalformedEntriesWithoutFailing() {
        List<DependencyDto> dependencies = dependencies(
                "not-a-coordinate,org.acme:missing-version:,:bad:1.0.0," + "  ," + "org.acme:widget:1.0.0");

        assertThat(dependencies).extracting(DependencyDto::packageName).containsExactly("org.acme:widget");
    }

    @Test
    void returnsEmptyWhenTheKeyIsAbsent() {
        assertThat(new QuarkusDependencyProvider(StubConfig.empty()).dependencies())
                .isEmpty();
    }

    @Test
    void returnsEmptyWhenTheKeyIsBlank() {
        assertThat(dependencies("   ")).isEmpty();
    }
}
