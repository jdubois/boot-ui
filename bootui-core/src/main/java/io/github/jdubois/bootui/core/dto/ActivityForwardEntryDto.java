package io.github.jdubois.bootui.core.dto;

/**
 * Wire twin of {@code io.github.jdubois.bootui.engine.activity.StoredActivityEntry}, carried in an
 * {@link ActivityForwardBatchRequest} POST body from an HTTP-forwarding sender to a receiving BootUI
 * instance.
 *
 * <p>{@code bootui-core} cannot depend on {@code bootui-engine} (the dependency direction is strictly
 * {@code core <- engine}), so this record is a self-contained mirror of {@code StoredActivityEntry}
 * rather than a reference to it: the sending adapter's engine-side forwarding store maps one to the
 * other explicitly on the way out, and the receiving engine-side service maps it back on the way in.
 *
 * @param instanceId identifier of the BootUI instance that captured this entry (multi-tenant partition
 *     key), unchanged from the sender's own {@code instanceId} so the receiver's durable store keeps
 *     forwarded rows correctly attributed to the instance that produced them, not the receiver itself
 * @param seq the sender's own monotonic sequence number for this entry, carried through unchanged so
 *     the receiver's {@code (instanceId, seq)} identity and merge-for-reads dedup logic works exactly
 *     as it does for locally captured entries
 * @param entry the normalized, already-masked activity entry
 */
public record ActivityForwardEntryDto(String instanceId, long seq, ActivityEntryDto entry) {}
