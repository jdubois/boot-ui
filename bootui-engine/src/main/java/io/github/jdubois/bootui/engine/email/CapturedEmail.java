package io.github.jdubois.bootui.engine.email;

import java.util.List;

/**
 * A raw outgoing email as handed to the adapter's mail-sender wrapper, before masking. This is the
 * framework-neutral seam both the Spring ({@code JavaMailSender} decorator) and Quarkus
 * ({@code Mailer}/{@code ReactiveMailer} CDI decorator) bindings feed into {@link EmailStore}, so the
 * store and its masking never depend on {@code jakarta.mail} or {@code io.quarkus.mailer} types.
 *
 * @param from the sender address, or {@code null} when not set
 * @param to the recipient addresses
 * @param cc the CC addresses
 * @param bcc the BCC addresses
 * @param subject the subject line, or {@code null} when not set
 * @param textBody the plain-text body, or {@code null} when the message carried none
 * @param htmlBody the HTML body, or {@code null} when the message carried none
 * @param attachments metadata for each attachment
 */
public record CapturedEmail(
        String from,
        List<String> to,
        List<String> cc,
        List<String> bcc,
        String subject,
        String textBody,
        String htmlBody,
        List<CapturedAttachment> attachments) {

    public CapturedEmail {
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        bcc = bcc == null ? List.of() : List.copyOf(bcc);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Small builder so adapters don't have to juggle the full constructor argument list. */
    public static final class Builder {
        private String from;
        private List<String> to = List.of();
        private List<String> cc = List.of();
        private List<String> bcc = List.of();
        private String subject;
        private String textBody;
        private String htmlBody;
        private List<CapturedAttachment> attachments = List.of();

        public Builder from(String from) {
            this.from = from;
            return this;
        }

        public Builder to(List<String> to) {
            this.to = to;
            return this;
        }

        public Builder cc(List<String> cc) {
            this.cc = cc;
            return this;
        }

        public Builder bcc(List<String> bcc) {
            this.bcc = bcc;
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder textBody(String textBody) {
            this.textBody = textBody;
            return this;
        }

        public Builder htmlBody(String htmlBody) {
            this.htmlBody = htmlBody;
            return this;
        }

        public Builder attachments(List<CapturedAttachment> attachments) {
            this.attachments = attachments;
            return this;
        }

        public CapturedEmail build() {
            return new CapturedEmail(from, to, cc, bcc, subject, textBody, htmlBody, attachments);
        }
    }
}
