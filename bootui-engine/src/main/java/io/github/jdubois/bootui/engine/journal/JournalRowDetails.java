package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.EmailMessageDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The detail the journal never keeps, taken from the panel buffers while they still hold it ({@code docs/PLAN-v2.md}
 * §5.3, D27): a request's principal, an exception's message and location, and an email's subject and recipients. Every
 * value comes already masked by its owning panel under the live exposure policy, so it is applied to the live feed only,
 * never to the rows persistence writes (§8). A row whose evidence the buffers no longer hold keeps its metadata.
 *
 * <p>Every join is by identity: a request by its request id, an exception by its group id, and an email by its request
 * id and capture time.</p>
 */
public final class JournalRowDetails {

    /** No detail: rows keep the journal's metadata. */
    public static final JournalRowDetails NONE = new JournalRowDetails(Map.of(), Map.of(), Map.of(), Map.of());

    private final Map<String, String> principals;
    private final Map<String, ExceptionGroupDto> exceptionGroups;
    private final Map<String, EmailMessageDto> emails;
    private final Map<String, String> securityPrincipals;

    private JournalRowDetails(
            Map<String, String> principals,
            Map<String, ExceptionGroupDto> exceptionGroups,
            Map<String, EmailMessageDto> emails,
            Map<String, String> securityPrincipals) {
        this.principals = principals;
        this.exceptionGroups = exceptionGroups;
        this.emails = emails;
        this.securityPrincipals = securityPrincipals;
    }

    /**
     * Details from the panels' already-masked reports; each list may be {@code null} when its panel is disabled or
     * absent.
     */
    public static JournalRowDetails of(
            List<HttpExchangeDto> exchanges, List<ExceptionGroupDto> exceptionGroups, List<EmailMessageDto> emails) {
        return of(exchanges, exceptionGroups, emails, null);
    }

    /**
     * Details as {@link #of(List, List, List)} gives them, plus each security event's principal, which names the
     * request's principal when its exchange records none, as on Spring by default.
     */
    public static JournalRowDetails of(
            List<HttpExchangeDto> exchanges,
            List<ExceptionGroupDto> exceptionGroups,
            List<EmailMessageDto> emails,
            List<SecurityLogEventDto> securityEvents) {
        Map<String, String> principals = new HashMap<>();
        if (exchanges != null) {
            for (HttpExchangeDto exchange : exchanges) {
                if (exchange != null
                        && exchange.requestId() != null
                        && exchange.principal() != null
                        && !exchange.principal().isBlank()) {
                    principals.put(exchange.requestId(), exchange.principal());
                }
            }
        }
        Map<String, ExceptionGroupDto> groups = new HashMap<>();
        if (exceptionGroups != null) {
            for (ExceptionGroupDto group : exceptionGroups) {
                if (group != null && group.id() != null) {
                    groups.put(group.id(), group);
                }
            }
        }
        Map<String, EmailMessageDto> byCapture = new HashMap<>();
        if (emails != null) {
            for (EmailMessageDto email : emails) {
                if (email != null) {
                    byCapture.putIfAbsent(emailKey(email.requestId(), email.timestamp()), email);
                }
            }
        }
        Map<String, String> securityPrincipals = new HashMap<>();
        if (securityEvents != null) {
            for (SecurityLogEventDto event : securityEvents) {
                if (event != null
                        && event.requestId() != null
                        && event.principal() != null
                        && !event.principal().isBlank()) {
                    securityPrincipals.putIfAbsent(securityKey(event.requestId(), event.type()), event.principal());
                    principals.putIfAbsent(event.requestId(), event.principal());
                }
            }
        }
        return new JournalRowDetails(principals, groups, byCapture, securityPrincipals);
    }

    /** {@code row}, rendered from {@code event}, with the detail the buffers still hold. */
    ActivityEntryDto apply(ActivityEntryDto row, RuntimeEvent event) {
        RuntimeEventPayload payload = event.payload();
        if (payload instanceof HttpPayload && event.requestId() != null) {
            String principal = principals.get(event.requestId());
            return principal == null ? row : with(row, row.id(), row.summary(), row.detail(), principal);
        }
        if (payload instanceof ExceptionPayload exception && exception.groupId() != null) {
            ExceptionGroupDto group = exceptionGroups.get(exception.groupId());
            if (group == null || group.message() == null || group.message().isBlank()) {
                return group == null || group.location() == null
                        ? row
                        : with(row, row.id(), row.summary(), group.location(), null);
            }
            return with(row, row.id(), row.summary() + ": " + group.message(), group.location(), null);
        }
        if (payload instanceof SecurityPayload security && event.requestId() != null) {
            String principal = securityPrincipals.get(securityKey(event.requestId(), security.type()));
            return principal == null ? row : with(row, row.id(), row.summary() + " · " + principal, row.detail(), null);
        }
        if (payload instanceof MailPayload) {
            EmailMessageDto email = emails.get(emailKey(event.requestId(), event.epochMillis()));
            if (email == null) {
                return row;
            }
            String detail = email.to().isEmpty() ? null : "to " + String.join(", ", email.to());
            if (!email.sent()) {
                detail = (detail == null ? "" : detail + " · ") + "dev-trap: not sent";
            }
            // The Email panel's own id, so the row opens that message.
            return with(row, email.id(), email.subject() == null ? "(no subject)" : email.subject(), detail, null);
        }
        return row;
    }

    private static ActivityEntryDto with(
            ActivityEntryDto row, String id, String summary, String detail, String securedPrincipal) {
        return new ActivityEntryDto(
                id,
                row.type(),
                row.timestamp(),
                row.severity(),
                summary,
                detail,
                row.durationMs(),
                row.correlationId(),
                row.method(),
                row.path(),
                row.status(),
                row.thread(),
                row.profileable(),
                row.parentId(),
                securedPrincipal == null ? row.securedPrincipal() : securedPrincipal,
                row.sqlNPlusOneSuspected(),
                row.badges());
    }

    private static String securityKey(String requestId, String type) {
        return requestId + "#" + type;
    }

    private static String emailKey(String requestId, long timestamp) {
        return requestId + "@" + timestamp;
    }
}
