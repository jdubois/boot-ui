package io.github.jdubois.bootui.autoconfigure.kernel;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Internal result of running a single gadget, before it is mapped to the public
 * {@link io.github.jdubois.bootui.core.dto.KernelGadgetResult} DTO.
 *
 * <p>{@code events} holds the raw, gadget-specific JSON objects (decoded into maps) emitted by
 * {@code ig}. Keeping decoding inside the runner lets {@code KernelInsightsService} stay focused on
 * normalizing events into stable DTOs and makes the mapping easy to unit-test with canned events.
 */
public record IgRunResult(boolean ok, @Nullable String message, List<Map<String, Object>> events) {

    public IgRunResult {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public static IgRunResult ok(List<Map<String, Object>> events) {
        return new IgRunResult(true, null, events);
    }

    public static IgRunResult error(String message) {
        return new IgRunResult(false, message, List.of());
    }
}
