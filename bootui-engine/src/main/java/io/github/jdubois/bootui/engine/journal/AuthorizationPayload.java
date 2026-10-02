package io.github.jdubois.bootui.engine.journal;

import java.util.Objects;

/**
 * One authorization decision ({@code docs/PLAN-v2.md} §5.18): a request or a method checked, the rule that decided, how
 * the caller was authenticated, and whether access was granted. It holds a count of authorities, never their names, a
 * principal, or a credential. A request's decision joins its request by id; a method's names its {@code Class#method}.
 *
 * @param target {@link #REQUEST} or {@link #METHOD}
 * @param subject the method, such as {@code OrderService#cancel}, or {@code null} for a request
 * @param rule what decided, such as {@code hasAnyAuthority(ROLE_ADMIN)}, or {@code null} when the framework does not say
 * @param authentication {@link #ANONYMOUS}, {@link #AUTHENTICATED}, {@link #NONE} when no authentication was present,
 *     or {@link #UNKNOWN} when the capture could not establish it
 * @param granted whether access was granted
 * @param authorities how many authorities or roles the caller had
 */
public record AuthorizationPayload(
        String target, String subject, String rule, String authentication, boolean granted, int authorities)
        implements RuntimeEventPayload {

    public static final String REQUEST = "REQUEST";
    public static final String METHOD = "METHOD";
    public static final String ANONYMOUS = "ANONYMOUS";
    public static final String AUTHENTICATED = "AUTHENTICATED";
    public static final String NONE = "NONE";
    public static final String UNKNOWN = "UNKNOWN";

    public AuthorizationPayload {
        Objects.requireNonNull(target, "target");
        authentication = authentication == null ? UNKNOWN : authentication;
    }

    /** Whether this decision is about a request rather than a method. */
    public boolean request() {
        return REQUEST.equals(target);
    }

    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new AuthorizationPayload(
                dictionary.shared(target),
                dictionary.shared(subject),
                dictionary.shared(rule),
                dictionary.shared(authentication),
                granted,
                authorities);
    }

    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 40
                + JournalDictionary.retained(dictionary, target)
                + JournalDictionary.retained(dictionary, subject)
                + JournalDictionary.retained(dictionary, rule)
                + JournalDictionary.retained(dictionary, authentication);
    }
}
