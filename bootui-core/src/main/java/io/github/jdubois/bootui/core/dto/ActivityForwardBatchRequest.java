package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * POST body an {@code HttpActivityStore} sender sends to a receiving BootUI instance's Live Activity
 * forwarding endpoint: one batch of already-captured, already-masked entries to append to the
 * receiver's own local durable store.
 *
 * @param entries the batch of entries to append, in the sender's own capture order
 */
public record ActivityForwardBatchRequest(List<ActivityForwardEntryDto> entries) {}
