package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.memory.MemoryScanner;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MemoryResourceTests {

    @Test
    void histogramSelectionIsForwardedAndDefaultToolEntryPointStaysArgumentFree() {
        MemoryScanner scanner = mock(MemoryScanner.class);
        DismissedRulesStore dismissals = mock(DismissedRulesStore.class);
        when(dismissals.load()).thenReturn(Set.of());
        MemoryResource resource = new MemoryResource(scanner, dismissals);
        resource.scan(query(List.of("false")));
        resource.scan(query(List.of("true")));
        resource.scan(query(null));
        verify(scanner).scan(false);
        verify(scanner).scan(true);
        verify(scanner).scan();
    }

    @Test
    void malformedSelectionCannotExecuteADiagnostic() {
        MemoryScanner scanner = mock(MemoryScanner.class);
        DismissedRulesStore dismissals = mock(DismissedRulesStore.class);
        MemoryResource resource = new MemoryResource(scanner, dismissals);
        for (String invalid : new String[] {"", "invalid", "0", "1", "TRUE", " false "}) {
            assertThatThrownBy(() -> resource.scan(query(List.of(invalid))))
                    .isInstanceOfSatisfying(WebApplicationException.class, failure -> {
                        assertThat(failure.getResponse().getStatus()).isEqualTo(400);
                        assertThat(failure.getResponse().getEntity())
                                .isEqualTo(Map.of("message", "includeHistogram must be true or false."));
                    });
        }
        verifyNoInteractions(scanner, dismissals);
    }

    @Test
    void repeatedOrPresentWithoutValuesCannotFallBackToTheConfiguredDefault() {
        MemoryScanner scanner = mock(MemoryScanner.class);
        DismissedRulesStore dismissals = mock(DismissedRulesStore.class);
        MemoryResource resource = new MemoryResource(scanner, dismissals);
        for (List<String> values : List.of(
                List.<String>of(),
                java.util.Collections.<String>singletonList(null),
                List.of("false", "true"),
                List.of("true", "false"),
                List.of("false", "false"),
                List.of("false", ""),
                List.of("", "false"))) {
            assertThatThrownBy(() -> resource.scan(query(values)))
                    .isInstanceOfSatisfying(
                            WebApplicationException.class,
                            failure -> assertThat(failure.getResponse().getStatus())
                                    .isEqualTo(400));
        }
        verifyNoInteractions(scanner, dismissals);
    }

    private static UriInfo query(List<String> values) {
        var parameters = new MultivaluedHashMap<String, String>();
        if (values != null) parameters.put("includeHistogram", values);
        UriInfo uri = mock(UriInfo.class);
        when(uri.getQueryParameters()).thenReturn(parameters);
        return uri;
    }
}
