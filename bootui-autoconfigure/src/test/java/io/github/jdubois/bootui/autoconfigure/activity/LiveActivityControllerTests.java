package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionStore;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionsController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.autoconfigure.web.HealthController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.SecurityLogsController;
import io.github.jdubois.bootui.autoconfigure.web.TracesController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class LiveActivityControllerTests {

    @Test
    void streamOpensAnSseEmitter() {
        assertThat(controller(new BootUiProperties()).stream()).isNotNull();
    }

    @Test
    void hostRequestExcludesBootUiOwnTrafficToAvoidRefreshLoop() {
        // BootUI's own re-fetches and SSE connection must not re-trigger the feed.
        assertThat(LiveActivityController.isHostRequest("/bootui/api/activity", "/bootui"))
                .isFalse();
        assertThat(LiveActivityController.isHostRequest("/bootui/api/activity/stream", "/bootui"))
                .isFalse();
        // Honors a custom base path.
        assertThat(LiveActivityController.isHostRequest("/app/console/api/activity", "/app/console"))
                .isFalse();
    }

    @Test
    void hostRequestSignalsForApplicationTrafficAndNullUrls() {
        assertThat(LiveActivityController.isHostRequest("/api/sample/products", "/bootui"))
                .isTrue();
        assertThat(LiveActivityController.isHostRequest(null, "/bootui")).isTrue();
    }

    private static LiveActivityController controller(BootUiProperties properties) {
        return new LiveActivityController(
                empty(HttpExchangesController.class),
                empty(SqlTraceController.class),
                empty(ExceptionsController.class),
                empty(SecurityLogsController.class),
                empty(TracesController.class),
                empty(HealthController.class),
                empty(SqlTraceRecorder.class),
                empty(ExceptionStore.class),
                empty(RequestCorrelationRegistry.class),
                properties);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> empty(Class<T> type) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
