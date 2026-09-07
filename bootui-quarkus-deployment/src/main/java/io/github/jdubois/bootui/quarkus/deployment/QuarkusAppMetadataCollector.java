package io.github.jdubois.bootui.quarkus.deployment;

import static io.github.jdubois.bootui.quarkus.quarkusapp.QuarkusAppMetadataStore.MAX_CLASSES;
import static io.github.jdubois.bootui.quarkus.quarkusapp.QuarkusAppMetadataStore.MAX_CLIENTS;
import static io.github.jdubois.bootui.quarkus.quarkusapp.QuarkusAppMetadataStore.MAX_MEMBERS;
import static org.jboss.resteasy.reactive.common.processor.EndpointIndexer.CDI_WRAPPER_SUFFIX;

import io.github.jdubois.bootui.quarkus.quarkusapp.QuarkusAppMetadataStore;
import io.github.jdubois.bootui.spi.QuarkusAppEvidenceProblem;
import io.github.jdubois.bootui.spi.QuarkusAppMetadata;
import io.github.jdubois.bootui.spi.QuarkusAppMetadata.RestClient;
import io.github.jdubois.bootui.spi.QuarkusAppMetadata.SharedField;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.arc.processor.InjectionPointInfo;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.resteasy.reactive.server.deployment.ResteasyReactiveResourceMethodEntriesBuildItem;
import io.quarkus.resteasy.reactive.server.deployment.SetupEndpointsResultBuildItem;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.resteasy.reactive.common.model.ResourceClass;

/** Collects only native, surviving CDI declarations and registered Quarkus REST entry methods. */
final class QuarkusAppMetadataCollector {

    private static final DotName APPLICATION = DotName.createSimple("jakarta.enterprise.context.ApplicationScoped");
    private static final DotName SINGLETON = DotName.createSimple("jakarta.inject.Singleton");
    private static final DotName REGISTER_CLIENT =
            DotName.createSimple("org.eclipse.microprofile.rest.client.inject.RegisterRestClient");
    private static final DotName CLIENT_QUALIFIER =
            DotName.createSimple("org.eclipse.microprofile.rest.client.inject.RestClient");
    private static final DotName CONFIG_PROPERTY =
            DotName.createSimple("org.eclipse.microprofile.config.inject.ConfigProperty");
    private static final DotName CONFIG_MAPPING = DotName.createSimple("io.smallrye.config.ConfigMapping");
    private static final DotName SCHEDULED = DotName.createSimple("io.quarkus.scheduler.Scheduled");
    private static final DotName SCHEDULES = DotName.createSimple("io.quarkus.scheduler.Scheduled$Schedules");
    private static final List<String> ALL_RULES =
            List.of("QA-CDI-001", "QA-CDI-002", "QA-CDI-003", "QA-PERF-002", "QA-WEB-003");

    private static final Set<String> SAFE_FINAL_TYPES = Set.of(
            "java.lang.String",
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Character",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Short",
            "java.math.BigDecimal",
            "java.math.BigInteger",
            "java.util.UUID",
            "java.time.Instant",
            "java.time.Duration",
            "java.time.Period",
            "java.time.LocalDate",
            "java.time.LocalTime",
            "java.time.LocalDateTime",
            "java.time.OffsetTime",
            "java.time.OffsetDateTime",
            "java.time.ZonedDateTime",
            "java.time.ZoneId",
            "java.time.ZoneOffset",
            "java.time.Year",
            "java.time.YearMonth",
            "java.time.MonthDay",
            "java.util.concurrent.atomic.AtomicBoolean",
            "java.util.concurrent.atomic.AtomicInteger",
            "java.util.concurrent.atomic.AtomicLong",
            "java.util.concurrent.atomic.AtomicReference",
            "java.util.concurrent.atomic.AtomicIntegerArray",
            "java.util.concurrent.atomic.AtomicLongArray",
            "java.util.concurrent.atomic.AtomicReferenceArray",
            "java.util.concurrent.atomic.AtomicMarkableReference",
            "java.util.concurrent.atomic.AtomicStampedReference",
            "java.util.concurrent.atomic.LongAdder",
            "java.util.concurrent.atomic.DoubleAdder",
            "java.util.concurrent.atomic.LongAccumulator",
            "java.util.concurrent.atomic.DoubleAccumulator",
            "java.util.concurrent.ConcurrentMap",
            "java.util.concurrent.ConcurrentNavigableMap",
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.concurrent.ConcurrentSkipListMap",
            "java.util.concurrent.ConcurrentSkipListSet",
            "java.util.concurrent.ConcurrentLinkedQueue",
            "java.util.concurrent.ConcurrentLinkedDeque",
            "java.util.concurrent.CopyOnWriteArrayList",
            "java.util.concurrent.CopyOnWriteArraySet",
            "java.util.concurrent.BlockingQueue",
            "java.util.concurrent.BlockingDeque",
            "java.util.concurrent.ArrayBlockingQueue",
            "java.util.concurrent.LinkedBlockingQueue",
            "java.util.concurrent.LinkedBlockingDeque",
            "java.util.concurrent.LinkedTransferQueue",
            "java.util.concurrent.PriorityBlockingQueue",
            "java.util.concurrent.DelayQueue",
            "java.util.concurrent.SynchronousQueue");

    private static final Set<String> REST_INJECTED_FIELDS = Set.of(
            "jakarta.ws.rs.core.Context",
            "jakarta.ws.rs.PathParam",
            "jakarta.ws.rs.QueryParam",
            "jakarta.ws.rs.HeaderParam",
            "jakarta.ws.rs.FormParam",
            "jakarta.ws.rs.MatrixParam",
            "jakarta.ws.rs.CookieParam",
            "jakarta.ws.rs.BeanParam",
            "org.jboss.resteasy.reactive.RestPath",
            "org.jboss.resteasy.reactive.RestQuery",
            "org.jboss.resteasy.reactive.RestHeader",
            "org.jboss.resteasy.reactive.RestForm",
            "org.jboss.resteasy.reactive.RestMatrix",
            "org.jboss.resteasy.reactive.RestCookie");

    private QuarkusAppMetadataCollector() {}

    static QuarkusAppMetadata collect(
            IndexView application,
            IndexView combined,
            Iterable<BeanInfo> survivingBeans,
            Collection<InjectionPointInfo> injectionPoints,
            Optional<ResteasyReactiveResourceMethodEntriesBuildItem> entries,
            Optional<SetupEndpointsResultBuildItem> endpoints,
            Capabilities capabilities) {
        State state = new State(capabilities);
        try {
            Collection<ClassInfo> knownClasses = application.getKnownClasses();
            if (knownClasses.size() > MAX_CLASSES || injectionPoints.size() > MAX_MEMBERS) {
                state.problem(ALL_RULES, QuarkusAppMetadataStore.INCOMPLETE);
                return state.snapshot();
            }
            List<ClassInfo> classes = knownClasses.stream()
                    .sorted(Comparator.comparing(info -> info.name().toString()))
                    .toList();
            Map<String, BeanInfo> beans = resolvedBeans(application, survivingBeans, state);
            state.beanCount = beans.size();
            Set<String> resources = registeredEndpoints(application, entries, endpoints, beans.keySet(), state);
            for (Map.Entry<String, BeanInfo> entry : beans.entrySet()) {
                BeanInfo bean = entry.getValue();
                DotName scope = bean.getScope().getDotName();
                if (!scope.equals(APPLICATION) && !scope.equals(SINGLETON)) {
                    continue;
                }
                String resolvedScope = scope.equals(APPLICATION) ? "APPLICATION" : "SINGLETON";
                String rule = resources.contains(entry.getKey())
                        ? "QA-CDI-002"
                        : scope.equals(APPLICATION) ? "QA-CDI-001" : "QA-CDI-003";
                try {
                    Set<FieldKey> injected = new HashSet<>();
                    for (InjectionPointInfo point : bean.getAllInjectionPoints()) {
                        state.members.take();
                        AnnotationTarget target = point.getAnnotationTarget();
                        if (target != null && target.kind() == AnnotationTarget.Kind.FIELD) {
                            injected.add(fieldId(target.asField()));
                        }
                    }
                    collectFields(
                            bean.getTarget().orElseThrow().asClass(),
                            combined,
                            resolvedScope,
                            resources.contains(entry.getKey()),
                            injected,
                            state.fields,
                            state.members,
                            state.fieldCharacters);
                } catch (LimitReached exception) {
                    state.problem(List.of(rule), QuarkusAppMetadataStore.INCOMPLETE);
                } catch (RuntimeException exception) {
                    state.problem(List.of(rule), QuarkusAppMetadataStore.UNRESOLVED);
                }
            }
            declarations(classes, state);
        } catch (RuntimeException exception) {
            state.problem(ALL_RULES, QuarkusAppMetadataStore.UNRESOLVED);
        }
        return state.snapshot();
    }

    private static Map<String, BeanInfo> resolvedBeans(
            IndexView application, Iterable<BeanInfo> survivingBeans, State state) {
        Map<String, BeanInfo> beans = new TreeMap<>();
        Iterator<BeanInfo> iterator = survivingBeans.iterator();
        int inspected = 0;
        while (iterator.hasNext()) {
            if (++inspected > MAX_MEMBERS) {
                // A nondeterministic stream prefix must not be presented as a deterministic application inventory.
                beans.clear();
                state.clients.clear();
                state.problem(ALL_RULES, QuarkusAppMetadataStore.INCOMPLETE);
                return beans;
            }
            BeanInfo bean = iterator.next();
            if (bean.isClassBean()
                    && bean.getBeanClass() != null
                    && application.getClassByName(bean.getBeanClass()) != null) {
                String name = bean.getBeanClass().toString();
                if (name.length() > QuarkusAppMetadataStore.MAX_STRING_BYTES) {
                    state.problem(ALL_RULES, QuarkusAppMetadataStore.INCOMPLETE);
                } else {
                    beans.put(name, bean);
                }
            }
            if (state.restClientSupported
                    && bean.getQualifiers().stream()
                            .anyMatch(annotation -> annotation.name().equals(CLIENT_QUALIFIER))) {
                for (Type type : bean.getTypes()) {
                    if (++inspected > MAX_MEMBERS) {
                        state.clients.clear();
                        state.problem(List.of("QA-WEB-003"), QuarkusAppMetadataStore.INCOMPLETE);
                        break;
                    }
                    ClassInfo client = application.getClassByName(type.name());
                    if (client == null || !client.isInterface()) {
                        continue;
                    }
                    AnnotationInstance registration = client.declaredAnnotation(REGISTER_CLIENT);
                    boolean managedWrapper = bean.getBeanClass() != null
                            && bean.getBeanClass().toString().equals(client.name() + CDI_WRAPPER_SUFFIX);
                    if (registration == null) {
                        if (managedWrapper) {
                            state.problem(List.of("QA-WEB-003"), QuarkusAppMetadataStore.UNRESOLVED);
                        }
                        continue;
                    }
                    if (!managedWrapper) {
                        // A user-supplied @RestClient implementation need not use Quarkus' managed transport.
                        state.problem(List.of("QA-WEB-003"), QuarkusAppMetadataStore.UNRESOLVED);
                        continue;
                    }
                    if (state.clients.size() <= MAX_CLIENTS) {
                        String configKey = registration.value("configKey") == null
                                ? ""
                                : registration.value("configKey").asString();
                        if (client.name().toString().length() > QuarkusAppMetadataStore.MAX_STRING_BYTES
                                || configKey.length() > QuarkusAppMetadataStore.MAX_STRING_BYTES) {
                            state.problem(List.of("QA-WEB-003"), QuarkusAppMetadataStore.INCOMPLETE);
                            continue;
                        }
                        state.clients.put(
                                client.name().toString(),
                                new RestClient(client.name().toString(), configKey));
                    }
                }
            }
        }
        if (state.clients.size() > MAX_CLIENTS) {
            state.clients.clear();
            state.problem(List.of("QA-WEB-003"), QuarkusAppMetadataStore.INCOMPLETE);
        }
        return beans;
    }

    private static Set<String> registeredEndpoints(
            IndexView application,
            Optional<ResteasyReactiveResourceMethodEntriesBuildItem> entries,
            Optional<SetupEndpointsResultBuildItem> endpoints,
            Set<String> beans,
            State state) {
        Set<String> resources = new TreeSet<>();
        if (entries.isEmpty() && endpoints.isEmpty()) {
            return resources; // Quarkus REST produces neither build item when there are no resources.
        }
        if (entries.isEmpty() || endpoints.isEmpty()) {
            state.problem(List.of("QA-CDI-002", "QA-PERF-002"), QuarkusAppMetadataStore.UNRESOLVED);
            return resources;
        }
        if (entries.get().getEntries().size() > MAX_MEMBERS
                || endpoints.get().getResourceClasses().size() > MAX_CLASSES
                || endpoints.get().getSubResourceClasses().size() > MAX_CLASSES) {
            state.problem(List.of("QA-CDI-002", "QA-PERF-002"), QuarkusAppMetadataStore.INCOMPLETE);
            return resources;
        }
        Set<String> rootClasses = new HashSet<>();
        Set<String> subresourceClasses = new HashSet<>();
        for (ResourceClass resource : endpoints.get().getResourceClasses()) {
            if (beans.contains(resource.getClassName())) {
                rootClasses.add(resource.getClassName());
            }
        }
        resources.addAll(rootClasses);
        for (ResourceClass resource : endpoints.get().getSubResourceClasses()) {
            subresourceClasses.add(resource.getClassName());
        }
        Set<MethodKey> methodIds = new HashSet<>();
        List<ResteasyReactiveResourceMethodEntriesBuildItem.Entry> ordered = entries.get().getEntries().stream()
                .filter(entry -> entry.getActualClassInfo() != null)
                .sorted(Comparator.comparing((ResteasyReactiveResourceMethodEntriesBuildItem.Entry entry) ->
                                entry.getActualClassInfo().name().toString())
                        .thenComparing(entry -> entry.getMethodInfo().name())
                        .thenComparing(entry -> entry.getMethodInfo().toString()))
                .toList();
        int remainingMethodCharacters = QuarkusAppMetadataStore.MAX_RESOURCE_BYTES / 4;
        for (var entry : ordered) {
            try {
                state.members.take();
            } catch (LimitReached exception) {
                state.problem(List.of("QA-CDI-002", "QA-PERF-002"), QuarkusAppMetadataStore.INCOMPLETE);
                break;
            }
            if (entry.getActualClassInfo() == null || entry.getResourceMethod().getHttpMethod() == null) {
                continue; // Subresource locators are not endpoint invocation methods.
            }
            String owner = entry.getActualClassInfo().name().toString();
            if (application.getClassByName(entry.getActualClassInfo().name()) == null) {
                continue;
            }
            if (!rootClasses.contains(owner)) {
                // A locator can return a non-CDI object; its dispatch and sharing cannot be inferred from a class bean.
                if (subresourceClasses.contains(owner)) {
                    state.problem(List.of("QA-CDI-002", "QA-PERF-002"), QuarkusAppMetadataStore.UNRESOLVED);
                }
                continue;
            }
            resources.add(owner);
            methodIds.add(new MethodKey(entry.getActualClassInfo().name(), entry.getMethodInfo()));
            if (entry.getResourceMethod().isRunOnVirtualThread()
                    && Modifier.isSynchronized(entry.getMethodInfo().flags())) {
                try {
                    String methodId = methodId(owner, entry.getMethodInfo());
                    if (methodId.length() > remainingMethodCharacters) {
                        throw new LimitReached();
                    }
                    if (state.virtualMethods.add(methodId)) {
                        remainingMethodCharacters -= methodId.length();
                    }
                } catch (LimitReached exception) {
                    state.problem(List.of("QA-PERF-002"), QuarkusAppMetadataStore.INCOMPLETE);
                }
            }
        }
        state.endpointCount = methodIds.size();
        return resources;
    }

    static void collectFields(
            ClassInfo beanClass,
            IndexView index,
            String scope,
            boolean resource,
            Set<FieldKey> injectedFields,
            List<SharedField> result,
            Budget budget) {
        collectFields(
                beanClass,
                index,
                scope,
                resource,
                injectedFields,
                result,
                budget,
                new Budget(QuarkusAppMetadataStore.MAX_RESOURCE_BYTES / 3));
    }

    private static void collectFields(
            ClassInfo beanClass,
            IndexView index,
            String scope,
            boolean resource,
            Set<FieldKey> injectedFields,
            List<SharedField> result,
            Budget budget,
            Budget characters) {
        String beanName = beanClass.name().toString();
        ClassInfo current = beanClass;
        Set<DotName> seen = new HashSet<>();
        while (current != null && seen.add(current.name())) {
            budget.take();
            if (current.fields().size() > budget.remaining) {
                throw new LimitReached();
            }
            for (FieldInfo field : current.fields().stream()
                    .sorted(Comparator.comparing(FieldInfo::name))
                    .toList()) {
                budget.take();
                if (isPublicState(field, injectedFields, resource)) {
                    if (beanName.length() > QuarkusAppMetadataStore.MAX_STRING_BYTES
                            || field.name().length() > QuarkusAppMetadataStore.MAX_STRING_BYTES) {
                        throw new LimitReached();
                    }
                    characters.take(beanName.length() + field.name().length() + scope.length() + 20);
                    result.add(new SharedField(beanName, field.name(), scope, resource));
                }
            }
            DotName parent = current.superName();
            if (parent == null || parent.toString().equals("java.lang.Object")) {
                return;
            }
            current = index.getClassByName(parent);
            if (current == null) {
                throw new IllegalStateException();
            }
        }
    }

    static boolean isPublicState(FieldInfo field, Set<FieldKey> injectedFields, boolean resource) {
        if (!Modifier.isPublic(field.flags())
                || Modifier.isStatic(field.flags())
                || injectedFields.contains(fieldId(field))) {
            return false;
        }
        if (resource
                && REST_INJECTED_FIELDS.stream()
                        .anyMatch(annotation -> field.hasAnnotation(DotName.createSimple(annotation)))) {
            return false;
        }
        return !Modifier.isFinal(field.flags())
                || (field.type().kind() != Type.Kind.PRIMITIVE
                        && !SAFE_FINAL_TYPES.contains(field.type().name().toString()));
    }

    private static void declarations(List<ClassInfo> classes, State state) {
        try {
            for (ClassInfo info : classes) {
                if (info.declaredAnnotation(CONFIG_MAPPING) != null) {
                    state.configMappings++;
                }
                for (FieldInfo field : info.fields()) {
                    state.members.take();
                    if (field.hasAnnotation(CONFIG_PROPERTY)) {
                        state.configProperties++;
                    }
                }
                for (MethodInfo method : info.methods()) {
                    state.members.take();
                    for (AnnotationInstance annotation : method.annotations()) {
                        state.members.take();
                        if (annotation.name().equals(CONFIG_PROPERTY)) {
                            state.configProperties++;
                        } else if (annotation.name().equals(SCHEDULED)) {
                            state.scheduled++;
                        } else if (annotation.name().equals(SCHEDULES) && annotation.value() != null) {
                            int count = annotation.value().asNestedArray().length;
                            state.members.take(count);
                            state.scheduled += count;
                        }
                    }
                }
            }
        } catch (LimitReached exception) {
            state.problem(ALL_RULES, QuarkusAppMetadataStore.INCOMPLETE);
        }
    }

    private static FieldKey fieldId(FieldInfo field) {
        return new FieldKey(field.declaringClass().name(), field.name());
    }

    static String methodId(String owner, MethodInfo method) {
        StringBuilder id = new StringBuilder();
        appendIdentity(id, owner);
        appendIdentity(id, "#" + method.name() + "(");
        boolean first = true;
        for (Type parameter : method.parameterTypes()) {
            appendIdentity(id, (first ? "" : ",") + parameter);
            first = false;
        }
        appendIdentity(id, ")");
        return id.toString();
    }

    private static void appendIdentity(StringBuilder identity, String part) {
        if (part.length() > QuarkusAppMetadataStore.MAX_STRING_BYTES - identity.length()) {
            throw new LimitReached();
        }
        identity.append(part);
    }

    private record MethodKey(DotName owner, MethodInfo method) {}

    record FieldKey(DotName owner, String name) {}

    static final class Budget {
        private int remaining;

        Budget(int remaining) {
            this.remaining = remaining;
        }

        void take() {
            take(1);
        }

        void take(int amount) {
            if (amount < 0 || amount > remaining) {
                throw new LimitReached();
            }
            remaining -= amount;
        }
    }

    private static final class LimitReached extends RuntimeException {}

    private static final class State {
        private final boolean hibernateSupported;
        private final boolean jdbcSupported;
        private final boolean restClientSupported;
        private final Budget members = new Budget(MAX_MEMBERS);
        private final Budget fieldCharacters = new Budget(QuarkusAppMetadataStore.MAX_RESOURCE_BYTES / 3);
        private final List<SharedField> fields = new ArrayList<>();
        private final Set<String> virtualMethods = new TreeSet<>();
        private final Map<String, RestClient> clients = new TreeMap<>();
        private final Set<QuarkusAppEvidenceProblem> problems =
                new TreeSet<>(Comparator.comparing(QuarkusAppEvidenceProblem::ruleId)
                        .thenComparing(QuarkusAppEvidenceProblem::message));
        private int beanCount;
        private int endpointCount;
        private int configProperties;
        private int configMappings;
        private int scheduled;

        State(Capabilities capabilities) {
            hibernateSupported = capabilities.isPresent(Capability.HIBERNATE_ORM);
            jdbcSupported = capabilities.isPresent(Capability.AGROAL);
            restClientSupported = capabilities.isPresent(Capability.REST_CLIENT_REACTIVE);
        }

        void problem(List<String> rules, String message) {
            rules.forEach(rule -> problems.add(new QuarkusAppEvidenceProblem(rule, message)));
        }

        QuarkusAppMetadata snapshot() {
            return new QuarkusAppMetadata(
                    true,
                    beanCount,
                    endpointCount,
                    configProperties,
                    configMappings,
                    scheduled,
                    hibernateSupported,
                    jdbcSupported,
                    restClientSupported,
                    fields,
                    List.copyOf(virtualMethods),
                    List.copyOf(clients.values()),
                    List.copyOf(problems));
        }
    }
}
