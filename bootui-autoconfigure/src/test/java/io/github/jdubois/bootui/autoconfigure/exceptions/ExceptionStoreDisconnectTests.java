package io.github.jdubois.bootui.autoconfigure.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * Pins the Spring adapter's client-disconnect filter: the engine store is constructed with
 * {@code DisconnectedClientHelper::isClientDisconnectedException} as its ignore predicate, so an
 * SSE/broken-pipe disconnect (most commonly BootUI's own Live Activity stream closing) never pollutes
 * the Exceptions panel.
 */
class ExceptionStoreDisconnectTests {

    @Test
    void ignoresClientDisconnectExceptionsSuchAsSseStreamBrokenPipes() {
        ExceptionStore store = new ExceptionStore(100, 25, 50, DisconnectedClientHelper::isClientDisconnectedException);

        store.record(
                new AsyncRequestNotUsableException("ServletResponse failed to flushBuffer: Broken pipe"),
                "bootui-activity-stream",
                "GET",
                "/bootui/api/activity/stream",
                "LiveActivityController#stream",
                "web");

        assertThat(store.groups()).isEmpty();
        assertThat(store.totalExceptions()).isZero();
    }
}
