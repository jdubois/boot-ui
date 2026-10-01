package io.github.jdubois.bootui.engine.architecture;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides whether a self-call to a {@code @Transactional} method merely joins the transaction its caller already
 * runs in, in which case skipping the proxy changes nothing and ARCH-SPRING-004 has nothing to report.
 *
 * <p>Every judgement fails toward reporting: an attribute that cannot be read, a caller that may run outside a
 * transaction, or a callee whose declaration would change the outcome through the proxy keeps the finding.
 */
final class TransactionJoin {

    // Through the proxy, these propagations make the callee participate in the caller's transaction.
    private static final Set<String> JOINING = Set.of("REQUIRED", "SUPPORTS", "MANDATORY");

    // A method entered through the proxy with one of these propagations always runs inside a transaction.
    private static final Set<String> GUARANTEED = Set.of("REQUIRED", "REQUIRES_NEW", "MANDATORY", "NESTED");

    private static final String DEFAULT_ISOLATION = "DEFAULT";

    private TransactionJoin() {}

    /**
     * Whether making {@code call} directly to {@code target} loses nothing the proxy would have applied.
     *
     * <p>A call written inside a lambda never qualifies: ArchUnit attributes it to the enclosing method, but the
     * lambda may run after that method's transaction has ended, or on another thread. Neither does a call inside a
     * {@code try} block: through the proxy, an exception leaving a joining callee marks the shared transaction
     * rollback-only, so catching it, or rethrowing it as an exception the caller's rules commit on, ends in a
     * rollback, while called directly the caller commits.
     */
    static boolean joinsCallerTransaction(JavaMethodCall call, JavaMethod target) {
        if (!isPlainCall(call) || !isOnlyTransactional(target, call.getTargetOwner())) {
            return false;
        }
        Optional<Definition> callee = transactional(target).flatMap(Definition::of);
        if (callee.isEmpty() || !JOINING.contains(callee.get().propagation())) {
            return false;
        }
        Optional<Set<Definition>> enclosing = enclosingTransactions(call.getOrigin(), new HashSet<>());
        return enclosing.isPresent()
                && !enclosing.get().isEmpty()
                && enclosing.get().stream().allMatch(callee.get()::joinsUnchanged);
    }

    // Any other interceptor on the callee is lost by the self-call even when its transaction would only join.
    private static boolean isPlainCall(JavaMethodCall call) {
        return !call.isDeclaredInLambda() && call.getContainingTryBlocks().isEmpty();
    }

    // The class a call is made on decides class-level interception, even for a method inherited from a superclass.
    private static boolean isOnlyTransactional(JavaMethod target, JavaClass receiver) {
        return SpringStereotypes.TRANSACTIONAL_ANNOTATED.test(target)
                && !SpringStereotypes.ASYNC_ANNOTATED.test(target)
                && !SpringStereotypes.CACHE_OPERATION_ANNOTATED.test(target)
                && !SpringStereotypes.RESILIENCE_ANNOTATED.test(target)
                && !SpringStereotypes.METHOD_SECURITY_ANNOTATED.test(target)
                && !SpringStereotypes.CLASS_LEVEL_PROXY_ANNOTATED.test(receiver)
                && !SpringStereotypes.CLASS_LEVEL_PROXY_ANNOTATED.test(target.getOwner());
    }

    /**
     * The transactions {@code origin} is guaranteed to run inside, or empty when it may run without one.
     *
     * <p>A method the proxy intercepts runs inside its own declaration (method-level, else class-level). A private
     * method is never intercepted, so it runs inside whatever its callers run in: it qualifies only when every
     * caller is in the same class, calls it outside any lambda or {@code try} block, and itself qualifies, and no
     * method reference lets it escape to run elsewhere.
     * Compiler-generated origins, such as lambda bodies, may run later or on another thread, so they never
     * qualify, and neither do constructors, static methods, or final methods a class-based proxy cannot override.
     */
    private static Optional<Set<Definition>> enclosingTransactions(JavaCodeUnit origin, Set<JavaMethod> visiting) {
        if (!(origin instanceof JavaMethod method) || ArchitectureRuleSupport.isCompilerGenerated(method)) {
            return Optional.empty();
        }
        Set<JavaModifier> modifiers = method.getModifiers();
        if (modifiers.contains(JavaModifier.PRIVATE)) {
            return throughCallers(method, visiting);
        }
        if (modifiers.contains(JavaModifier.STATIC) || modifiers.contains(JavaModifier.FINAL)) {
            return Optional.empty();
        }
        Optional<JavaAnnotation<?>> declared = transactional(method);
        if (declared.isEmpty()) {
            declared = transactional(method.getOwner());
        }
        return declared.flatMap(Definition::of)
                .filter(definition -> GUARANTEED.contains(definition.propagation()))
                .map(Set::of);
    }

    private static Optional<Set<Definition>> throughCallers(JavaMethod method, Set<JavaMethod> visiting) {
        if (!visiting.add(method)) {
            // Already being resolved further up a recursive chain: it adds no transaction of its own.
            return Optional.of(Set.of());
        }
        Set<JavaMethodCall> calls = method.getCallsOfSelf();
        if (calls.isEmpty() || !method.getReferencesToSelf().isEmpty()) {
            return Optional.empty();
        }
        Set<Definition> transactions = new HashSet<>();
        for (JavaMethodCall call : calls) {
            if (!isPlainCall(call) || !call.getOriginOwner().equals(method.getOwner())) {
                return Optional.empty();
            }
            Optional<Set<Definition>> enclosing = enclosingTransactions(call.getOrigin(), visiting);
            if (enclosing.isEmpty()) {
                return Optional.empty();
            }
            transactions.addAll(enclosing.get());
        }
        return Optional.of(transactions);
    }

    private static Optional<JavaAnnotation<?>> transactional(HasAnnotations<?> annotated) {
        Optional<? extends JavaAnnotation<?>> spring =
                annotated.tryGetAnnotationOfType(SpringStereotypes.TRANSACTIONAL);
        if (spring.isPresent()) {
            return Optional.of(spring.get());
        }
        return annotated
                .tryGetAnnotationOfType(SpringStereotypes.JAKARTA_TRANSACTIONAL)
                .map(found -> found);
    }

    /**
     * The attributes of one {@code @Transactional} declaration that decide what the proxy would do, normalized
     * across Spring's annotation and {@code jakarta.transaction.Transactional}.
     *
     * @param manager    the transaction manager qualifier, empty for the default one
     * @param rollback   class names of the rollback rules, empty for the default rules
     * @param noRollback class names of the no-rollback rules
     * @param isolation  the isolation level name, {@code DEFAULT} when unset
     * @param timeout    the timeout as declared, empty when unset
     */
    private record Definition(
            String propagation,
            String manager,
            Set<String> rollback,
            Set<String> noRollback,
            String isolation,
            String timeout) {

        /**
         * Whether this callee, joining {@code caller}, would behave exactly as when called directly.
         *
         * <p>A different transaction manager is a different transaction, and a callee's own rollback rules decide
         * at its boundary whether the shared transaction is marked rollback-only, so both must match. Spring ignores
         * a joining callee's isolation and timeout unless {@code validateExistingTransaction} is enabled, but a callee
         * that declares its own is read as expecting them and stays reported. {@code readOnly} is not compared.
         */
        boolean joinsUnchanged(Definition caller) {
            return manager.equals(caller.manager)
                    && rollback.equals(caller.rollback)
                    && noRollback.equals(caller.noRollback)
                    && (DEFAULT_ISOLATION.equals(isolation) || isolation.equals(caller.isolation))
                    && (timeout.isEmpty() || timeout.equals(caller.timeout));
        }

        static Optional<Definition> of(JavaAnnotation<?> annotation) {
            try {
                return SpringStereotypes.TRANSACTIONAL.equals(
                                annotation.getRawType().getName())
                        ? spring(annotation)
                        : jakarta(annotation);
            } catch (RuntimeException ex) {
                return Optional.empty();
            }
        }

        private static Optional<Definition> spring(JavaAnnotation<?> annotation) {
            Optional<String> propagation = enumName(annotation, "propagation", "REQUIRED");
            Optional<String> value = string(annotation, "value");
            Optional<String> transactionManager = string(annotation, "transactionManager");
            Optional<Set<String>> rollbackFor = names(annotation, "rollbackFor");
            Optional<Set<String>> rollbackForClassName = names(annotation, "rollbackForClassName");
            Optional<Set<String>> noRollbackFor = names(annotation, "noRollbackFor");
            Optional<Set<String>> noRollbackForClassName = names(annotation, "noRollbackForClassName");
            Optional<String> isolation = enumName(annotation, "isolation", DEFAULT_ISOLATION);
            Optional<String> timeout = timeout(annotation);
            if (propagation.isEmpty()
                    || value.isEmpty()
                    || transactionManager.isEmpty()
                    || rollbackFor.isEmpty()
                    || rollbackForClassName.isEmpty()
                    || noRollbackFor.isEmpty()
                    || noRollbackForClassName.isEmpty()
                    || isolation.isEmpty()
                    || timeout.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Definition(
                    propagation.get(),
                    value.get().isEmpty() ? transactionManager.get() : value.get(),
                    union(rollbackFor.get(), rollbackForClassName.get()),
                    union(noRollbackFor.get(), noRollbackForClassName.get()),
                    isolation.get(),
                    timeout.get()));
        }

        private static Optional<Definition> jakarta(JavaAnnotation<?> annotation) {
            Optional<String> propagation = enumName(annotation, "value", "REQUIRED");
            Optional<Set<String>> rollbackOn = names(annotation, "rollbackOn");
            Optional<Set<String>> dontRollbackOn = names(annotation, "dontRollbackOn");
            if (propagation.isEmpty() || rollbackOn.isEmpty() || dontRollbackOn.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Definition(
                    propagation.get(), "", rollbackOn.get(), dontRollbackOn.get(), DEFAULT_ISOLATION, ""));
        }

        private static Optional<String> timeout(JavaAnnotation<?> annotation) {
            Optional<String> declared = string(annotation, "timeoutString");
            Object seconds = annotation.get("timeout").orElse(-1);
            if (declared.isEmpty() || !(seconds instanceof Integer value)) {
                return Optional.empty();
            }
            if (!declared.get().isEmpty()) {
                return declared;
            }
            return Optional.of(value < 0 ? "" : String.valueOf(value));
        }

        // An absent property is the annotation's own default; a value of an unexpected shape is unknown.
        private static Optional<String> enumName(JavaAnnotation<?> annotation, String property, String fallback) {
            Object value = annotation.get(property).orElse(null);
            if (value == null) {
                return Optional.of(fallback);
            }
            return value instanceof JavaEnumConstant constant ? Optional.of(constant.name()) : Optional.empty();
        }

        private static Optional<String> string(JavaAnnotation<?> annotation, String property) {
            Object value = annotation.get(property).orElse("");
            return value instanceof String text ? Optional.of(text) : Optional.empty();
        }

        private static Optional<Set<String>> names(JavaAnnotation<?> annotation, String property) {
            Object value = annotation.get(property).orElse(null);
            if (value == null) {
                return Optional.of(Set.of());
            }
            Object[] elements = value instanceof Object[] array ? array : new Object[] {value};
            Set<String> names = new TreeSet<>();
            for (Object element : elements) {
                if (element instanceof JavaClass type) {
                    names.add(type.getName());
                } else if (element instanceof String name) {
                    names.add(name);
                } else {
                    return Optional.empty();
                }
            }
            return Optional.of(names);
        }

        private static Set<String> union(Set<String> first, Set<String> second) {
            Set<String> union = new TreeSet<>(first);
            union.addAll(second);
            return union;
        }
    }
}
