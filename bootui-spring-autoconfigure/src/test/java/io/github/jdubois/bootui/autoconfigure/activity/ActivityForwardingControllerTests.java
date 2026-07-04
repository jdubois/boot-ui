package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityForwardBatchRequest;
import io.github.jdubois.bootui.core.dto.ActivityForwardEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityForwardResult;
import io.github.jdubois.bootui.engine.activity.ActivityForwardService;
import io.github.jdubois.bootui.engine.activity.StoredActivityEntry;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Mirrors {@code LiveActivityControllerTests}' plain-instantiation, no-Spring-context style: this
 * controller is a thin adapter over the already fully unit-tested {@link ActivityForwardService}
 * orchestration, so these tests pin only the controller's own wiring (which field feeds which
 * parameter, and that the returned {@link ActivityForwardResponse} status/body round-trip correctly
 * into a Spring {@link ResponseEntity}) rather than re-verifying validation logic already covered by
 * {@code ActivityForwardServiceTests}.
 *
 * <p>Does not exercise {@code LocalhostOnlyFilter}/{@code PanelAccessFilter} — those run at the
 * servlet-filter layer, above any controller, and are outside what a plain-instantiation controller
 * test can reach. Both filters already apply automatically to every {@code /bootui/api/**} path
 * (verified by reading their matching logic directly; see the class Javadoc on {@link
 * ActivityForwardingController} and the existing {@code PanelAccessFilterTests} prefix-matching
 * coverage for the sibling {@code /activity/use-existing-datasource} action), so no new filter-level
 * test is required for this endpoint specifically.
 */
class ActivityForwardingControllerTests {

    @Test
    void forwardAppendsBatchAndReturns200WhenNoSecretIsConfigured() {
        SwitchableActivityStore store = mock(SwitchableActivityStore.class);
        ActivityForwardingController controller = new ActivityForwardingController(store, new BootUiProperties());

        ActivityForwardBatchRequest request = new ActivityForwardBatchRequest(
                List.of(new ActivityForwardEntryDto("sender-1", 1, entry("1", "REQUEST", 1_000, "OK", "hello"))));

        ResponseEntity<ActivityForwardResult> response = controller.forward(null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new ActivityForwardResult("accepted", "Appended 1 entries", 1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StoredActivityEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).appendBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).instanceId()).isEqualTo("sender-1");
        assertThat(captor.getValue().get(0).seq()).isEqualTo(1);
    }

    @Test
    void forwardRejectsMissingTokenWhenASecretIsConfiguredAndNeverCallsAppendBatch() {
        SwitchableActivityStore store = mock(SwitchableActivityStore.class);
        BootUiProperties properties = new BootUiProperties();
        properties.getActivity().getForwarding().setSharedSecret("configured-secret");
        ActivityForwardingController controller = new ActivityForwardingController(store, properties);

        ActivityForwardBatchRequest request = new ActivityForwardBatchRequest(
                List.of(new ActivityForwardEntryDto("sender-1", 1, entry("1", "REQUEST", 1_000, "OK", "hello"))));

        ResponseEntity<ActivityForwardResult> response = controller.forward(null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().status()).isEqualTo("unauthorized");
        verify(store, never()).appendBatch(anyList());
    }

    @Test
    void forwardRejectsWrongTokenWhenASecretIsConfiguredAndNeverCallsAppendBatch() {
        SwitchableActivityStore store = mock(SwitchableActivityStore.class);
        BootUiProperties properties = new BootUiProperties();
        properties.getActivity().getForwarding().setSharedSecret("configured-secret");
        ActivityForwardingController controller = new ActivityForwardingController(store, properties);

        ActivityForwardBatchRequest request = new ActivityForwardBatchRequest(
                List.of(new ActivityForwardEntryDto("sender-1", 1, entry("1", "REQUEST", 1_000, "OK", "hello"))));

        ResponseEntity<ActivityForwardResult> response = controller.forward("wrong-token", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(store, never()).appendBatch(anyList());
    }

    @Test
    void forwardAcceptsAMatchingTokenWhenASecretIsConfigured() {
        SwitchableActivityStore store = mock(SwitchableActivityStore.class);
        BootUiProperties properties = new BootUiProperties();
        properties.getActivity().getForwarding().setSharedSecret("configured-secret");
        ActivityForwardingController controller = new ActivityForwardingController(store, properties);

        ActivityForwardBatchRequest request = new ActivityForwardBatchRequest(
                List.of(new ActivityForwardEntryDto("sender-1", 1, entry("1", "REQUEST", 1_000, "OK", "hello"))));

        ResponseEntity<ActivityForwardResult> response = controller.forward("configured-secret", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(store).appendBatch(anyList());
    }

    @Test
    void forwardRejectsANullBodyAndNeverCallsAppendBatch() {
        SwitchableActivityStore store = mock(SwitchableActivityStore.class);
        ActivityForwardingController controller = new ActivityForwardingController(store, new BootUiProperties());

        ResponseEntity<ActivityForwardResult> response = controller.forward(null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(store, never()).appendBatch(anyList());
    }

    @Test
    void forwardReturns500WhenTheLocalStoreThrowsWithoutPropagating() {
        SwitchableActivityStore store = mock(SwitchableActivityStore.class);
        doThrow(new RuntimeException("db is down")).when(store).appendBatch(anyList());
        ActivityForwardingController controller = new ActivityForwardingController(store, new BootUiProperties());

        ActivityForwardBatchRequest request = new ActivityForwardBatchRequest(
                List.of(new ActivityForwardEntryDto("sender-1", 1, entry("1", "REQUEST", 1_000, "OK", "hello"))));

        ResponseEntity<ActivityForwardResult> response = controller.forward(null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().status()).isEqualTo("failed");
    }

    private static ActivityEntryDto entry(String id, String type, long timestamp, String severity, String summary) {
        return new ActivityEntryDto(
                id, type, timestamp, severity, summary, null, null, null, null, null, null, null, false, null, null,
                false);
    }
}
