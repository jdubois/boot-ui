package io.github.jdubois.bootui.engine.journal;

/**
 * One outgoing email's metadata ({@code docs/PLAN-v2.md} §5.2, §8): never its sender, recipients, subject, or body.
 *
 * @param recipients its recipients, across to, cc, and bcc
 * @param attachments its attachments
 * @param sent whether it was handed to the real mail transport; {@code false} when dev-trap mode intercepted it
 */
public record MailPayload(int recipients, int attachments, boolean sent) implements RuntimeEventPayload {

    @Override
    public int estimatedBytes() {
        return 16;
    }
}
