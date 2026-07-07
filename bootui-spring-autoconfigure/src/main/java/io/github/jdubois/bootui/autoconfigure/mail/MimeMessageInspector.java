package io.github.jdubois.bootui.autoconfigure.mail;

import io.github.jdubois.bootui.engine.email.CapturedAttachment;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort {@code jakarta.mail} MIME walker that extracts the fields the Email Viewer panel needs
 * (recipients, subject, plain-text/HTML bodies, attachment metadata) without depending on any
 * third-party MIME parsing library.
 *
 * <p>Parsing never throws: any failure is logged at debug level and the affected field is simply left
 * {@code null}/empty, so a malformed or unusual message can never break the application's real mail
 * sending, which always happens from the original, unmodified {@link MimeMessage}.</p>
 */
final class MimeMessageInspector {

    private static final Logger log = LoggerFactory.getLogger(MimeMessageInspector.class);

    private MimeMessageInspector() {}

    record Parsed(
            String from,
            List<String> to,
            List<String> cc,
            List<String> bcc,
            String subject,
            String textBody,
            String htmlBody,
            List<CapturedAttachment> attachments) {}

    static Parsed parse(MimeMessage message) {
        // A freshly built MimeMessage/MimeBodyPart only exposes an accurate Content-Type via
        // getContentType()/isMimeType() once its headers have been derived from the underlying
        // DataHandler, which normally only happens as a side effect of Transport.send() (or an explicit
        // saveChanges() call). Since we capture before the real send, force that derivation now so the
        // walk below sees correct MIME types; this is idempotent and harmless to call again later.
        try {
            message.saveChanges();
        } catch (Exception ex) {
            log.debug("BootUI could not normalize a captured email's headers before parsing", ex);
        }
        String from = addressesToString(safeAddresses(message::getFrom));
        List<String> to = addressListToString(safeAddresses(() -> message.getRecipients(Message.RecipientType.TO)));
        List<String> cc = addressListToString(safeAddresses(() -> message.getRecipients(Message.RecipientType.CC)));
        List<String> bcc = addressListToString(safeAddresses(() -> message.getRecipients(Message.RecipientType.BCC)));
        String subject = safeString(message::getSubject);

        Bodies bodies = new Bodies();
        try {
            walk(message, bodies);
        } catch (Exception ex) {
            log.debug("BootUI could not fully parse a captured email's body/attachments", ex);
        }
        return new Parsed(from, to, cc, bcc, subject, bodies.text, bodies.html, bodies.attachments);
    }

    private static void walk(Part part, Bodies bodies) throws Exception {
        // A message's top-level Content-Type header is only guaranteed accurate after
        // MimeMessage.saveChanges() has run, which callers may not have done yet at capture time
        // (it normally happens later, inside the real send). So don't rely on isMimeType("multipart/*")
        // alone; fall back to inspecting the actual content object.
        if (part.isMimeType("multipart/*") || part.getContent() instanceof Multipart) {
            Object content = part.getContent();
            if (content instanceof Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    walk(multipart.getBodyPart(i), bodies);
                }
            }
            return;
        }
        String disposition = part.getDisposition();
        String filename = safeFileName(part);
        boolean looksLikeAttachment = filename != null
                && (Part.ATTACHMENT.equalsIgnoreCase(disposition)
                        || Part.INLINE.equalsIgnoreCase(disposition)
                        || (!part.isMimeType("text/plain") && !part.isMimeType("text/html")));
        if (looksLikeAttachment) {
            bodies.attachments.add(new CapturedAttachment(filename, safeContentType(part), safeSize(part)));
            return;
        }
        if (part.isMimeType("text/html")) {
            if (bodies.html == null) {
                bodies.html = safeContentAsString(part);
            }
        } else if (part.isMimeType("text/plain")) {
            if (bodies.text == null) {
                bodies.text = safeContentAsString(part);
            }
        }
    }

    private static String safeContentAsString(Part part) {
        try {
            Object content = part.getContent();
            return content == null ? null : content.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private static String safeFileName(Part part) {
        try {
            return part.getFileName();
        } catch (MessagingException ex) {
            return null;
        }
    }

    private static String safeContentType(Part part) {
        try {
            return part.getContentType();
        } catch (MessagingException ex) {
            return null;
        }
    }

    private static Long safeSize(Part part) {
        try {
            int size = part.getSize();
            return size < 0 ? null : (long) size;
        } catch (MessagingException ex) {
            return null;
        }
    }

    private static String safeString(ThrowingSupplier<String> supplier) {
        try {
            return supplier.get();
        } catch (Exception ex) {
            return null;
        }
    }

    private static Address[] safeAddresses(ThrowingSupplier<Address[]> supplier) {
        try {
            return supplier.get();
        } catch (Exception ex) {
            return null;
        }
    }

    private static String addressesToString(Address[] addresses) {
        List<String> asStrings = addressListToString(addresses);
        return asStrings.isEmpty() ? null : asStrings.get(0);
    }

    private static List<String> addressListToString(Address[] addresses) {
        List<String> result = new ArrayList<>();
        if (addresses != null) {
            for (Address address : addresses) {
                result.add(address.toString());
            }
        }
        return result;
    }

    private static final class Bodies {
        private String text;
        private String html;
        private final List<CapturedAttachment> attachments = new ArrayList<>();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
