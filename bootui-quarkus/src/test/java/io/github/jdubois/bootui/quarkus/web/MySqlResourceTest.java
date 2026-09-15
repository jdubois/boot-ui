package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.MySqlInsightReport;
import io.github.jdubois.bootui.engine.mysql.MySqlInsightService;
import io.smallrye.common.annotation.Blocking;
import org.junit.jupiter.api.Test;

class MySqlResourceTest {

    @Test
    void cachedGetAndExplicitReadDelegateToTheSameEngineCache() {
        MySqlInsightService service = mock(MySqlInsightService.class);
        MySqlInsightReport cached = mock(MySqlInsightReport.class);
        MySqlInsightReport collected = mock(MySqlInsightReport.class);
        when(service.report()).thenReturn(cached);
        when(service.read()).thenReturn(collected);
        MySqlResource resource = new MySqlResource(service);

        assertThat(resource.mysql()).isSameAs(cached);
        verify(service).report();
        assertThat(resource.read()).isSameAs(collected);
        verify(service).read();
        verifyNoMoreInteractions(service);
    }

    @Test
    void onlyTheExternalReadRunsOnTheBlockingWorker() throws Exception {
        assertThat(MySqlResource.class.getMethod("read").isAnnotationPresent(Blocking.class))
                .isTrue();
        assertThat(MySqlResource.class.getMethod("mysql").isAnnotationPresent(Blocking.class))
                .isFalse();
    }
}
