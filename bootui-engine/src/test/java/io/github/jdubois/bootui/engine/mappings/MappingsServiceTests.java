package io.github.jdubois.bootui.engine.mappings;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.core.dto.MappingsReport;
import io.github.jdubois.bootui.spi.MappingProvider;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

class MappingsServiceTests {

    @Test
    void reportIsEmptyWhenProviderIsNull() {
        MappingsService service = new MappingsService(null);

        MappingsReport report = service.report(null, null, null);

        assertThat(report.total()).isZero();
        assertThat(report.mappings()).isEmpty();
        assertThat(report.page().total()).isZero();
        assertThat(report.page().hasMore()).isFalse();
    }

    @Test
    void reportIsEmptyWhenProviderUnavailable() {
        FakeMappingProvider provider = new FakeMappingProvider();
        provider.available = false;
        provider.mappings = List.of(new MappingDto("GET", "/a", "A#a", null, null));
        MappingsService service = new MappingsService(provider);

        MappingsReport report = service.report(null, null, null);

        assertThat(report.total()).isZero();
        assertThat(report.mappings()).isEmpty();
    }

    @Test
    void sortsByPatternThenMethodThenHandlerWithNullsLast() {
        FakeMappingProvider provider = new FakeMappingProvider();
        provider.mappings = List.of(
                new MappingDto("POST", "/beta", "Z#z", null, null),
                new MappingDto("GET", "/beta", "Z#z", null, null),
                new MappingDto("GET", "/alpha", "B#b", null, null),
                new MappingDto("GET", "/alpha", "A#a", null, null),
                new MappingDto("GET", null, "Null#n", null, null));
        MappingsService service = new MappingsService(provider);

        List<MappingDto> result = service.report(null, null, null).mappings();

        // pattern asc (nulls last), then method asc, then handler asc.
        assertThat(result)
                .extracting(MappingDto::pattern, MappingDto::method, MappingDto::handler)
                .containsExactly(
                        Tuple.tuple("/alpha", "GET", "A#a"),
                        Tuple.tuple("/alpha", "GET", "B#b"),
                        Tuple.tuple("/beta", "GET", "Z#z"),
                        Tuple.tuple("/beta", "POST", "Z#z"),
                        Tuple.tuple(null, "GET", "Null#n"));
    }

    @Test
    void queryMatchesAcrossMethodPatternHandlerProducesConsumes() {
        FakeMappingProvider provider = new FakeMappingProvider();
        provider.mappings = List.of(
                new MappingDto("GET", "/users", "UserController#list", "application/json", null),
                new MappingDto("POST", "/orders", "OrderController#create", null, "text/xml"),
                new MappingDto("DELETE", "/files", "FileController#remove", null, null));
        MappingsService service = new MappingsService(provider);

        // "xml" only appears in the consumes field of the /orders row.
        MappingsReport byConsumes = service.report("xml", null, null);
        assertThat(byConsumes.total()).isEqualTo(3);
        assertThat(byConsumes.mappings()).extracting(MappingDto::pattern).containsExactly("/orders");

        // "json" only appears in the produces field of the /users row.
        assertThat(service.report("json", null, null).mappings())
                .extracting(MappingDto::pattern)
                .containsExactly("/users");

        // Handler substring match.
        assertThat(service.report("FileController", null, null).mappings())
                .extracting(MappingDto::pattern)
                .containsExactly("/files");
    }

    @Test
    void totalReflectsFullMappingCountWhilePagingLimitsReturned() {
        FakeMappingProvider provider = new FakeMappingProvider();
        provider.mappings = List.of(
                new MappingDto("GET", "/a", "A#a", null, null),
                new MappingDto("GET", "/b", "B#b", null, null),
                new MappingDto("GET", "/c", "C#c", null, null));
        MappingsService service = new MappingsService(provider);

        MappingsReport report = service.report(null, 1, 1);

        // total is the full (filtered) mapping count, independent of the page window.
        assertThat(report.total()).isEqualTo(3);
        assertThat(report.page().total()).isEqualTo(3);
        assertThat(report.page().matched()).isEqualTo(3);
        assertThat(report.page().offset()).isEqualTo(1);
        assertThat(report.page().limit()).isEqualTo(1);
        assertThat(report.page().returned()).isEqualTo(1);
        assertThat(report.page().hasMore()).isTrue();
        // offset 1 into the sorted list (/a, /b, /c) -> /b.
        assertThat(report.mappings()).extracting(MappingDto::pattern).containsExactly("/b");
    }

    private static final class FakeMappingProvider implements MappingProvider {

        private boolean available = true;

        private List<MappingDto> mappings = List.of();

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public List<MappingDto> mappings() {
            return mappings;
        }
    }
}
