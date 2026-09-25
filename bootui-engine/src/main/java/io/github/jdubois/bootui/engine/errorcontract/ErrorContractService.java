package io.github.jdubois.bootui.engine.errorcontract;

import io.github.jdubois.bootui.core.dto.ErrorContractEntryDto;
import io.github.jdubois.bootui.core.dto.ErrorContractLinkDto;
import io.github.jdubois.bootui.core.dto.ErrorContractReport;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.engine.support.PagedList;
import io.github.jdubois.bootui.spi.ErrorContractProvider;
import io.github.jdubois.bootui.spi.ErrorHandlerDescriptor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Framework-neutral logic behind the REST API panel's error-contract catalogue, shared by the Spring
 * servlet, Spring WebFlux and Quarkus adapters.
 *
 * <p>It reads raw declaration facts from an {@link ErrorContractProvider} (optional: {@code null} when
 * the running stack has no backend) and owns everything that must look identical on every stack:
 * normalization to {@link ErrorContractEntryDto}, response-body classification, per-exception-type
 * precedence resolution, the bound on catalogue size, the stable ordering, free-text query, server-side
 * paging, and the conservative cross-link the Exceptions panel uses.</p>
 *
 * <p>Nothing here executes application code. Every value is derived from a declaration, and anything a
 * declaration cannot prove is reported as {@code UNRESOLVED}/{@code DYNAMIC} rather than guessed.</p>
 */
public final class ErrorContractService implements ErrorContractLinkResolver {

    /** Default upper bound on catalogued entries, so a pathological application cannot unbound the panel. */
    public static final int DEFAULT_MAX_ENTRIES = 500;

    // --- Status sources ------------------------------------------------------------------------
    static final String STATUS_ANNOTATION = "ANNOTATION";
    static final String STATUS_DYNAMIC = "DYNAMIC";
    static final String STATUS_UNRESOLVED = "UNRESOLVED";

    // --- Precedence sources --------------------------------------------------------------------
    static final String PRECEDENCE_DECLARED = "DECLARED";
    static final String PRECEDENCE_DEFAULT = "DEFAULT";
    static final String PRECEDENCE_UNRESOLVED = "UNRESOLVED";

    private static final String NO_BACKEND =
            "Not available: this stack has no error-contract backend wired, so declared exception handlers"
                    + " cannot be catalogued.";

    /**
     * Packages whose exception handlers belong to the framework rather than to the application.
     *
     * <p>Every Spring Boot application inherits {@code BasicErrorController}, and every Quarkus
     * application inherits the built-in RESTEasy Reactive and Jackson mappers. Reporting them would be
     * identical noise in every application, would drown the handful of declarations the developer
     * actually wrote, and would break the panel's promise that an application without advice or exception
     * mappers shows an empty catalogue. Applying the rule here rather than in each adapter keeps Spring
     * MVC, Spring WebFlux, and Quarkus from drifting on what "the application's contract" means.</p>
     *
     * <p>Only the <em>declaring component</em> is matched. An application mapper for a framework exception
     * such as {@code jakarta.validation.ConstraintViolationException} is still the application's own
     * declaration and is reported.</p>
     */
    private static final InternalPackageMatcher FRAMEWORK_PACKAGES = new InternalPackageMatcher(List.of(
            "org.springframework",
            "io.quarkus",
            "org.jboss.resteasy",
            "io.smallrye",
            "org.eclipse.microprofile",
            "jakarta",
            "com.fasterxml.jackson"));

    private final ErrorContractProvider provider;

    private final int maxEntries;

    /**
     * Memoized catalogue, invalidated by value when the provider reports a different declaration set. The
     * Exceptions panel resolves one cross-link per retained group, so recomputing the whole catalogue per
     * lookup would be quadratic; providers return a stable list instance, which makes the guard O(1).
     */
    private volatile Memo memo;

    /** The memoized catalogue and the declaration list it was built from, published together. */
    private record Memo(List<ErrorHandlerDescriptor> source, List<ErrorContractEntryDto> catalogue) {}

    public ErrorContractService(ErrorContractProvider provider) {
        this(provider, DEFAULT_MAX_ENTRIES);
    }

    public ErrorContractService(ErrorContractProvider provider, int maxEntries) {
        this.provider = provider;
        this.maxEntries = Math.max(1, maxEntries);
    }

    /** The sorted, queried and paged catalogue; explicitly unavailable when no backend is wired. */
    public ErrorContractReport report(String query, Integer offset, Integer limit) {
        if (provider == null || !provider.available()) {
            String reason = provider == null ? NO_BACKEND : reasonOrDefault(provider.unavailableReason());
            return ErrorContractReport.unavailable(reason, maxEntries);
        }
        List<ErrorContractEntryDto> all = catalogue();
        boolean truncated = all.size() > maxEntries;
        List<ErrorContractEntryDto> bounded = truncated ? List.copyOf(all.subList(0, maxEntries)) : all;

        String normalizedQuery = PagedList.normalize(query);
        PagedList.Result<ErrorContractEntryDto> page =
                PagedList.from(bounded, entry -> matches(entry, normalizedQuery), offset, limit);
        return new ErrorContractReport(
                true,
                null,
                bounded.size(),
                distinct(bounded, entry -> entry.component() + "#" + entry.method()),
                distinct(bounded, ErrorContractEntryDto::component),
                distinct(bounded, ErrorContractEntryDto::exceptionType),
                truncated,
                maxEntries,
                page.items(),
                page.page());
    }

    /**
     * Resolves the single declared handler that would produce the HTTP response for a retained failure, or
     * {@code null} when the retained evidence cannot identify exactly one.
     *
     * <p>Deliberately conservative, so the Exceptions panel never invents a relationship:</p>
     * <ul>
     *   <li>Only an <em>exact</em> exception-type match counts. BootUI has no framework-neutral type
     *       hierarchy at this seam, so a handler declared for a supertype is not attributed.</li>
     *   <li>A {@code SCOPED} or {@code UNKNOWN} candidate makes the whole resolution ambiguous: its
     *       applicability depends on selectors BootUI cannot evaluate from a retained failure.</li>
     *   <li>A {@code CONTROLLER} candidate is used only when the retained handler evidence names its
     *       declaring class; when a controller-local candidate exists but the evidence cannot confirm or
     *       exclude it, nothing is linked.</li>
     *   <li>Among the remaining application-wide candidates, the highest-ranked one is linked, and only when
     *       its precedence is resolved. A tie at the top is reported as unresolved, so nothing is linked.</li>
     * </ul>
     *
     * @param exceptionClassName fully-qualified type of the retained exception
     * @param handlerEvidence the retained request handler (for example {@code com.acme.OrderController#get}),
     *     or {@code null} when the failure carried none
     */
    @Override
    public ErrorContractLinkDto resolve(String exceptionClassName, String handlerEvidence) {
        if (provider == null || !provider.available() || exceptionClassName == null || exceptionClassName.isBlank()) {
            return null;
        }
        List<ErrorContractEntryDto> candidates = catalogue().stream()
                .filter(entry -> exceptionClassName.equals(entry.exceptionType()))
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        for (ErrorContractEntryDto candidate : candidates) {
            if (ErrorHandlerDescriptor.SCOPE_SCOPED.equals(candidate.scope())
                    || ErrorHandlerDescriptor.SCOPE_UNKNOWN.equals(candidate.scope())) {
                return null;
            }
        }
        List<ErrorContractEntryDto> controllerLocal = candidates.stream()
                .filter(entry -> ErrorHandlerDescriptor.SCOPE_CONTROLLER.equals(entry.scope()))
                .toList();
        if (!controllerLocal.isEmpty()) {
            if (handlerEvidence == null || handlerEvidence.isBlank()) {
                return null; // a controller-local handler may apply, and nothing proves it did not
            }
            List<ErrorContractEntryDto> confirmed = controllerLocal.stream()
                    .filter(entry -> declaredBy(handlerEvidence, entry.scopeTarget()))
                    .toList();
            if (confirmed.size() == 1) {
                return toLink(confirmed.get(0));
            }
            if (!confirmed.isEmpty()) {
                return null; // several controller-local declarations in the serving controller
            }
            // No controller-local declaration belongs to the serving controller, so the application-wide
            // candidates below are the only ones that could have applied.
        }
        ErrorContractEntryDto winner = candidates.stream()
                .filter(entry -> ErrorHandlerDescriptor.SCOPE_GLOBAL.equals(entry.scope()))
                .min(Comparator.comparingInt(ErrorContractEntryDto::precedence))
                .orElse(null);
        if (winner == null || PRECEDENCE_UNRESOLVED.equals(winner.precedenceSource())) {
            return null;
        }
        return toLink(winner);
    }

    private static ErrorContractLinkDto toLink(ErrorContractEntryDto entry) {
        return new ErrorContractLinkDto(
                entry.id(),
                entry.component(),
                entry.componentSimpleName(),
                entry.method(),
                entry.scope(),
                entry.status(),
                entry.bodyCategory());
    }

    /**
     * Whether the retained handler evidence names the supplied declaring class.
     *
     * <p>Evidence is what the request that failed recorded, and both adapters record it as
     * {@code SimpleName#method} ({@code BootUiExceptionHandlerResolver} on Spring,
     * {@code QuarkusResourceHandlers} on Quarkus). Fully-qualified evidence is accepted too so the seam
     * does not depend on that formatting choice. Only the class part is compared: a handler declared in
     * {@code OrderController} still applies no matter which of its methods was serving.</p>
     *
     * <p>Simple-name comparison is exact, never a substring, so {@code OrderController} never matches
     * {@code AdminOrderController}. Two same-simple-named controllers in different packages are the one
     * ambiguity this cannot see through; the caller keeps that conservative by requiring exactly one
     * confirmed declaration before it links.</p>
     */
    private static boolean declaredBy(String handlerEvidence, String declaringClass) {
        if (declaringClass == null || declaringClass.isBlank()) {
            return false;
        }
        String evidenceClass = evidenceClass(handlerEvidence);
        if (evidenceClass.isEmpty()) {
            return false;
        }
        return evidenceClass.equals(declaringClass) || evidenceClass.equals(simpleName(declaringClass));
    }

    /** The class part of handler evidence: everything before the {@code #} method separator. */
    private static String evidenceClass(String handlerEvidence) {
        String value = handlerEvidence.trim();
        int separator = value.indexOf('#');
        return separator < 0 ? value : value.substring(0, separator).trim();
    }

    /** The full, ordered catalogue: one entry per (declaration, handled exception type). */
    private List<ErrorContractEntryDto> catalogue() {
        List<ErrorHandlerDescriptor> handlers = provider.handlers();
        if (handlers == null) {
            return List.of();
        }
        Memo cached = memo;
        if (cached != null && handlers.equals(cached.source())) {
            return cached.catalogue();
        }
        List<ErrorContractEntryDto> computed = buildCatalogue(handlers);
        memo = new Memo(handlers, computed);
        return computed;
    }

    private static List<ErrorContractEntryDto> buildCatalogue(List<ErrorHandlerDescriptor> handlers) {
        List<Candidate> candidates = new ArrayList<>();
        for (ErrorHandlerDescriptor descriptor : handlers) {
            if (descriptor == null || descriptor.componentClassName() == null) {
                continue;
            }
            if (FRAMEWORK_PACKAGES.matchesName(descriptor.componentClassName())) {
                continue; // the framework's own handlers are not part of this application's contract
            }
            Set<String> exceptionTypes = new LinkedHashSet<>(descriptor.exceptionTypeNames());
            for (String exceptionType : exceptionTypes) {
                if (exceptionType != null && !exceptionType.isBlank()) {
                    candidates.add(new Candidate(descriptor, exceptionType));
                }
            }
        }

        Map<String, List<Candidate>> byExceptionType = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            byExceptionType
                    .computeIfAbsent(candidate.exceptionType(), ignored -> new ArrayList<>())
                    .add(candidate);
        }

        List<ErrorContractEntryDto> entries = new ArrayList<>();
        for (Map.Entry<String, List<Candidate>> group : byExceptionType.entrySet()) {
            entries.addAll(rank(group.getValue()));
        }
        entries.sort(Comparator.comparing(ErrorContractEntryDto::exceptionSimpleName)
                .thenComparing(ErrorContractEntryDto::exceptionType)
                .thenComparingInt(ErrorContractEntryDto::precedence)
                .thenComparing(ErrorContractEntryDto::component)
                .thenComparing(ErrorContractEntryDto::method));
        return List.copyOf(entries);
    }

    /**
     * Resolves precedence within the candidates for one exception type. Candidates that tie on every piece
     * of declared evidence share a rank and are reported as {@code UNRESOLVED} rather than being ordered
     * arbitrarily.
     */
    private static List<ErrorContractEntryDto> rank(List<Candidate> candidates) {
        boolean unrankable = candidates.size() > 1
                && candidates.stream()
                        .anyMatch(candidate -> candidate.descriptor().dynamicPrecedence());
        List<Candidate> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingInt((Candidate candidate) -> scopeRank(candidate.descriptor()))
                .thenComparingInt(Candidate::order)
                .thenComparing(candidate -> candidate.descriptor().componentClassName())
                .thenComparing(candidate -> nullSafe(candidate.descriptor().methodName())));

        List<ErrorContractEntryDto> ranked = new ArrayList<>(ordered.size());
        if (unrankable) {
            // At least one component orders itself at runtime, so no declared evidence separates the group.
            for (Candidate candidate : ordered) {
                ranked.add(toEntry(candidate, 1, PRECEDENCE_UNRESOLVED));
            }
            return ranked;
        }
        int index = 0;
        while (index < ordered.size()) {
            int end = index + 1;
            while (end < ordered.size() && sameDeclaredPrecedence(ordered.get(index), ordered.get(end))) {
                end++;
            }
            boolean ambiguous = competesWithinTie(ordered.subList(index, end));
            for (int position = index; position < end; position++) {
                Candidate candidate = ordered.get(position);
                String precedenceSource = ambiguous
                        ? PRECEDENCE_UNRESOLVED
                        : (candidate.descriptor().dynamicPrecedence()
                                ? PRECEDENCE_UNRESOLVED
                                : (candidate.descriptor().declaredOrder() == null
                                        ? PRECEDENCE_DEFAULT
                                        : PRECEDENCE_DECLARED));
                ranked.add(toEntry(candidate, index + 1, precedenceSource));
            }
            index = end;
        }
        return ranked;
    }

    /** Two candidates whose declared evidence cannot separate them; they therefore share a rank. */
    private static boolean sameDeclaredPrecedence(Candidate left, Candidate right) {
        return scopeRank(left.descriptor()) == scopeRank(right.descriptor()) && left.order() == right.order();
    }

    /**
     * Whether candidates that share a rank actually compete. Controller-local declarations in different
     * controllers never serve the same request, so they share rank 1 without being ambiguous; two
     * declarations that do compete on equal declared evidence are genuinely unresolved.
     */
    private static boolean competesWithinTie(List<Candidate> tied) {
        if (tied.size() < 2) {
            return false;
        }
        Set<String> applicability = new LinkedHashSet<>();
        for (Candidate candidate : tied) {
            ErrorHandlerDescriptor descriptor = candidate.descriptor();
            applicability.add(
                    ErrorHandlerDescriptor.SCOPE_CONTROLLER.equals(descriptor.scope())
                            ? nullSafe(descriptor.scopeTarget())
                            : "");
        }
        return applicability.size() < tied.size();
    }

    private static int scopeRank(ErrorHandlerDescriptor descriptor) {
        String scope = descriptor.scope();
        if (ErrorHandlerDescriptor.SCOPE_CONTROLLER.equals(scope)) {
            return 0;
        }
        if (ErrorHandlerDescriptor.SCOPE_SCOPED.equals(scope)) {
            return 1;
        }
        if (ErrorHandlerDescriptor.SCOPE_GLOBAL.equals(scope)) {
            return 2;
        }
        return 3;
    }

    private static ErrorContractEntryDto toEntry(Candidate candidate, int precedence, String precedenceSource) {
        ErrorHandlerDescriptor descriptor = candidate.descriptor();
        String component = descriptor.componentClassName();
        String method = nullSafe(descriptor.methodName());
        String status = descriptor.declaredStatus();
        String statusSource;
        if (status != null && !status.isBlank()) {
            statusSource = STATUS_ANNOTATION;
        } else {
            status = null;
            statusSource = descriptor.dynamicStatus() ? STATUS_DYNAMIC : STATUS_UNRESOLVED;
        }
        return new ErrorContractEntryDto(
                component + "#" + method + "(" + candidate.exceptionType() + ")",
                candidate.exceptionType(),
                simpleName(candidate.exceptionType()),
                component,
                simpleName(component),
                method,
                descriptor.source(),
                scope(descriptor),
                descriptor.scopeTarget(),
                precedence,
                precedenceSource,
                status,
                statusSource,
                bodyCategory(descriptor),
                descriptor.bodyTypeName(),
                produces(descriptor));
    }

    /**
     * The declared media types, de-duplicated and ordered. Adapters report them in declaration order, which
     * differs per stack; normalizing here keeps equivalent declarations byte-identical across adapters.
     */
    private static List<String> produces(ErrorHandlerDescriptor descriptor) {
        return descriptor.produces().stream()
                .filter(type -> type != null && !type.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }

    private static String scope(ErrorHandlerDescriptor descriptor) {
        String scope = descriptor.scope();
        return scope == null || scope.isBlank() ? ErrorHandlerDescriptor.SCOPE_UNKNOWN : scope;
    }

    /**
     * Classifies the response body from the declaration alone. {@code DYNAMIC} means the declaration
     * proves a body is produced but not what shape it has; {@code UNRESOLVED} means the declaration proves
     * nothing at all.
     */
    static String bodyCategory(ErrorHandlerDescriptor descriptor) {
        return ErrorBodyCategory.classify(
                descriptor.returnTypeName(), descriptor.bodyTypeName(), descriptor.dynamicStatus());
    }

    private static boolean matches(ErrorContractEntryDto entry, String query) {
        return PagedList.contains(entry.exceptionType(), query)
                || PagedList.contains(entry.component(), query)
                || PagedList.contains(entry.method(), query)
                || PagedList.contains(entry.status(), query)
                || PagedList.contains(entry.scope(), query)
                || PagedList.contains(entry.bodyCategory(), query)
                || PagedList.contains(entry.bodyType(), query)
                || PagedList.contains(String.join(", ", entry.produces()), query);
    }

    private static int distinct(List<ErrorContractEntryDto> entries, Function<ErrorContractEntryDto, String> key) {
        Set<String> values = new LinkedHashSet<>();
        for (ErrorContractEntryDto entry : entries) {
            values.add(key.apply(entry));
        }
        return values.size();
    }

    private static String simpleName(String className) {
        if (className == null) {
            return "";
        }
        int separator = Math.max(className.lastIndexOf('.'), className.lastIndexOf('$'));
        return separator < 0 ? className : className.substring(separator + 1);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String reasonOrDefault(String reason) {
        return reason == null || reason.isBlank() ? NO_BACKEND : reason;
    }

    /** One (declaration, handled exception type) pair before ranking and normalization. */
    private record Candidate(ErrorHandlerDescriptor descriptor, String exceptionType) {

        /** The declared ordering value, or {@code Integer.MAX_VALUE} when the declaration carries none. */
        int order() {
            Integer declared = descriptor.declaredOrder();
            return declared == null ? Integer.MAX_VALUE : declared;
        }
    }
}
