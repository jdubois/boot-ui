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
import org.jboss.resteasy.reactive.server.model.ServerResourceMethod;

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
    private static final DotName RUN_ON_VIRTUAL_THREAD =
            DotName.createSimple("io.smallrye.common.annotation.RunOnVirtualThread");
    private static final DotName KOTLIN_METADATA = DotName.createSimple("kotlin.Metadata");
    private static final Set<DotName> METHOD_ONLY_VIRTUAL_THREAD_ENTRY_ANNOTATIONS =
            Set.of(SCHEDULED, SCHEDULES, DotName.createSimple("io.quarkus.vertx.ConsumeEvent"));
    private static final Set<DotName> CLASS_OR_METHOD_VIRTUAL_THREAD_ENTRY_ANNOTATIONS = Set.of(
            DotName.createSimple("org.eclipse.microprofile.reactive.messaging.Incoming"),
            DotName.createSimple("org.eclipse.microprofile.reactive.messaging.Incomings"),
            DotName.createSimple("org.eclipse.microprofile.reactive.messaging.Outgoing"),
            DotName.createSimple("org.eclipse.microprofile.reactive.messaging.Outgoings"),
            DotName.createSimple("io.smallrye.reactive.messaging.annotations.Incomings"),
            DotName.createSimple("io.smallrye.reactive.messaging.annotations.Outgoings"),
            DotName.createSimple("io.quarkus.websockets.next.OnOpen"),
            DotName.createSimple("io.quarkus.websockets.next.OnTextMessage"),
            DotName.createSimple("io.quarkus.websockets.next.OnBinaryMessage"),
            DotName.createSimple("io.quarkus.websockets.next.OnPingMessage"),
            DotName.createSimple("io.quarkus.websockets.next.OnPongMessage"),
            DotName.createSimple("io.quarkus.websockets.next.OnClose"),
            DotName.createSimple("io.quarkus.websockets.next.OnError"));
    private static final List<String> ALL_RULES = List.of(
            "QA-CDI-002",
            "QA-CDI-003",
            "QA-CDI-004",
            "QA-CDI-005",
            "QA-CDI-006",
            "QA-PERF-002",
            "QA-PERF-003",
            "QA-PERF-004",
            "QA-WEB-003");
    private static final Set<DotName> REACTIVE_RETURN_TYPES = Set.of(
            DotName.createSimple("io.smallrye.mutiny.Uni"),
            DotName.createSimple("io.smallrye.mutiny.Multi"),
            DotName.createSimple("java.util.concurrent.CompletionStage"),
            DotName.createSimple("java.util.concurrent.CompletableFuture"),
            DotName.createSimple("java.util.concurrent.Flow$Publisher"),
            DotName.createSimple("org.reactivestreams.Publisher"));
    private static final DotName REST_RESPONSE = DotName.createSimple("org.jboss.resteasy.reactive.RestResponse");

    private static final Set<String> IMMUTABLE_VALUE_TYPES = Set.of(
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
            "java.time.MonthDay");

    private static final Set<String> THREAD_SAFE_TYPES = Set.of(
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
            Map<String, BeanInfo> beans = resolvedBeans(application, combined, survivingBeans, state);
            Set<String> resources =
                    registeredEndpoints(application, combined, entries, endpoints, beans.keySet(), state);
            collectBeanVirtualThreadMethods(beans.values(), combined, state);
            for (Map.Entry<String, BeanInfo> entry : beans.entrySet()) {
                BeanInfo bean = entry.getValue();
                DotName scope = bean.getScope().getDotName();
                boolean normal = bean.getScope().isNormal();
                if (!normal && !scope.equals(SINGLETON)) {
                    continue;
                }
                state.beanCount++;
                String resolvedScope = scope.equals(APPLICATION) ? "APPLICATION" : normal ? "NORMAL" : "SINGLETON";
                String rule = normal ? "QA-CDI-004" : resources.contains(entry.getKey()) ? "QA-CDI-002" : "QA-CDI-003";
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
            IndexView application, IndexView combined, Iterable<BeanInfo> survivingBeans, State state) {
        Map<String, BeanInfo> beans = new TreeMap<>();
        Iterator<BeanInfo> iterator = survivingBeans.iterator();
        int inspected = 0;
        while (iterator.hasNext()) {
            if (++inspected > MAX_MEMBERS) {
                // A nondeterministic stream prefix must not be presented as a deterministic application inventory.
                beans.clear();
                state.clients.clear();
                state.unproxyableBeans.clear();
                state.defaultPackageBeans.clear();
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
                    String pkg = bean.getBeanClass().packagePrefix();
                    if (pkg == null || pkg.isEmpty()) {
                        state.defaultPackageBeans.add(name);
                    }
                    if (bean.getScope().isNormal()) {
                        ClassInfo beanClass = bean.getTarget()
                                .filter(t -> t.kind() == AnnotationTarget.Kind.CLASS)
                                .map(AnnotationTarget::asClass)
                                .orElseGet(() -> combined.getClassByName(bean.getBeanClass()));
                        if (beanClass != null && isUnproxyableNormalScopedClass(bean, beanClass, combined)) {
                            state.unproxyableBeans.add(name);
                        }
                    }
                }
            } else if ((bean.isProducerMethod() || bean.isProducerField())
                    && bean.getBeanClass() != null
                    && application.getClassByName(bean.getBeanClass()) != null
                    && bean.getScope().isNormal()) {
                collectUnproxyableProducer(bean, combined, state);
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
                    ClassInfo client = combined.getClassByName(type.name());
                    if (client == null) {
                        client = application.getClassByName(type.name());
                    }
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
                        // A user-supplied @RestClient implementation or test mock need not use Quarkus' managed
                        // transport.
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
            IndexView combined,
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
            if (beans.contains(resource.getClassName())) {
                resources.add(resource.getClassName());
            }
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
            if (!rootClasses.contains(owner) && !subresourceClasses.contains(owner)) {
                continue;
            }
            if (beans.contains(owner)) {
                resources.add(owner);
            }
            methodIds.add(new MethodKey(entry.getActualClassInfo().name(), entry.getMethodInfo()));
            if (entry.getResourceMethod().isRunOnVirtualThread()) {
                try {
                    MethodInfo implementation = endpointImplementation(entry, combined);
                    if (implementation == null || Modifier.isAbstract(implementation.flags())) {
                        state.problem(List.of("QA-PERF-002"), QuarkusAppMetadataStore.UNRESOLVED);
                        MethodInfo declared = entry.getMethodInfo();
                        if (declared != null && isReactiveReturnType(declared.returnType())) {
                            addBoundedMethod(state.reactiveVirtualMethods, methodId(owner, declared));
                        }
                        continue;
                    }
                    String methodId = methodId(owner, implementation);
                    addBoundedMethod(state.allVirtualMethods, methodId);
                    if (isReactiveReturnType(implementation.returnType())
                            || isReactiveReturnType(entry.getMethodInfo().returnType())) {
                        addBoundedMethod(state.reactiveVirtualMethods, methodId);
                    }
                    if (!Modifier.isSynchronized(implementation.flags())) {
                        continue;
                    }
                    if (methodId.length() > remainingMethodCharacters) {
                        throw new LimitReached();
                    }
                    if (state.virtualMethods.add(methodId)) {
                        remainingMethodCharacters -= methodId.length();
                    }
                } catch (LimitReached exception) {
                    state.problem(
                            List.of("QA-PERF-002", "QA-PERF-003", "QA-PERF-004"), QuarkusAppMetadataStore.INCOMPLETE);
                }
            }
        }
        state.endpointCount = methodIds.size();
        return resources;
    }

    private static void collectBeanVirtualThreadMethods(Collection<BeanInfo> beans, IndexView index, State state) {
        int remainingMethodCharacters = QuarkusAppMetadataStore.MAX_RESOURCE_BYTES / 4;
        for (BeanInfo bean : beans) {
            Optional<AnnotationTarget> target = bean.getTarget();
            if (target.isEmpty() || target.get().kind() != AnnotationTarget.Kind.CLASS) {
                continue;
            }
            ClassInfo beanClass = target.get().asClass();
            String owner = beanClass.name().toString();
            boolean beanVirtualThread = beanClass.hasDeclaredAnnotation(RUN_ON_VIRTUAL_THREAD);
            ClassInfo current = beanClass;
            Set<DotName> seen = new HashSet<>();
            try {
                while (current != null && seen.add(current.name())) {
                    boolean classVirtualThread =
                            beanVirtualThread || current.hasDeclaredAnnotation(RUN_ON_VIRTUAL_THREAD);
                    for (MethodInfo method : current.methods().stream()
                            .sorted(Comparator.comparing(MethodInfo::name).thenComparing(MethodInfo::toString))
                            .toList()) {
                        if (Modifier.isStatic(method.flags())
                                || Modifier.isAbstract(method.flags())
                                || method.isSynthetic()) {
                            continue;
                        }
                        boolean nonRestEntry = isNonRestVirtualThreadEntryMethod(method, classVirtualThread);
                        boolean directVirtualThread = method.hasDeclaredAnnotation(RUN_ON_VIRTUAL_THREAD);
                        if ((nonRestEntry || directVirtualThread) && isReactiveReturnType(method.returnType())) {
                            addBoundedMethod(state.reactiveVirtualMethods, methodId(owner, method));
                        }
                        if (!nonRestEntry) {
                            continue;
                        }
                        String methodId = methodId(owner, method);
                        addBoundedMethod(state.allVirtualMethods, methodId);
                        if (!Modifier.isSynchronized(method.flags())) {
                            continue;
                        }
                        if (methodId.length() > remainingMethodCharacters) {
                            throw new LimitReached();
                        }
                        if (state.virtualMethods.add(methodId)) {
                            remainingMethodCharacters -= methodId.length();
                        }
                    }
                    DotName parent = current.superName();
                    if (parent == null
                            || parent.toString().startsWith("java.")
                            || parent.toString().startsWith("jakarta.")) {
                        break;
                    }
                    current = index.getClassByName(parent);
                }
            } catch (LimitReached exception) {
                state.problem(List.of("QA-PERF-002", "QA-PERF-003", "QA-PERF-004"), QuarkusAppMetadataStore.INCOMPLETE);
                return;
            }
        }
    }

    private static void addBoundedMethod(Set<String> target, String methodId) {
        if (methodId.length() > QuarkusAppMetadataStore.MAX_STRING_BYTES || target.size() >= MAX_MEMBERS) {
            throw new LimitReached();
        }
        target.add(methodId);
    }

    private static boolean isReactiveReturnType(Type returnType) {
        if (returnType == null) {
            return false;
        }
        Type candidate = returnType;
        if (candidate.kind() == Type.Kind.PARAMETERIZED_TYPE
                && candidate.name().equals(REST_RESPONSE)
                && !candidate.asParameterizedType().arguments().isEmpty()) {
            candidate = candidate.asParameterizedType().arguments().get(0);
        }
        return REACTIVE_RETURN_TYPES.contains(candidate.name());
    }

    private static void collectUnproxyableProducer(BeanInfo bean, IndexView combined, State state) {
        AnnotationTarget target = bean.getTarget().orElse(null);
        if (target == null) {
            return;
        }
        try {
            if (bean.isProducerMethod() && target.kind() == AnnotationTarget.Kind.METHOD) {
                MethodInfo method = target.asMethod();
                if (isUnproxyableProducerReturnType(bean, method.returnType(), combined)) {
                    String id = methodId(bean.getBeanClass().toString(), method);
                    addBoundedMethod(state.unproxyableBeans, id);
                }
            } else if (bean.isProducerField() && target.kind() == AnnotationTarget.Kind.FIELD) {
                FieldInfo field = target.asField();
                if (isUnproxyableProducerReturnType(bean, field.type(), combined)) {
                    String id = bean.getBeanClass().toString() + "." + field.name();
                    addBoundedMethod(state.unproxyableBeans, id);
                }
            }
        } catch (LimitReached exception) {
            state.problem(List.of("QA-CDI-005"), QuarkusAppMetadataStore.INCOMPLETE);
        }
    }

    private static boolean isUnproxyableProducerReturnType(BeanInfo bean, Type returnType, IndexView combined) {
        if (returnType == null
                || returnType.kind() == Type.Kind.PRIMITIVE
                || returnType.kind() == Type.Kind.ARRAY
                || returnType.kind() == Type.Kind.VOID) {
            return false;
        }
        DotName typeName = returnType.name();
        ClassInfo typeInfo = combined == null ? null : combined.getClassByName(typeName);
        if (typeName.toString().startsWith("java.")) {
            if (typeInfo != null) {
                return !Modifier.isInterface(typeInfo.flags());
            }
            try {
                Class<?> clazz =
                        Class.forName(typeName.toString(), false, QuarkusAppMetadataCollector.class.getClassLoader());
                return !clazz.isInterface();
            } catch (ClassNotFoundException | RuntimeException | LinkageError ignored) {
                return false;
            }
        }
        return typeInfo != null && isUnproxyableNormalScopedClass(bean, typeInfo, combined);
    }

    private static boolean isUnproxyableNormalScopedClass(BeanInfo bean, ClassInfo typeInfo, IndexView combined) {
        if (Modifier.isInterface(typeInfo.flags())) {
            return false;
        }
        if (typeInfo.name().toString().startsWith("java.")) {
            return true;
        }
        if (hasRuntimeDeferredUnproxyableError(bean)) {
            return true;
        }
        if (typeInfo.isSealed()) {
            return true;
        }
        boolean transformUnproxyable = isTransformUnproxyableClasses(bean);
        if (typeInfo.isFinal() && !typeInfo.isRecord() && !transformUnproxyable) {
            return true;
        }
        MethodInfo noArgs = typeInfo.method("<init>");
        if (noArgs == null) {
            if (!transformUnproxyable) {
                return true;
            }
            DotName superName = typeInfo.superName();
            if (superName != null && !superName.toString().equals("java.lang.Object")) {
                return !superHasAccessibleNoArgsConstructor(superName, combined);
            }
            return false;
        }
        return Modifier.isPrivate(noArgs.flags()) && !transformUnproxyable;
    }

    private static boolean superHasAccessibleNoArgsConstructor(DotName superName, IndexView combined) {
        if (combined != null) {
            ClassInfo superClass = combined.getClassByName(superName);
            if (superClass != null) {
                MethodInfo superNoArgs = superClass.method("<init>");
                return superNoArgs != null && !Modifier.isPrivate(superNoArgs.flags());
            }
        }
        String name = superName.toString();
        if (name.startsWith("java.") || name.startsWith("jakarta.")) {
            try {
                Class<?> superClazz = Class.forName(name, false, QuarkusAppMetadataCollector.class.getClassLoader());
                var ctor = superClazz.getDeclaredConstructor();
                return !Modifier.isPrivate(ctor.getModifiers());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean hasRuntimeDeferredUnproxyableError(BeanInfo bean) {
        try {
            var getDeployment = BeanInfo.class.getDeclaredMethod("getDeployment");
            getDeployment.setAccessible(true);
            Object deployment = getDeployment.invoke(bean);
            if (deployment == null) {
                return false;
            }
            var hasDeferred =
                    deployment.getClass().getDeclaredMethod("hasRuntimeDeferredUnproxyableError", BeanInfo.class);
            hasDeferred.setAccessible(true);
            return Boolean.TRUE.equals(hasDeferred.invoke(deployment, bean));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean isTransformUnproxyableClasses(BeanInfo bean) {
        try {
            var getDeployment = BeanInfo.class.getDeclaredMethod("getDeployment");
            getDeployment.setAccessible(true);
            Object deployment = getDeployment.invoke(bean);
            if (deployment == null) {
                return true;
            }
            var field = deployment.getClass().getDeclaredField("transformUnproxyableClasses");
            field.setAccessible(true);
            return field.getBoolean(deployment);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return true;
        }
    }

    private static boolean isNonRestVirtualThreadEntryMethod(MethodInfo method, boolean classVirtualThread) {
        boolean methodVirtualThread = method.hasDeclaredAnnotation(RUN_ON_VIRTUAL_THREAD);
        if (methodVirtualThread) {
            for (DotName entryAnnotation : METHOD_ONLY_VIRTUAL_THREAD_ENTRY_ANNOTATIONS) {
                if (method.hasDeclaredAnnotation(entryAnnotation)) {
                    return true;
                }
            }
        }
        if (methodVirtualThread || classVirtualThread) {
            for (DotName entryAnnotation : CLASS_OR_METHOD_VIRTUAL_THREAD_ENTRY_ANNOTATIONS) {
                if (method.hasDeclaredAnnotation(entryAnnotation)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static MethodInfo endpointImplementation(
            ResteasyReactiveResourceMethodEntriesBuildItem.Entry entry, IndexView index) {
        if (!(entry.getResourceMethod() instanceof ServerResourceMethod method)
                || method.getActualDeclaringClassName() == null) {
            return null;
        }
        // Quarkus already resolved the invoked implementation separately from inherited REST annotations.
        ClassInfo implementation = index.getClassByName(method.getActualDeclaringClassName());
        return implementation == null
                ? null
                : implementation.method(
                        entry.getMethodInfo().name(), entry.getMethodInfo().parameterTypes());
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
                if (isPublicState(field, index, injectedFields, resource, !scope.equals("SINGLETON"))) {
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
                String parentName = parent.toString();
                if (parentName.startsWith("java.") || parentName.startsWith("jakarta.")) {
                    return;
                }
                throw new IllegalStateException();
            }
        }
    }

    /**
     * A normal-scoped bean is reached through ArC's shared client proxy, a subclass instance with its own copy of every
     * field, so a thread-safe holder there is a second, diverging object. Final primitives and immutable values remain
     * excluded to limit noise, although constructor-assigned values can still differ on the proxy.
     */
    static boolean isPublicState(FieldInfo field, Set<FieldKey> injectedFields, boolean resource, boolean proxied) {
        return isPublicState(field, null, injectedFields, resource, proxied);
    }

    static boolean isPublicState(
            FieldInfo field, IndexView index, Set<FieldKey> injectedFields, boolean resource, boolean proxied) {
        if (!Modifier.isPublic(field.flags())
                || Modifier.isStatic(field.flags())
                || field.isSynthetic()
                || injectedFields.contains(fieldId(field))
                || isKotlinAccessorBackedProperty(field)) {
            return false;
        }
        if (REST_INJECTED_FIELDS.stream()
                .anyMatch(annotation -> field.hasAnnotation(DotName.createSimple(annotation)))) {
            return false;
        }
        if (!Modifier.isFinal(field.flags()) || field.type().kind() == Type.Kind.PRIMITIVE) {
            return !Modifier.isFinal(field.flags());
        }
        if (isEnumType(field.type().name(), index)) {
            return false;
        }
        String type = field.type().name().toString();
        return !IMMUTABLE_VALUE_TYPES.contains(type) && (proxied || !THREAD_SAFE_TYPES.contains(type));
    }

    private static boolean isKotlinAccessorBackedProperty(FieldInfo field) {
        ClassInfo owner = field.declaringClass();
        String name = field.name();
        if (owner == null || name == null || name.isEmpty() || !owner.hasDeclaredAnnotation(KOTLIN_METADATA)) {
            return false;
        }
        boolean isPrefixed = name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2));
        String getterName = isPrefixed ? name : "get" + capitalize(name);
        String setterName = "set" + (isPrefixed ? name.substring(2) : capitalize(name));
        MethodInfo getter = owner.method(getterName);
        MethodInfo setter = owner.method(setterName, field.type());
        return isKotlinAccessor(getter, field.type())
                && isKotlinAccessor(setter, Type.create(DotName.createSimple("void"), Type.Kind.VOID));
    }

    private static boolean isKotlinAccessor(MethodInfo method, Type expectedReturnType) {
        return method != null
                && !Modifier.isStatic(method.flags())
                && !method.isSynthetic()
                && method.returnType().kind() == expectedReturnType.kind()
                && (expectedReturnType.kind() == Type.Kind.VOID
                        || method.returnType().name().equals(expectedReturnType.name()));
    }

    private static String capitalize(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static boolean isEnumType(DotName typeName, IndexView index) {
        if (index != null) {
            ClassInfo typeInfo = index.getClassByName(typeName);
            if (typeInfo != null) {
                return typeInfo.isEnum();
            }
        }
        String name = typeName.toString();
        if (name.startsWith("java.") || name.startsWith("jakarta.")) {
            try {
                return Class.forName(name, false, QuarkusAppMetadataCollector.class.getClassLoader())
                        .isEnum();
            } catch (ClassNotFoundException | RuntimeException | LinkageError ignored) {
                return false;
            }
        }
        return false;
    }

    private static void declarations(List<ClassInfo> classes, State state) {
        Budget budget = new Budget(MAX_MEMBERS);
        try {
            for (ClassInfo info : classes) {
                if (info.declaredAnnotation(CONFIG_MAPPING) != null) {
                    state.configMappings++;
                }
                for (FieldInfo field : info.fields()) {
                    budget.take();
                    if (field.hasAnnotation(CONFIG_PROPERTY)) {
                        state.configProperties++;
                    }
                }
                for (MethodInfo method : info.methods()) {
                    budget.take();
                    for (AnnotationInstance annotation : method.annotations()) {
                        budget.take();
                        if (annotation.name().equals(CONFIG_PROPERTY)) {
                            state.configProperties++;
                        } else if (annotation.name().equals(SCHEDULED)) {
                            state.scheduled++;
                        } else if (annotation.name().equals(SCHEDULES) && annotation.value() != null) {
                            int count = annotation.value().asNestedArray().length;
                            budget.take(count);
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
        private final Set<String> reactiveVirtualMethods = new TreeSet<>();
        private final Set<String> allVirtualMethods = new TreeSet<>();
        private final Set<String> unproxyableBeans = new TreeSet<>();
        private final Set<String> defaultPackageBeans = new TreeSet<>();
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
            if (capabilities.isPresent(Capability.RESTEASY_CLIENT)) {
                problem(List.of("QA-WEB-003"), QuarkusAppMetadataStore.UNRESOLVED);
            }
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
                    List.copyOf(problems),
                    List.copyOf(unproxyableBeans),
                    List.copyOf(defaultPackageBeans),
                    List.copyOf(reactiveVirtualMethods),
                    List.copyOf(allVirtualMethods));
        }
    }
}
