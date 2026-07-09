package io.github.jdubois.bootui.engine.beans;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.BeanList;
import io.github.jdubois.bootui.core.dto.BeanGraphReport;
import io.github.jdubois.bootui.core.dto.BeanSummary;
import io.github.jdubois.bootui.spi.BeanProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class BeansServiceTests {

    private static BeanSummary bean(String name, String type, String classification) {
        return new BeanSummary(name, type, "singleton", null, List.of(), List.of(), classification);
    }

    private static BeanSummary bean(String name, String... dependencies) {
        return new BeanSummary(
                name, "com.example." + name, "singleton", null, List.of(dependencies), List.of(), "APPLICATION");
    }

    @Test
    void beansAreEmptyWhenProviderIsNull() {
        BeansService service = new BeansService(null);

        BeanList list = service.beans(null, null, null, null);

        assertThat(list.total()).isZero();
        assertThat(list.beans()).isEmpty();
        assertThat(list.page().total()).isZero();
        assertThat(list.page().hasMore()).isFalse();
    }

    @Test
    void beansAreEmptyWhenProviderUnavailable() {
        FakeBeanProvider provider = new FakeBeanProvider();
        provider.available = false;
        provider.beans = List.of(bean("a", "A", "APPLICATION"));
        BeansService service = new BeansService(provider);

        BeanList list = service.beans(null, null, null, null);

        assertThat(list.total()).isZero();
        assertThat(list.beans()).isEmpty();
    }

    @Test
    void sortsByName() {
        FakeBeanProvider provider = new FakeBeanProvider();
        provider.beans = List.of(bean("zebra", "Z", "APPLICATION"), bean("apple", "A", "APPLICATION"));
        BeansService service = new BeansService(provider);

        BeanList list = service.beans(null, null, null, null);

        assertThat(list.beans()).extracting(BeanSummary::name).containsExactly("apple", "zebra");
        assertThat(list.total()).isEqualTo(2);
    }

    @Test
    void filtersByClassificationCaseInsensitively() {
        FakeBeanProvider provider = new FakeBeanProvider();
        provider.beans = List.of(
                bean("alpha", "java.lang.String", "PLATFORM"),
                bean("beta", "com.example.Foo", "APPLICATION"),
                bean("gamma", "java.util.List", "PLATFORM"));
        BeansService service = new BeansService(provider);

        BeanList list = service.beans(null, "platform", null, null);

        assertThat(list.total()).isEqualTo(3);
        assertThat(list.beans()).extracting(BeanSummary::name).containsExactly("alpha", "gamma");
        assertThat(list.page().matched()).isEqualTo(2);
    }

    @Test
    void filtersByQueryAcrossNameAndType() {
        FakeBeanProvider provider = new FakeBeanProvider();
        provider.beans = List.of(
                bean("alpha", "com.example.Widget", "APPLICATION"),
                bean("widgetBean", "com.example.Other", "APPLICATION"),
                bean("zeta", "com.example.Thing", "APPLICATION"));
        BeansService service = new BeansService(provider);

        BeanList list = service.beans("widget", null, null, null);

        assertThat(list.beans()).extracting(BeanSummary::name).containsExactly("alpha", "widgetBean");
    }

    @Test
    void appliesServerSidePaging() {
        FakeBeanProvider provider = new FakeBeanProvider();
        provider.beans = List.of(
                bean("a", "com.example.A", "APPLICATION"),
                bean("b", "com.example.B", "APPLICATION"),
                bean("c", "com.example.C", "APPLICATION"));
        BeansService service = new BeansService(provider);

        BeanList list = service.beans(null, null, 1, 1);

        assertThat(list.total()).isEqualTo(3);
        assertThat(list.beans()).extracting(BeanSummary::name).containsExactly("b");
        assertThat(list.page().total()).isEqualTo(3);
        assertThat(list.page().matched()).isEqualTo(3);
        assertThat(list.page().offset()).isEqualTo(1);
        assertThat(list.page().returned()).isEqualTo(1);
        assertThat(list.page().hasMore()).isTrue();
    }

    @Test
    void graphIsUnavailableWithoutAProviderAndEmptyForUnknownFocus() {
        assertThat(new BeansService(null).graph("alpha", null).available()).isFalse();

        FakeBeanProvider provider = new FakeBeanProvider();
        provider.beans = List.of(bean("alpha"));
        BeanGraphReport report = new BeansService(provider).graph("missing", null);

        assertThat(report.available()).isTrue();
        assertThat(report.focus()).isNull();
        assertThat(report.edges()).isEmpty();
    }

    @Test
    void buildsDeterministicDirectDependenciesAndReverseDependents() {
        FakeBeanProvider provider = new FakeBeanProvider();
        provider.beans = List.of(
                bean("zDependent", "focus"),
                bean("focus", "missing", "beta", "alpha", "alpha", "focus"),
                bean("beta"),
                bean("aDependent", "focus"),
                bean("alpha"));

        BeanGraphReport report = new BeansService(provider).graph("focus", 10);

        assertThat(report.dependencies()).extracting(BeanSummary::name).containsExactly("alpha", "beta");
        assertThat(report.dependents()).extracting(BeanSummary::name).containsExactly("aDependent", "zDependent");
        assertThat(report.edges())
                .extracting(edge -> edge.source() + "->" + edge.target())
                .containsExactly("alpha->focus", "beta->focus", "focus->aDependent", "focus->zDependent");
        assertThat(report.unresolvedDependencies()).containsExactly("missing");
    }

    @Test
    void boundsEachGraphSideAndReportsHiddenCountsAcrossCycles() {
        FakeBeanProvider provider = new FakeBeanProvider();
        provider.beans = List.of(
                bean("focus", "a", "b"),
                bean("a", "focus"),
                bean("b"),
                bean("c", "focus"));

        BeanGraphReport report = new BeansService(provider).graph("focus", 1);

        assertThat(report.dependencies()).extracting(BeanSummary::name).containsExactly("a");
        assertThat(report.dependents()).extracting(BeanSummary::name).containsExactly("a");
        assertThat(report.hiddenDependencies()).isEqualTo(1);
        assertThat(report.hiddenDependents()).isEqualTo(1);
    }

    private static final class FakeBeanProvider implements BeanProvider {

        private boolean available = true;

        private List<BeanSummary> beans = List.of();

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public List<BeanSummary> beans() {
            return beans;
        }
    }
}
