package io.github.jdubois.bootui.engine.crac;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitCallTarget;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaStaticInitializer;
import com.tngtech.archunit.lang.ArchRule;
import io.github.jdubois.bootui.core.dto.CracFindingDto;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Base class for readiness checks backed by a single ArchUnit {@link ArchRule}.
 *
 * <p>Subclasses build the rule for the current context; any failure to build or evaluate it is
 * captured and reported as an {@code ERROR} outcome so one broken check never aborts the scan.</p>
 */
abstract class AbstractArchUnitCracCheck implements CracCheck {

    private final CracCheckDefinition definition;

    AbstractArchUnitCracCheck(CracCheckDefinition definition) {
        this.definition = definition;
    }

    @Override
    public final CracCheckDefinition definition() {
        return definition;
    }

    abstract ArchRule rule(CracContext context);

    @Override
    public CracFindingDto evaluate(CracContext context) {
        try {
            ArchRule rule = rule(context);
            if (rule == null) {
                return CracCheckSupport.skipped(definition, "Check is not applicable to the imported classes.");
            }
            return CracCheckSupport.evaluate(definition, rule, context);
            // Catch LinkageError as well as RuntimeException so one check that trips over an unresolvable class
            // reports an ERROR result instead of aborting the whole scan; VirtualMachineError still propagates.
        } catch (RuntimeException | LinkageError ex) {
            return CracCheckSupport.error(definition, ex);
        }
    }
}

/**
 * Shared checkpoint/restore lifecycle evidence used by the readiness checks.
 *
 * <p>Only acquisition from a restore/start callback is exempt. Acquiring a resource from
 * {@code beforeCheckpoint()} or {@code stop()} is still suspicious because those callbacks should
 * quiesce or release state. Field checks annotate compatible cleanup without inferring receiver
 * identity; neither interface implementation nor cleanup calls prove registration or ownership.</p>
 */
final class ManagedLifecycleCallSites {

    private static final Set<String> RESOURCE_TYPES =
            Set.of("org.crac.Resource", "javax.crac.Resource", "jdk.crac.Resource");

    private static final java.util.Map<String, String> RESOURCE_CONTEXT_TYPES = java.util.Map.of(
            "org.crac.Resource", "org.crac.Context",
            "javax.crac.Resource", "javax.crac.Context",
            "jdk.crac.Resource", "jdk.crac.Context");

    private static final Set<String> SPRING_LIFECYCLE_TYPES =
            Set.of("org.springframework.context.Lifecycle", "org.springframework.context.SmartLifecycle");

    private static final Set<String> CLEANUP_METHODS =
            Set.of("cancel", "close", "destroy", "disconnect", "shutdown", "shutdownGracefully", "shutdownNow", "stop");

    private ManagedLifecycleCallSites() {}

    static boolean isExemptCallSite(JavaCall<?> call) {
        JavaCodeUnit origin = call.getOrigin();
        if (isResourceCallback(origin, "afterRestore")) {
            return true;
        }
        return "start".equals(origin.getName())
                && origin.getRawParameterTypes().isEmpty()
                && isAssignableToAny(call.getOriginOwner(), SPRING_LIFECYCLE_TYPES);
    }

    static boolean isManagedClass(JavaClass javaClass) {
        return isAssignableToAny(javaClass, RESOURCE_TYPES) || isAssignableToAny(javaClass, SPRING_LIFECYCLE_TYPES);
    }

    static boolean hasCompatibleCleanupCall(JavaClass javaClass, JavaField field) {
        if (!isManagedClass(javaClass)) {
            return false;
        }
        for (JavaMethod method : javaClass.getMethods()) {
            boolean cleanupCallback = isResourceCallback(method, "beforeCheckpoint") || isSpringStopCallback(method);
            if (cleanupCallback && delegatesCleanup(method, field, javaClass, new HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recognizes a matching cleanup call made directly by {@code codeUnit}, or delegated to a
     * private helper method declared on the same {@code owner} class (a common refactor of
     * checkpoint callbacks). Traversal only follows calls resolving to a <b>private</b> method
     * declared on {@code owner} itself, not arbitrary collaborators or broader-visibility methods
     * that may be reused elsewhere for unrelated purposes, and {@code visited} guards against call
     * cycles so this stays a bounded walk rather than an unbounded whole-program call graph search.
     */
    private static boolean delegatesCleanup(
            JavaCodeUnit codeUnit, JavaField field, JavaClass owner, Set<JavaCodeUnit> visited) {
        if (!visited.add(codeUnit)) {
            return false;
        }
        for (JavaCall<?> call : codeUnit.getCallsFromSelf()) {
            CodeUnitCallTarget target = call.getTarget();
            if (CLEANUP_METHODS.contains(target.getName())
                    && field.getRawType().isAssignableTo(target.getOwner().getName())) {
                return true;
            }
            if (owner.equals(target.getOwner())
                    && target.resolveMember()
                            .filter(ManagedLifecycleCallSites::isPrivateHelper)
                            .isPresent()
                    && delegatesCleanup(target.resolveMember().get(), field, owner, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPrivateHelper(JavaCodeUnit codeUnit) {
        return codeUnit.getModifiers().contains(JavaModifier.PRIVATE);
    }

    private static boolean isResourceCallback(JavaCodeUnit codeUnit, String methodName) {
        if (!methodName.equals(codeUnit.getName())
                || codeUnit.getRawParameterTypes().size() != 1) {
            return false;
        }
        String contextType = codeUnit.getRawParameterTypes().get(0).getName();
        for (java.util.Map.Entry<String, String> callback : RESOURCE_CONTEXT_TYPES.entrySet()) {
            if (callback.getValue().equals(contextType) && codeUnit.getOwner().isAssignableTo(callback.getKey())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSpringStopCallback(JavaMethod method) {
        if (!"stop".equals(method.getName())) {
            return false;
        }
        if (method.getRawParameterTypes().isEmpty()) {
            return isAssignableToAny(method.getOwner(), SPRING_LIFECYCLE_TYPES);
        }
        return method.getRawParameterTypes().size() == 1
                && "java.lang.Runnable"
                        .equals(method.getRawParameterTypes().get(0).getName())
                && method.getOwner().isAssignableTo("org.springframework.context.SmartLifecycle");
    }

    private static boolean isAssignableToAny(JavaClass javaClass, Set<String> typeNames) {
        for (String typeName : typeNames) {
            if (javaClass.isAssignableTo(typeName)) {
                return true;
            }
        }
        return false;
    }
}

/**
 * Flags direct construction of network sockets ({@code new Socket}, {@code new ServerSocket},
 * {@code new DatagramSocket}, {@code new MulticastSocket}) and the NIO channel static {@code open(...)}
 * factory methods ({@code SocketChannel}, {@code ServerSocketChannel}, {@code DatagramChannel},
 * {@code AsynchronousSocketChannel}, {@code AsynchronousServerSocketChannel}) that idiomatic NIO code
 * actually uses instead of a constructor. The call site does not prove a socket remains open:
 * ownership and the deployed runtime's descriptor handling need separate verification.
 *
 * <p>A call that originates from a managed restore callback ({@code org.crac.Resource.afterRestore()}
 * or a Spring {@code Lifecycle.start()}) is exempt: re-opening the socket there is the recommended fix,
 * not a new violation. See {@link ManagedLifecycleCallSites}.</p>
 */
final class SocketConstructionCheck extends AbstractArchUnitCracCheck {

    private static final Set<String> SOCKET_TYPES = Set.of(
            "java.net.Socket",
            "java.net.ServerSocket",
            "java.net.DatagramSocket",
            "java.net.MulticastSocket",
            "java.nio.channels.ServerSocketChannel",
            "java.nio.channels.SocketChannel");

    private static final Set<String> NIO_CHANNEL_OPEN_TYPES = Set.of(
            "java.nio.channels.SocketChannel",
            "java.nio.channels.ServerSocketChannel",
            "java.nio.channels.DatagramChannel",
            "java.nio.channels.AsynchronousSocketChannel",
            "java.nio.channels.AsynchronousServerSocketChannel");

    SocketConstructionCheck() {
        super(new CracCheckDefinition(
                "CRAC-NET-001",
                "Direct network socket acquisition needs checkpoint lifecycle review",
                CracCategory.NETWORK,
                "HIGH",
                "Detects direct network socket or channel acquisition in application bytecode. The call site is evidence of ownership, not proof that the socket remains open at checkpoint time; short-lived sockets may already be closed. Acquisition from org.crac.Resource.afterRestore() or Spring Lifecycle.start() is excluded, while acquisition during beforeCheckpoint()/stop() remains visible.",
                "Confirm that each acquired socket is closed before checkpoint. Use try-with-resources for short-lived work, an org.crac.Resource that closes in beforeCheckpoint() and reopens in afterRestore(), or a Spring Lifecycle owner that stops and starts the transport.",
                "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html"));
    }

    @Override
    ArchRule rule(CracContext context) {
        return noClasses()
                .should()
                .callCodeUnitWhere(new DescribedPredicate<JavaCall<?>>("a network socket is constructed") {
                    @Override
                    public boolean test(JavaCall<?> call) {
                        if (ManagedLifecycleCallSites.isExemptCallSite(call)) {
                            return false;
                        }
                        CodeUnitCallTarget target = call.getTarget();
                        String owner = target.getOwner().getName();
                        String name = target.getName();
                        if ("<init>".equals(name) && SOCKET_TYPES.contains(owner)) {
                            return true;
                        }
                        return "open".equals(name) && NIO_CHANNEL_OPEN_TYPES.contains(owner);
                    }
                })
                .as("Classes should not open network sockets that survive a checkpoint");
    }
}

/**
 * Flags host-name resolution and network-interface enumeration in static initializers. OpenJDK CRaC wipes
 * {@code InetAddress}'s resolution cache before checkpoint so that lookups after restore reflect the restore
 * environment; a result retained in a static field is frozen into the image instead. Like
 * {@link CapturedTimeCheck}, the bytecode signal cannot prove that the value is retained.
 */
final class StaticNetworkIdentityCheck extends AbstractArchUnitCracCheck {

    private static final Set<String> INET_ADDRESS_LOOKUPS =
            Set.of("getLocalHost", "getByName", "getAllByName", "getHostName", "getCanonicalHostName");

    private static final Set<String> NETWORK_INTERFACE_LOOKUPS =
            Set.of("getNetworkInterfaces", "networkInterfaces", "getByName", "getByInetAddress", "getByIndex");

    StaticNetworkIdentityCheck() {
        super(
                new CracCheckDefinition(
                        "CRAC-NET-002",
                        "Static initializer may retain resolved host or network identity",
                        CracCategory.NETWORK,
                        "LOW",
                        "Detects InetAddress host lookups (getLocalHost, getByName, getAllByName, getHostName, getCanonicalHostName) and NetworkInterface enumeration in static initializers. OpenJDK CRaC clears its own address cache before checkpoint so lookups after restore resolve in the restore environment, but a value retained by the application is frozen into the image. The bytecode signal cannot prove retention, and getByName with a literal IP address performs no lookup. NetworkInterface enumeration is included by analogy; the cited CRaC note covers InetAddress resolution only.",
                        "If the host name, address or interface list is retained and must reflect the host where the image is restored, resolve it when needed instead of in a static initializer, or refresh it in org.crac.Resource.afterRestore(). Values that are only logged or discarded need no change.",
                        "https://github.com/openjdk/crac/blob/945496fe5fded24a64a6a3683979bbc76788f83c/src/java.base/share/classes/java/net/InetAddress.java"));
    }

    @Override
    ArchRule rule(CracContext context) {
        return noClasses()
                .should()
                .callCodeUnitWhere(
                        new DescribedPredicate<JavaCall<?>>("a static initializer resolves network identity") {
                            @Override
                            public boolean test(JavaCall<?> call) {
                                if (!(call.getOrigin() instanceof JavaStaticInitializer)) {
                                    return false;
                                }
                                return isNetworkIdentityLookup(
                                        call.getTarget().getOwner().getName(),
                                        call.getTarget().getName());
                            }
                        })
                .as("Static initializers should not retain host or network identity before a checkpoint");
    }

    static boolean isNetworkIdentityLookup(String owner, String name) {
        return "java.net.InetAddress".equals(owner) && INET_ADDRESS_LOOKUPS.contains(name)
                || ("java.net.Inet4Address".equals(owner) || "java.net.Inet6Address".equals(owner))
                        && ("getHostName".equals(name) || "getCanonicalHostName".equals(name))
                || "java.net.NetworkInterface".equals(owner) && NETWORK_INTERFACE_LOOKUPS.contains(name);
    }
}

/**
 * Flags direct construction or opening of file handles ({@code new FileInputStream}, {@code
 * FileReader}, {@code RandomAccessFile}, {@code ZipFile}/{@code JarFile}, and the {@code Files} /
 * {@code FileChannel} / {@code AsynchronousFileChannel} open factory methods). A caller-owned
 * handle requires lifecycle or deployment-specific descriptor-policy review, not an assumption
 * that it is still open when checkpointing occurs.
 *
 * <p>A call that originates from a managed restore callback ({@code org.crac.Resource.afterRestore()}
 * or a Spring {@code Lifecycle.start()}) is exempt: reopening the file there is the recommended fix,
 * not a new violation. See {@link ManagedLifecycleCallSites}.</p>
 */
final class FileHandleCheck extends AbstractArchUnitCracCheck {

    private static final Set<String> FILE_TYPES = Set.of(
            "java.io.FileInputStream",
            "java.io.FileOutputStream",
            "java.io.RandomAccessFile",
            "java.io.FileReader",
            "java.io.FileWriter",
            "java.util.zip.ZipFile",
            "java.util.jar.JarFile");

    private static final Set<String> FILES_FACTORIES = Set.of(
            "newInputStream",
            "newOutputStream",
            "newByteChannel",
            "newBufferedReader",
            "newBufferedWriter",
            "newDirectoryStream",
            "list",
            "walk",
            "find",
            "lines");

    private static final Set<String> CHANNEL_OPENERS =
            Set.of("java.nio.channels.FileChannel", "java.nio.channels.AsynchronousFileChannel");

    FileHandleCheck() {
        super(new CracCheckDefinition(
                "CRAC-FILE-001",
                "Direct file handle acquisition needs checkpoint lifecycle review",
                CracCategory.RESOURCES,
                "HIGH",
                "Detects direct file-handle acquisition in application bytecode. The call site is evidence of ownership, not proof that the handle remains open at checkpoint time; try-with-resources may already close it. Acquisition from org.crac.Resource.afterRestore() or Spring Lifecycle.start() is excluded, while acquisition during beforeCheckpoint()/stop() remains visible.",
                "Confirm that each acquired handle is closed before checkpoint. Use try-with-resources for short-lived work, an org.crac.Resource that closes in beforeCheckpoint() and reopens in afterRestore(), or a Spring Lifecycle owner that stops and starts the resource.",
                "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html"));
    }

    @Override
    ArchRule rule(CracContext context) {
        return noClasses()
                .should()
                .callCodeUnitWhere(new DescribedPredicate<JavaCall<?>>("a file handle is opened") {
                    @Override
                    public boolean test(JavaCall<?> call) {
                        if (ManagedLifecycleCallSites.isExemptCallSite(call)) {
                            return false;
                        }
                        CodeUnitCallTarget target = call.getTarget();
                        String owner = target.getOwner().getName();
                        String name = target.getName();
                        if ("<init>".equals(name) && FILE_TYPES.contains(owner)) {
                            return true;
                        }
                        if ("java.nio.file.Files".equals(owner) && FILES_FACTORIES.contains(name)) {
                            return true;
                        }
                        return "open".equals(name) && CHANNEL_OPENERS.contains(owner);
                    }
                })
                .as("Classes should not open files that survive a checkpoint");
    }
}

/**
 * Flags threads, timers, and executor pools created directly rather than through a Spring-managed
 * lifecycle. CRaC is built on CRIU, which freezes <em>every</em> OS thread in the process to take a
 * checkpoint — no thread "keeps running through" it. The real risk is in what happens just before that
 * freeze: Spring stops {@code SmartLifecycle} beans gracefully before the checkpoint is even requested,
 * so managed background work reaches a quiescent, consistent state first. A raw thread or executor has
 * no such hook, so it is frozen abruptly mid-execution — in whatever state it happens to be in — which
 * risks deadlocks, stale locks, or inconsistent state on restore.
 *
 * <p>A call that originates from a managed restore callback ({@code org.crac.Resource.afterRestore()}
 * or a Spring {@code Lifecycle.start()}) is exempt: restarting the pool there is the recommended fix,
 * not a new violation. See {@link ManagedLifecycleCallSites}.</p>
 */
final class UnmanagedThreadCheck extends AbstractArchUnitCracCheck {

    private static final Set<String> THREAD_TYPES = Set.of(
            "java.util.Timer",
            "java.util.concurrent.ThreadPoolExecutor",
            "java.util.concurrent.ScheduledThreadPoolExecutor",
            "java.util.concurrent.ForkJoinPool");

    private static final Set<String> EXECUTOR_FACTORIES = Set.of(
            "newFixedThreadPool",
            "newCachedThreadPool",
            "newSingleThreadExecutor",
            "newScheduledThreadPool",
            "newSingleThreadScheduledExecutor",
            "newWorkStealingPool",
            "newThreadPerTaskExecutor",
            "newVirtualThreadPerTaskExecutor");

    private static final Set<String> THREAD_BUILDER_TYPES = Set.of(
            "java.lang.Thread$Builder", "java.lang.Thread$Builder$OfVirtual", "java.lang.Thread$Builder$OfPlatform");

    UnmanagedThreadCheck() {
        super(new CracCheckDefinition(
                "CRAC-THREAD-001",
                "Threads or executor pools created outside the Spring lifecycle",
                CracCategory.THREADS,
                "MEDIUM",
                "Detects direct thread starts, timers, and executor-pool construction outside a managed checkpoint/restore lifecycle. Constructing an unstarted Thread or obtaining a ThreadFactory is not reported. Executor construction is ownership evidence rather than proof that workers are active. CRIU freezes every OS thread, but unmanaged work is not first quiesced by Spring's lifecycle.",
                "Drive background work through a lifecycle-managed TaskExecutor/TaskScheduler, or register an org.crac.Resource that quiesces the work in beforeCheckpoint() and recreates it in afterRestore(). Direct restart calls from afterRestore()/start() are not flagged.",
                "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html"));
    }

    @Override
    ArchRule rule(CracContext context) {
        return noClasses()
                .should()
                .callCodeUnitWhere(new DescribedPredicate<JavaCall<?>>("an unmanaged thread or pool is created") {
                    @Override
                    public boolean test(JavaCall<?> call) {
                        if (ManagedLifecycleCallSites.isExemptCallSite(call)) {
                            return false;
                        }
                        CodeUnitCallTarget target = call.getTarget();
                        String owner = target.getOwner().getName();
                        String name = target.getName();
                        return isThreadCreation(owner, name);
                    }
                })
                .as("Classes should not create threads or pools outside the Spring lifecycle");
    }

    static boolean isThreadCreation(String owner, String name) {
        return "<init>".equals(name) && THREAD_TYPES.contains(owner)
                || "java.lang.Thread".equals(owner) && ("start".equals(name) || "startVirtualThread".equals(name))
                || THREAD_BUILDER_TYPES.contains(owner) && "start".equals(name)
                || "java.util.concurrent.Executors".equals(owner) && EXECUTOR_FACTORIES.contains(name);
    }
}

/**
 * Reports Spring thread-per-task executors and schedulers with incomplete context lifecycle support.
 */
final class SpringTaskLifecycleCheck implements CracCheck {

    @Override
    public Evidence evidence() {
        return Evidence.RUNTIME;
    }

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-THREAD-002",
            "Spring thread-per-task executors need explicit restore handling",
            CracCategory.THREADS,
            "MEDIUM",
            "Detects SimpleAsyncTaskExecutor and SimpleAsyncTaskScheduler beans. SimpleAsyncTaskExecutor does not participate in context-level lifecycle management; SimpleAsyncTaskScheduler stops trigger firing but does not stop handed-off tasks. Bean presence is bounded evidence, not proof that a task is active at checkpoint.",
            "Prefer lifecycle-managed ThreadPoolTaskExecutor/ThreadPoolTaskScheduler infrastructure, or configure and verify explicit quiescence before checkpoint and restart after restore. Test handed-off work with the exact checkpoint mode.",
            "https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/task/SimpleAsyncTaskExecutor.html");

    @Override
    public CracCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CracFindingDto evaluate(CracContext context) {
        try {
            List<String> taskBeans = context.runtime().unmanagedTaskBeans();
            if (taskBeans.isEmpty()) {
                return CracCheckSupport.ok(DEFINITION);
            }
            List<String> samples = new ArrayList<>();
            for (String taskBean : taskBeans) {
                if (samples.size() >= CracCheckSupport.maxSampleOccurrences()) {
                    break;
                }
                samples.add(CracCheckSupport.detail(taskBean));
            }
            return CracCheckSupport.review(DEFINITION, taskBeans.size(), samples);
        } catch (RuntimeException | LinkageError ex) {
            return CracCheckSupport.error(DEFINITION, ex);
        }
    }
}

/**
 * Flags capture of wall-clock time in static initializers. With CRaC the static initializer runs
 * once when the original JVM starts; the captured value is frozen into the checkpoint image and is
 * stale (sometimes by days) in every restored process.
 */
final class CapturedTimeCheck extends AbstractArchUnitCracCheck {

    private static final Set<String> SYSTEM_TIME = Set.of("currentTimeMillis");

    CapturedTimeCheck() {
        super(new CracCheckDefinition(
                "CRAC-TIME-001",
                "Static initializer may retain checkpoint-era wall-clock time",
                CracCategory.TIME,
                "LOW",
                "Detects wall-clock reads in static initializers (System.currentTimeMillis, java.time now(), or new Date()). The bytecode signal cannot prove that the value is retained, but a retained startup timestamp is frozen into the image and may be stale after restore. System.nanoTime is deliberately excluded because it is not wall-clock time.",
                "If the value is retained, read it when needed instead of caching it before checkpoint, or refresh the retained value in org.crac.Resource.afterRestore().",
                "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html"));
    }

    @Override
    ArchRule rule(CracContext context) {
        return noClasses()
                .should()
                .callCodeUnitWhere(new DescribedPredicate<JavaCall<?>>("a static initializer captures the time") {
                    @Override
                    public boolean test(JavaCall<?> call) {
                        if (!(call.getOrigin() instanceof JavaStaticInitializer)) {
                            return false;
                        }
                        CodeUnitCallTarget target = call.getTarget();
                        String owner = target.getOwner().getName();
                        String name = target.getName();
                        if ("java.lang.System".equals(owner)) {
                            return SYSTEM_TIME.contains(name);
                        }
                        if (owner.startsWith("java.time.") && "now".equals(name)) {
                            return true;
                        }
                        return "java.util.Date".equals(owner)
                                && "<init>".equals(name)
                                && target.getRawParameterTypes().isEmpty();
                    }
                })
                .as("Static initializers should not capture wall-clock time before a checkpoint");
    }
}

/**
 * Reviews resource-typed application fields. Compatible callback cleanup is useful context but
 * does not establish which field is closed or that the owner is registered.
 */
final class OpenResourceFieldCheck implements CracCheck {

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-RES-001",
            "Resource fields need observable checkpoint cleanup",
            CracCategory.RESOURCES,
            "HIGH",
            "Detects fields whose type can hold an OS resource. A field is possible ownership, not proof of a reachable, non-null or open resource. Compatible cleanup in an exact lifecycle callback is noted, but cannot establish which instance is closed or whether the owner is registered; it never makes another same-type field clean.",
            "Review actual field ownership and registration before changing resource handling. Where needed, release the resource before checkpoint and recreate it after restore. Preserve existing framework-managed cleanup; a compatible close call is evidence to investigate, not a guarantee of field-specific cleanup.",
            "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html");

    private static final Set<String> RESOURCE_TYPES = Set.of(
            "java.net.Socket",
            "java.net.ServerSocket",
            "java.net.DatagramSocket",
            "java.net.MulticastSocket",
            "java.io.FileInputStream",
            "java.io.FileOutputStream",
            "java.io.FileReader",
            "java.io.FileWriter",
            "java.io.RandomAccessFile",
            "java.util.zip.ZipFile",
            "java.nio.channels.FileChannel",
            "java.nio.channels.AsynchronousFileChannel",
            "java.nio.channels.SocketChannel",
            "java.nio.channels.ServerSocketChannel",
            "java.nio.channels.DatagramChannel",
            "java.nio.channels.AsynchronousSocketChannel",
            "java.nio.channels.AsynchronousServerSocketChannel",
            "java.nio.channels.FileLock",
            "java.nio.channels.Selector",
            "java.nio.file.WatchService",
            "java.nio.file.DirectoryStream",
            "java.lang.Process",
            "java.sql.Connection");

    @Override
    public CracCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CracFindingDto evaluate(CracContext context) {
        try {
            List<String> samples = new ArrayList<>();
            int count = 0;
            boolean withoutCleanup = false;
            for (JavaClass javaClass : context.classes()) {
                for (JavaField field : javaClass.getFields()) {
                    if (isResourceType(field.getRawType())) {
                        count++;
                        boolean cleanup = ManagedLifecycleCallSites.hasCompatibleCleanupCall(javaClass, field);
                        withoutCleanup |= !cleanup;
                        if (samples.size() < CracCheckSupport.maxSampleOccurrences()) {
                            samples.add(CracCheckSupport.detail(javaClass.getName() + "." + field.getName()
                                    + (cleanup
                                            ? " - compatible cleanup observed; field target and registration unverified"
                                            : " - no compatible lifecycle cleanup observed")));
                        }
                    }
                }
            }
            if (count == 0) {
                return CracCheckSupport.ok(DEFINITION);
            }
            return CracCheckSupport.review(
                    withoutCleanup ? DEFINITION : DEFINITION.withSeverity("MEDIUM"), count, samples);
        } catch (RuntimeException | LinkageError ex) {
            return CracCheckSupport.error(DEFINITION, ex);
        }
    }

    private static boolean isResourceType(JavaClass type) {
        for (String resourceType : RESOURCE_TYPES) {
            if (type.isAssignableTo(resourceType)) {
                return true;
            }
        }
        return false;
    }
}

/**
 * Flags non-cryptographic random generator fields and explicit {@link java.security.SecureRandom} seeding.
 * Explicit seeding keeps the high severity because it can disable a provider's automatic reseeding after
 * restore; generator fields alone are reported at medium severity.
 */
final class RandomFieldCheck implements CracCheck {

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-RANDOM-001",
            "Random state or explicit SecureRandom seeding needs restore handling",
            CracCategory.RANDOMNESS,
            "HIGH",
            "Detects java.util.Random (including ThreadLocalRandom) and SplittableRandom fields (SecureRandom-typed fields are covered by CRAC-RANDOM-002), plus SecureRandom(byte[]) construction and SecureRandom.setSeed(...) calls outside restore/start callbacks. Generator state retained in an image is duplicated in every restored process. HIGH when explicit SecureRandom seeding is observed: in OpenJDK CRaC's SHA1PRNG an explicit seed disables automatic reseeding after restore, while other providers such as NativePRNG still mix operating-system entropy. MEDIUM when only generator fields are found. Fields typed only as the RandomGenerator interface are not reported because they may hold a SecureRandom. This signal does not establish security-sensitive use, actual retention, or the deployed provider's restore behavior.",
            "For security-sensitive uniqueness, use an unseeded SecureRandom and verify the exact deployed JDK/provider. Review explicit seeds and cloned generator state; intentional deterministic simulation may need no change. Refresh state after restore only when independent sequences per restored process are required. Exact restore/start seed calls are excluded, but their entropy quality is not assessed.",
            "https://github.com/openjdk/crac/blob/945496fe5fded24a64a6a3683979bbc76788f83c/src/java.base/share/classes/sun/security/provider/SecureRandom.java");

    private static final Set<String> GENERATOR_TYPES = Set.of("java.util.Random", "java.util.SplittableRandom");

    @Override
    public CracCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CracFindingDto evaluate(CracContext context) {
        try {
            List<String> samples = new ArrayList<>();
            int count = 0;
            boolean explicitSeed = false;
            for (JavaClass javaClass : context.classes()) {
                for (JavaField field : javaClass.getFields()) {
                    JavaClass type = field.getRawType();
                    if (isGeneratorType(type)) {
                        count++;
                        if (samples.size() < CracCheckSupport.maxSampleOccurrences()) {
                            samples.add(CracCheckSupport.detail(
                                    javaClass.getName() + "." + field.getName() + " : " + type.getName()));
                        }
                    }
                }
                for (JavaCodeUnit codeUnit : javaClass.getCodeUnits()) {
                    for (JavaCall<?> call : codeUnit.getCallsFromSelf()) {
                        CodeUnitCallTarget target = call.getTarget();
                        if (!ManagedLifecycleCallSites.isExemptCallSite(call) && isExplicitSecureRandomSeed(target)) {
                            count++;
                            explicitSeed = true;
                            if (samples.size() < CracCheckSupport.maxSampleOccurrences()) {
                                samples.add(CracCheckSupport.detail(javaClass.getName() + "." + codeUnit.getName()
                                        + "() explicitly seeds SecureRandom"));
                            }
                        }
                    }
                }
            }
            if (count == 0) {
                return CracCheckSupport.ok(DEFINITION);
            }
            return CracCheckSupport.review(
                    explicitSeed ? DEFINITION : DEFINITION.withSeverity("MEDIUM"), count, samples);
        } catch (RuntimeException | LinkageError ex) {
            return CracCheckSupport.error(DEFINITION, ex);
        }
    }

    private static boolean isGeneratorType(JavaClass type) {
        if (type.isAssignableTo("java.security.SecureRandom")) {
            return false;
        }
        for (String generatorType : GENERATOR_TYPES) {
            if (generatorType.equals(type.getName()) || type.isAssignableTo(generatorType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExplicitSecureRandomSeed(CodeUnitCallTarget target) {
        if (!"java.security.SecureRandom".equals(target.getOwner().getName())) {
            return false;
        }
        return ("<init>".equals(target.getName())
                        && target.getRawParameterTypes().size() == 1
                        && Set.of("byte[]", "[B")
                                .contains(target.getRawParameterTypes().get(0).getName()))
                || "setSeed".equals(target.getName());
    }
}

/**
 * Reports cached {@link java.security.SecureRandom} instances as a provider-specific verification.
 */
final class SecureRandomFieldCheck implements CracCheck {

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-RANDOM-002",
            "SecureRandom restore behavior depends on construction and provider",
            CracCategory.RANDOMNESS,
            "INFO",
            "Detects SecureRandom fields but cannot determine their constructor, algorithm, or security provider. Inspected OpenJDK CRaC JDK 28 development sources contain provider-specific restore handling; this is not a guarantee for a deployed CRaC JDK 17, 21 or 25. Custom, PKCS#11, FIPS, explicit-seed and other provider behavior is not inferred.",
            "Keep security generators unseeded unless the application deliberately owns reseeding, and run a checkpoint/restore test against the exact JDK, algorithm, and provider used in deployment. Explicit seed calls remain covered by CRAC-RANDOM-001.",
            "https://github.com/openjdk/crac/blob/945496fe5fded24a64a6a3683979bbc76788f83c/src/java.base/share/classes/sun/security/provider/SecureRandom.java");

    @Override
    public CracCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CracFindingDto evaluate(CracContext context) {
        try {
            List<String> samples = new ArrayList<>();
            int count = 0;
            for (JavaClass javaClass : context.classes()) {
                for (JavaField field : javaClass.getFields()) {
                    if (field.getRawType().isAssignableTo("java.security.SecureRandom")) {
                        count++;
                        if (samples.size() < CracCheckSupport.maxSampleOccurrences()) {
                            samples.add(CracCheckSupport.detail(javaClass.getName() + "." + field.getName() + " : "
                                    + field.getRawType().getName()));
                        }
                    }
                }
            }
            if (count == 0) {
                return CracCheckSupport.ok(DEFINITION);
            }
            return CracCheckSupport.review(DEFINITION, count, samples);
        } catch (RuntimeException | LinkageError ex) {
            return CracCheckSupport.error(DEFINITION, ex);
        }
    }
}

/**
 * Flags fields that may retain named secrets or cryptographic key material in a checkpoint image.
 */
final class CapturedSecretFieldCheck implements CracCheck {

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-SECRET-001",
            "Potential secret or key material is retained in a field",
            CracCategory.SECRETS,
            "HIGH",
            "Detects String/char[]/byte[] fields whose normalized name ends in secret, password, token, API key, credential, or private key, plus fields typed as SecretKey, PrivateKey, KeyStore, or KeyPair. Credential-named fields declared on JPA @Entity, @Embeddable or @MappedSuperclass classes are not reported because a column declaration is row data rather than a long-lived owner; entity instances loaded before checkpoint can still be in the image and are not visible to this check. Key-typed fields are reported wherever they are declared. The signal does not read values and cannot prove a field is populated, but any sensitive value seen before checkpoint must be assumed present in the image.",
            "Avoid loading sensitive values before a distributable checkpoint when possible, minimize their lifetime, and protect checkpoint files as secrets. Rotating a field after restore does not remove the original value from an already-created image.",
            "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html");

    private static final Set<String> SECRET_TYPES = Set.of("java.lang.String", "char[]", "byte[]", "[C", "[B");

    private static final Set<String> KEY_TYPES = Set.of(
            "javax.crypto.SecretKey", "java.security.PrivateKey", "java.security.KeyStore", "java.security.KeyPair");

    @Override
    public CracCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CracFindingDto evaluate(CracContext context) {
        try {
            List<String> samples = new ArrayList<>();
            int count = 0;
            for (JavaClass javaClass : context.classes()) {
                for (JavaField field : javaClass.getFields()) {
                    if (isCapturedSecret(field)) {
                        count++;
                        if (samples.size() < CracCheckSupport.maxSampleOccurrences()) {
                            samples.add(CracCheckSupport.detail(javaClass.getName() + "." + field.getName() + " : "
                                    + field.getRawType().getName()));
                        }
                    }
                }
            }
            if (count == 0) {
                return CracCheckSupport.ok(DEFINITION);
            }
            return CracCheckSupport.review(DEFINITION, count, samples);
        } catch (RuntimeException | LinkageError ex) {
            return CracCheckSupport.error(DEFINITION, ex);
        }
    }

    private static final Set<String> PERSISTENCE_CLASS_ANNOTATIONS = Set.of(
            "jakarta.persistence.Entity", "jakarta.persistence.Embeddable", "jakarta.persistence.MappedSuperclass");

    private static boolean isCapturedSecret(JavaField field) {
        JavaClass type = field.getRawType();
        boolean secretByName =
                hasSecretName(field.getName()) && SECRET_TYPES.contains(type.getName()) && !isPersistenceRowData(field);
        return secretByName || isKeyType(type);
    }

    private static boolean isPersistenceRowData(JavaField field) {
        if (field.getModifiers().contains(JavaModifier.STATIC)) {
            return false;
        }
        for (String annotation : PERSISTENCE_CLASS_ANNOTATIONS) {
            if (field.getOwner().isAnnotatedWith(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSecretName(String fieldName) {
        String normalized = fieldName
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replace('-', '_')
                .toLowerCase(java.util.Locale.ROOT);
        return normalized.matches(".*(?:secret|password|passwd|token|api_key|credential|private_key)$");
    }

    private static boolean isKeyType(JavaClass type) {
        for (String keyType : KEY_TYPES) {
            if (type.isAssignableTo(keyType)) {
                return true;
            }
        }
        return false;
    }
}

/**
 * Reports cached TLS context and manager fields separately from high-confidence key material.
 */
final class TlsMaterialFieldCheck implements CracCheck {

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-SECRET-002",
            "Cached TLS state may need restore-time rebuilding",
            CracCategory.SECRETS,
            "MEDIUM",
            "Detects fields typed as SSLContext, KeyManager, TrustManager, or their arrays. A field does not prove that key material or sessions are initialized, so this is separate from CRAC-SECRET-001, but initialized TLS state may contain checkpoint-era credentials, entropy, sessions, or transport state.",
            "Verify the exact TLS provider and initialization path. Rebuild initialized key/trust managers and SSLContext after restore when their state must change, and protect checkpoint files as sensitive artifacts.",
            "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html");

    private static final Set<String> TLS_TYPES = Set.of(
            "javax.net.ssl.SSLContext",
            "javax.net.ssl.KeyManager",
            "javax.net.ssl.TrustManager",
            "[Ljavax.net.ssl.KeyManager;",
            "[Ljavax.net.ssl.TrustManager;");

    @Override
    public CracCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CracFindingDto evaluate(CracContext context) {
        try {
            List<String> samples = new ArrayList<>();
            int count = 0;
            for (JavaClass javaClass : context.classes()) {
                for (JavaField field : javaClass.getFields()) {
                    if (isTlsType(field.getRawType())) {
                        count++;
                        if (samples.size() < CracCheckSupport.maxSampleOccurrences()) {
                            samples.add(CracCheckSupport.detail(javaClass.getName() + "." + field.getName() + " : "
                                    + field.getRawType().getName()));
                        }
                    }
                }
            }
            if (count == 0) {
                return CracCheckSupport.ok(DEFINITION);
            }
            return CracCheckSupport.review(DEFINITION, count, samples);
        } catch (RuntimeException | LinkageError ex) {
            return CracCheckSupport.error(DEFINITION, ex);
        }
    }

    private static boolean isTlsType(JavaClass type) {
        for (String tlsType : TLS_TYPES) {
            if (type.isAssignableTo(tlsType)) {
                return true;
            }
        }
        return false;
    }
}

/**
 * Flags non-Hikari connection pools and remote clients that need library-specific checkpoint lifecycle
 * verification.
 *
 * <p>Unlike the other checks this one reads the live {@link CracRuntimeInventory} rather than the
 * imported application bytecode, because pools are contributed by Spring Boot auto-configuration and
 * never appear in the application's own base package.</p>
 */
final class ConnectionPoolCheck implements CracCheck {

    @Override
    public Evidence evidence() {
        return Evidence.RUNTIME;
    }

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-POOL-001",
            "Non-Hikari pools need verified checkpoint lifecycle support",
            CracCategory.POOLS,
            "HIGH",
            "Reviews non-Hikari pool and remote-client metadata without proving an open connection. Known existing Spring-managed factories with documented lifecycle handling are distinguished from generic interfaces and unknown ownership. Their on-demand stop/restart support is not a guarantee for early initialization before the original onRefresh checkpoint.",
            "Verify ownership, initialization timing and the exact library version. Preserve documented Spring-managed lifecycle handling instead of wrapping or closing it twice. Add explicit quiescence/recreation only for resources not already managed. Service availability requirements depend on startup and restore behavior. Hikari is assessed separately by CRAC-POOL-004.",
            "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html");

    @Override
    public CracCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CracFindingDto evaluate(CracContext context) {
        try {
            List<String> poolBeans = new ArrayList<>(context.runtime().connectionPoolBeans());
            boolean knownManagedOnly = poolBeans.isEmpty();
            if (!context.runtime().applicationRunning() || !context.runtime().cracApiPresent()) {
                context.runtime()
                        .managedConnectionPoolBeans()
                        .forEach(bean -> poolBeans.add(
                                bean + " - known managed factory; startup phase or CRaC integration unverified"));
            }
            if (poolBeans.isEmpty()) {
                return CracCheckSupport.ok(DEFINITION);
            }
            List<String> samples = new ArrayList<>();
            for (String poolBean : poolBeans) {
                if (samples.size() >= CracCheckSupport.maxSampleOccurrences()) {
                    break;
                }
                samples.add(CracCheckSupport.detail(poolBean));
            }
            return CracCheckSupport.review(
                    knownManagedOnly ? DEFINITION.withSeverity("MEDIUM") : DEFINITION, poolBeans.size(), samples);
        } catch (RuntimeException | LinkageError ex) {
            return CracCheckSupport.error(DEFINITION, ex);
        }
    }
}

/**
 * Reports bounded Hikari lifecycle observations collected from the live Spring context.
 */
final class HikariCheckpointLifecycleCheck implements CracCheck {

    @Override
    public Evidence evidence() {
        return Evidence.RUNTIME;
    }

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-POOL-004",
            "Hikari pools need Spring Boot lifecycle coverage and suspension",
            CracCategory.POOLS,
            "HIGH",
            "Reviews Hikari checkpoint lifecycle pairing and suspension independently. Equal pool/lifecycle bean counts do not prove that each lifecycle targets a distinct pool, or successfully unwraps it. Pairing uncertainty is a manual review, not a proven open connection. Spring's stop/restart cycle applies to an already-running lifecycle, not necessarily early startup.",
            "Keep the CRaC API and applicable Spring Boot lifecycle configuration. Verify the actual pool-to-lifecycle pairing and initialization timing. Enable suspension before pool initialization using spring.datasource.hikari.allow-pool-suspension=true for Boot's stock pool, or the custom pool's own configuration prefix. Never change a running pool merely to satisfy this check.",
            "https://docs.spring.io/spring-boot/api/java/org/springframework/boot/jdbc/HikariCheckpointRestoreLifecycle.html");

    @Override
    public CracCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CracFindingDto evaluate(CracContext context) {
        try {
            List<String> issues = context.runtime().hikariPoolIssues();
            if (issues.isEmpty()) {
                return CracCheckSupport.ok(DEFINITION);
            }
            List<String> samples = new ArrayList<>();
            for (String issue : issues) {
                if (samples.size() >= CracCheckSupport.maxSampleOccurrences()) {
                    break;
                }
                samples.add(CracCheckSupport.detail(issue));
            }
            return CracCheckSupport.review(DEFINITION, issues.size(), samples);
        } catch (RuntimeException | LinkageError ex) {
            return CracCheckSupport.error(DEFINITION, ex);
        }
    }
}

/**
 * Reports refresh-time database access that an automatic {@code spring.context.checkpoint=onRefresh}
 * checkpoint would capture with open pooled JDBC connections. Spring Framework triggers that checkpoint
 * before lifecycle beans start, and its checkpoint callback only stops beans that are already running, so
 * Spring Boot's Hikari checkpoint lifecycle cannot suspend and evict the pool at that point.
 *
 * <p>This is planning guidance for the startup checkpoint mode (used by the generated scaffold), evaluated
 * only when checkpoint intent is observed: the {@code org.crac} API is present or the onRefresh setting is
 * set. On-demand checkpoints of a running application are assessed by {@link HikariCheckpointLifecycleCheck}.</p>
 */
final class StartupDatabaseAccessCheck implements CracCheck {

    @Override
    public Evidence evidence() {
        return Evidence.RUNTIME;
    }

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-POOL-005",
            "Startup database access leaves pooled connections open at an onRefresh checkpoint",
            CracCategory.POOLS,
            "MEDIUM",
            "Detects refresh-time database access - a Flyway migration initializer, Liquibase, Boot schema initializers with bundled scripts, spring.sql.init.mode=always, or Hibernate boot JDBC metadata access or schema management - alongside a Hikari pool whose JDBC URL is not in-memory or is unknown. The automatic spring.context.checkpoint=onRefresh checkpoint runs before lifecycle beans start, so Spring Boot's HikariCheckpointRestoreLifecycle never suspends or evicts the pool and the opened connections remain at checkpoint. This is planning guidance, evaluated only when the org.crac API is present or onRefresh is configured; it does not describe open connections in the already-running process. It cannot tell which datasource each source uses, and custom migration strategies, Liquibase shouldRun=false or Hibernate settings outside the JPA property map are not observed.",
            "For the checkpoint (training) run, avoid early database interaction: run migrations outside the application lifecycle (spring.flyway.enabled=false or spring.liquibase.enabled=false in the checkpoint profile, with migrations orchestrated separately), set spring.sql.init.mode=never, and for Hibernate set spring.jpa.database-platform plus spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false and ddl-auto=none. Enabling Hikari pool suspension (CRAC-POOL-004) does not help here. Alternatively, checkpoint a running application on demand. -Dspring.context.exit=onRefresh only shows whether startup reaches that phase; it does not prove checkpoint readiness.",
            "https://github.com/spring-projects/spring-lifecycle-smoke-tests/blob/main/data/data-jpa/README.adoc");

    @Override
    public CracCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CracFindingDto evaluate(CracContext context) {
        try {
            if (!context.runtime().cracApiPresent() && !context.runtime().checkpointOnRefresh()) {
                return CracCheckSupport.skipped(
                        DEFINITION,
                        "No checkpoint intent observed: the org.crac API is absent and spring.context.checkpoint=onRefresh is not set (see CRAC-LIFECYCLE-002).");
            }
            List<String> access = context.runtime().startupDatabaseAccess();
            if (access.isEmpty()) {
                return CracCheckSupport.ok(DEFINITION);
            }
            List<String> samples = new ArrayList<>();
            for (String observation : access) {
                if (samples.size() >= CracCheckSupport.maxSampleOccurrences()) {
                    break;
                }
                samples.add(CracCheckSupport.detail(observation));
            }
            return CracCheckSupport.review(DEFINITION, access.size(), samples);
        } catch (RuntimeException | LinkageError ex) {
            return CracCheckSupport.error(DEFINITION, ex);
        }
    }
}

/**
 * Flags known Spring {@code CacheManager} implementations backed by local, in-heap storage. Cache
 * entries populated before the checkpoint survive into every restored process and may be stale (for
 * example expired tokens or other time-sensitive data), because the checkpoint freezes the cache
 * contents along with the rest of the heap.
 *
 * <p>Well-known remote/external-store-backed managers (currently Spring Data Redis's {@code
 * RedisCacheManager}) are excluded upstream by {@code CracRuntimeInventoryCollector}, because their
 * entries live outside the JVM heap in an external store and are not frozen by the checkpoint the way a
 * local manager's (for example {@code ConcurrentMapCacheManager} or Caffeine) are.</p>
 *
 * <p>Like {@link ConnectionPoolCheck} this reads the live {@link CracRuntimeInventory} rather than the
 * imported bytecode, because cache managers are contributed by Spring's cache auto-configuration and
 * never appear in the application's own base package.</p>
 */
final class CacheManagerCheck implements CracCheck {

    @Override
    public Evidence evidence() {
        return Evidence.RUNTIME;
    }

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-CACHE-001",
            "In-memory caches may hold stale entries after restore",
            CracCategory.CACHES,
            "LOW",
            "Detects known local, in-heap Spring CacheManager implementations (currently ConcurrentMapCacheManager and CaffeineCacheManager). Entries populated before checkpoint, for example by warm-up traffic before an on-demand checkpoint, are frozen into the image. ConcurrentMapCacheManager never expires entries. Caffeine's default ticker uses System.nanoTime, which the inspected OpenJDK CRaC sources advance across restore, but that is not certified for every deployed runtime; long TTLs, refreshAfterWrite, custom tickers and caches without expiry can still serve checkpoint-era data. Manager presence proves neither cache contents nor expiry configuration; unknown, no-op and remote-backed managers are not classified as local.",
            "Review the freshness requirement of each local cache. Where existing expiry is insufficient, clear or refresh the affected caches in an org.crac.Resource.afterRestore() callback, keeping in mind that clearing everything at once can cause a reload burst after restore.",
            "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html");

    @Override
    public CracCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CracFindingDto evaluate(CracContext context) {
        try {
            List<String> cacheBeans = context.runtime().cacheManagerBeans();
            if (cacheBeans.isEmpty()) {
                return CracCheckSupport.ok(DEFINITION);
            }
            List<String> samples = new ArrayList<>();
            for (String cacheBean : cacheBeans) {
                if (samples.size() >= CracCheckSupport.maxSampleOccurrences()) {
                    break;
                }
                samples.add(CracCheckSupport.detail(cacheBean));
            }
            return CracCheckSupport.review(DEFINITION, cacheBeans.size(), samples);
        } catch (RuntimeException | LinkageError ex) {
            return CracCheckSupport.error(DEFINITION, ex);
        }
    }
}

/**
 * Flags static initializers that read environment- or system-derived configuration ({@code
 * System.getenv}, {@code System.getProperty}, {@code System.getProperties}). With {@code
 * spring.context.checkpoint=onRefresh} the value is read once when the original JVM starts and frozen
 * into the checkpoint image, so a restore-only start that changes the variable has no effect until a
 * new checkpoint is taken.
 */
final class CapturedConfigurationCheck extends AbstractArchUnitCracCheck {

    private static final Set<String> CONFIG_ACCESSORS = Set.of("getenv", "getProperty", "getProperties");

    CapturedConfigurationCheck() {
        super(new CracCheckDefinition(
                "CRAC-CONFIG-001",
                "Static initializer may retain startup configuration",
                CracCategory.CONFIG,
                "LOW",
                "Detects System.getenv/getProperty/getProperties calls in static initializers. The bytecode signal cannot prove retention. Some CRaC runtimes update environment/system properties on restore, but already-cached values and Spring bean bindings do not automatically rebind.",
                "If the result is retained and must vary per restore, explicitly reload or rebind it through the owning lifecycle and verify the deployed runtime. Regenerate the checkpoint when startup assumptions change; do not assume restore-time environment changes update existing beans.",
                "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html"));
    }

    @Override
    ArchRule rule(CracContext context) {
        return noClasses()
                .should()
                .callCodeUnitWhere(new DescribedPredicate<JavaCall<?>>("a static initializer captures configuration") {
                    @Override
                    public boolean test(JavaCall<?> call) {
                        if (!(call.getOrigin() instanceof JavaStaticInitializer)) {
                            return false;
                        }
                        CodeUnitCallTarget target = call.getTarget();
                        return "java.lang.System".equals(target.getOwner().getName())
                                && CONFIG_ACCESSORS.contains(target.getName());
                    }
                })
                .as("Static initializers should not capture environment or system configuration before a checkpoint");
    }
}

/**
 * Flags fixed-rate scheduling: {@code @Scheduled} methods that explicitly declare {@code fixedRate} or
 * {@code fixedRateString}, {@code ScheduledTaskRegistrar.addFixedRateTask(...)} registrations, and
 * programmatic {@code scheduleAtFixedRate(...)} calls on a {@code ScheduledExecutorService},
 * {@code java.util.Timer} or Spring {@code TaskScheduler}. Spring Framework's checkpoint/restore reference
 * documentation and the OpenJDK CRaC javadoc of {@code ScheduledExecutorService} and {@code Timer} both warn
 * that missed fixed-rate executions are caught up after restore, because the period is computed from the
 * previous scheduled time rather than from completion.
 *
 * <p>Declarative tasks are registered when the context is refreshed, after the automatic startup checkpoint
 * ({@code spring.context.checkpoint=onRefresh}), so they are excluded only while that pre-start phase is
 * observed. Programmatic calls can run from constructors or initialization callbacks before that checkpoint
 * and are never excluded by it; calls from {@code afterRestore()} or {@code Lifecycle.start()} are, because
 * rescheduling there is the documented fix.</p>
 */
final class ScheduledFixedRateTaskCheck implements CracCheck {

    @Override
    public Evidence evidence() {
        return Evidence.BOTH;
    }

    private static final String SCHEDULED_ANNOTATION = "org.springframework.scheduling.annotation.Scheduled";

    private static final String TASK_REGISTRAR = "org.springframework.scheduling.config.ScheduledTaskRegistrar";

    private static final List<String> FIXED_RATE_SCHEDULERS = List.of(
            "java.util.concurrent.ScheduledExecutorService",
            "java.util.Timer",
            "org.springframework.scheduling.TaskScheduler");

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-SCHED-001",
            "Fixed-rate scheduled tasks may run a catch-up burst after restore",
            CracCategory.THREADS,
            "MEDIUM",
            "Detects fixed-rate declarations in direct, repeated and composed @Scheduled metadata, ScheduledTaskRegistrar.addFixedRateTask registrations, and programmatic scheduleAtFixedRate calls on ScheduledExecutorService, Timer or Spring TaskScheduler. Spring and the OpenJDK CRaC javadoc document catch-up executions after restore. A declaration or call site does not prove that the task is active; property placeholders, composed attribute overrides and custom scheduler behavior still require verification.",
            "If catch-up is unwanted, consider fixedDelay/scheduleWithFixedDelay, an appropriate cron trigger, or cancelling the task before checkpoint and rescheduling it after restore (calls from afterRestore()/Lifecycle.start() are not flagged). These are different scheduling semantics, not interchangeable fixes. Declarative tasks are excluded only while the original pre-lifecycle onRefresh checkpoint phase is observed; programmatic calls may run before that checkpoint and are always checked.",
            "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html#_on_demand_checkpointrestore_of_a_running_application");

    @Override
    public CracCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CracFindingDto evaluate(CracContext context) {
        try {
            boolean preStartOnRefresh = context.runtime().checkpointOnRefresh()
                    && !context.runtime().restoredProcess()
                    && !context.runtime().applicationRunning();
            List<String> samples = new ArrayList<>();
            int count = 0;
            for (JavaClass javaClass : context.classes()) {
                if (!preStartOnRefresh) {
                    for (JavaMethod method : javaClass.getMethods()) {
                        if (declaresFixedRate(method)) {
                            count++;
                            addSample(samples, javaClass.getName() + "." + method.getName() + "()");
                        }
                    }
                }
                for (JavaCodeUnit codeUnit : javaClass.getCodeUnits()) {
                    for (JavaCall<?> call : codeUnit.getCallsFromSelf()) {
                        if (ManagedLifecycleCallSites.isExemptCallSite(call)) {
                            continue;
                        }
                        String scheduler = fixedRateScheduler(call.getTarget());
                        boolean registrar = scheduler == null && registersFixedRateTask(call.getTarget());
                        if (scheduler != null || registrar && !preStartOnRefresh) {
                            count++;
                            addSample(
                                    samples,
                                    javaClass.getName() + "." + codeUnit.getName() + "() calls "
                                            + (registrar ? "ScheduledTaskRegistrar.addFixedRateTask" : scheduler)
                                            + (registrar ? "" : ".scheduleAtFixedRate"));
                        }
                    }
                }
            }
            if (count == 0 && preStartOnRefresh) {
                return CracCheckSupport.skipped(
                        DEFINITION,
                        "spring.context.checkpoint=onRefresh checkpoints before declarative scheduled tasks start, and no programmatic fixed-rate scheduling was found.");
            }
            if (count == 0) {
                return CracCheckSupport.ok(DEFINITION);
            }
            return CracCheckSupport.review(DEFINITION, count, samples);
        } catch (RuntimeException | LinkageError ex) {
            return CracCheckSupport.error(DEFINITION, ex);
        }
    }

    private static void addSample(List<String> samples, String sample) {
        if (samples.size() < CracCheckSupport.maxSampleOccurrences()) {
            samples.add(CracCheckSupport.detail(sample));
        }
    }

    private static String fixedRateScheduler(CodeUnitCallTarget target) {
        if (!"scheduleAtFixedRate".equals(target.getName())) {
            return null;
        }
        JavaClass owner = target.getOwner();
        for (String scheduler : FIXED_RATE_SCHEDULERS) {
            if (scheduler.equals(owner.getName()) || owner.isAssignableTo(scheduler)) {
                return scheduler.substring(scheduler.lastIndexOf('.') + 1);
            }
        }
        return null;
    }

    private static boolean registersFixedRateTask(CodeUnitCallTarget target) {
        return "addFixedRateTask".equals(target.getName())
                && (TASK_REGISTRAR.equals(target.getOwner().getName())
                        || target.getOwner().isAssignableTo(TASK_REGISTRAR));
    }

    private static boolean declaresFixedRate(JavaMethod method) {
        for (JavaAnnotation<?> annotation : method.getAnnotations()) {
            if (declaresFixedRate(annotation, new HashSet<>(), 0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresFixedRate(JavaAnnotation<?> annotation, Set<String> visited, int depth) {
        if (depth > 16) {
            return false;
        }
        String type = annotation.getRawType().getName();
        if (SCHEDULED_ANNOTATION.equals(type)) {
            Object rate = annotation.get("fixedRate").orElse(-1L);
            Object rateString = annotation.get("fixedRateString").orElse("");
            return rate instanceof Number number && number.longValue() >= 0
                    || rateString instanceof String value && !value.isBlank();
        }
        if ("org.springframework.scheduling.annotation.Schedules".equals(type)) {
            Object values = annotation.get("value").orElse(null);
            if (values instanceof JavaAnnotation<?>[] schedules) {
                for (JavaAnnotation<?> schedule : schedules) {
                    if (declaresFixedRate(schedule, visited, depth + 1)) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (!visited.add(type)) {
            return false;
        }
        for (JavaAnnotation<?> meta : annotation.getRawType().getAnnotations()) {
            if (declaresFixedRate(meta, visited, depth + 1)) {
                return true;
            }
        }
        return false;
    }
}

/**
 * Reports whether the application-facing {@code org.crac:crac} API is present on the classpath.
 * When it is absent, an application has no library to implement {@code org.crac.Resource} against, so it cannot hook
 * {@code beforeCheckpoint()}/{@code afterRestore()} to release and reacquire resources, re-seed
 * randomness, or refresh secrets around a checkpoint - the fix every other resource/random/secret
 * finding in this rule set recommends.
 *
 * <p>Like {@link ConnectionPoolCheck} and {@link CacheManagerCheck} this reads the live {@link
 * CracRuntimeInventory} rather than the imported application bytecode, because classpath presence is a
 * runtime/dependency signal, not something visible in any one class's bytecode.</p>
 */
final class CracDependencyCheck implements CracCheck {

    @Override
    public Evidence evidence() {
        return Evidence.RUNTIME;
    }

    private static final CracCheckDefinition PLANNING_DEFINITION = new CracCheckDefinition(
            "CRAC-LIFECYCLE-002",
            "The org.crac:crac API is not on the classpath",
            CracCategory.LIFECYCLE,
            "MEDIUM",
            "Detects whether the org.crac:crac compatibility API (org.crac.Core / org.crac.Resource) is present on the application's classpath. Spring Boot's checkpoint/restore auto-configuration is conditional on this API even when the CRaC-enabled JDK exposes its vendor implementation as javax.crac or jdk.crac.",
            "Add org.crac:crac (its version is managed by the Spring Boot BOM) so application classes and Spring Boot integrations can register org.crac.Resource callbacks. Vendor packages such as javax.crac and jdk.crac are implementation details and are detected separately as JVM capability markers.",
            "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html");

    private static final CracCheckDefinition CHECKPOINT_BLOCKER_DEFINITION = new CracCheckDefinition(
            PLANNING_DEFINITION.id(),
            PLANNING_DEFINITION.name(),
            PLANNING_DEFINITION.category(),
            "HIGH",
            PLANNING_DEFINITION.description(),
            PLANNING_DEFINITION.recommendation(),
            PLANNING_DEFINITION.learnMoreUrl());

    @Override
    public CracCheckDefinition definition() {
        return PLANNING_DEFINITION;
    }

    @Override
    public CracFindingDto evaluate(CracContext context) {
        try {
            CracCheckDefinition definition =
                    context.runtime().checkpointOnRefresh() ? CHECKPOINT_BLOCKER_DEFINITION : PLANNING_DEFINITION;
            if (context.runtime().cracApiPresent()) {
                return CracCheckSupport.ok(definition);
            }
            return CracCheckSupport.review(
                    definition,
                    1,
                    List.of(
                            CracCheckSupport.detail(
                                    "org.crac.Core is not on the classpath; Spring Boot CRaC lifecycle integrations cannot activate.")));
        } catch (RuntimeException | LinkageError ex) {
            return CracCheckSupport.error(PLANNING_DEFINITION, ex);
        }
    }
}

/**
 * Flags fields that hold a long-lived transport-owning client with its own connection pool or event-loop
 * threads (the JDK's {@code java.net.http.HttpClient}, Apache HttpClient's {@code CloseableHttpClient},
 * OkHttp's {@code OkHttpClient}, Reactor Netty's {@code ConnectionProvider}, gRPC's {@code ManagedChannel},
 * Kafka's {@code KafkaProducer}/{@code KafkaConsumer}, Lettuce and Jedis Redis clients, or a Netty
 * {@code EventLoopGroup}) outside a managed checkpoint/restore lifecycle. These hold sockets and background threads exactly like the raw socket/pool types {@link
 * OpenResourceFieldCheck} already covers, but are easy to miss because the client is typically built
 * once via a builder rather than constructed directly.
 *
 * <p>Compatible cleanup in a CRaC or Spring stop callback is contextual evidence, matching
 * {@link OpenResourceFieldCheck}; it cannot establish the receiver's identity or registration.</p>
 */
final class UnmanagedHttpClientFieldCheck implements CracCheck {

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-POOL-002",
            "Transport-owning client fields need checkpoint lifecycle review",
            CracCategory.POOLS,
            "HIGH",
            "Detects fields typed as known transport-owning clients: HTTP/RPC clients (JDK HttpClient, Apache CloseableHttpClient, OkHttpClient, Reactor Netty ConnectionProvider, gRPC ManagedChannel), Kafka KafkaProducer/KafkaConsumer, Lettuce Redis clients, Jedis pools and Netty EventLoopGroup. A field does not prove an active connection or that the application owns the instance; it may reference a Spring-managed or shared resource. Compatible lifecycle cleanup is contextual evidence, not proof of which instance is closed or of registration. Spring facades (RestClient, WebClient, Reactor HttpClient, KafkaTemplate, RedisTemplate) and generic Producer/Consumer interfaces are deliberately excluded.",
            "Verify actual transport ownership before adding lifecycle handling. Preserve existing Spring-managed transports and shared resources. Where explicit shutdown is needed, use the deployed client's lifecycle API (for example close(), shutdown(), or Netty's shutdownGracefully() followed by awaiting termination) and account for in-flight work; JDK HttpClient shutdown APIs require Java 21 or later. Never close a client during a readiness scan.",
            "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html");

    private static final Set<String> HTTP_CLIENT_TYPES = Set.of(
            "java.net.http.HttpClient",
            "org.apache.hc.client5.http.impl.classic.CloseableHttpClient",
            "org.apache.http.impl.client.CloseableHttpClient",
            "okhttp3.OkHttpClient",
            "reactor.netty.resources.ConnectionProvider",
            "io.grpc.ManagedChannel",
            "org.apache.kafka.clients.producer.KafkaProducer",
            "org.apache.kafka.clients.consumer.KafkaConsumer",
            "io.lettuce.core.AbstractRedisClient",
            "redis.clients.jedis.JedisPool",
            "redis.clients.jedis.UnifiedJedis",
            "io.netty.channel.EventLoopGroup");

    @Override
    public CracCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CracFindingDto evaluate(CracContext context) {
        try {
            List<String> samples = new ArrayList<>();
            int count = 0;
            boolean withoutCleanup = false;
            for (JavaClass javaClass : context.classes()) {
                for (JavaField field : javaClass.getFields()) {
                    if (isHttpClientType(field.getRawType())) {
                        count++;
                        boolean cleanup = ManagedLifecycleCallSites.hasCompatibleCleanupCall(javaClass, field);
                        withoutCleanup |= !cleanup;
                        if (samples.size() < CracCheckSupport.maxSampleOccurrences()) {
                            samples.add(CracCheckSupport.detail(javaClass.getName() + "." + field.getName()
                                    + (cleanup
                                            ? " - compatible cleanup observed; field target and registration unverified"
                                            : " - no compatible lifecycle cleanup observed")));
                        }
                    }
                }
            }
            if (count == 0) {
                return CracCheckSupport.ok(DEFINITION);
            }
            return CracCheckSupport.review(
                    withoutCleanup ? DEFINITION : DEFINITION.withSeverity("MEDIUM"), count, samples);
        } catch (RuntimeException | LinkageError ex) {
            return CracCheckSupport.error(DEFINITION, ex);
        }
    }

    private static boolean isHttpClientType(JavaClass type) {
        for (String httpClientType : HTTP_CLIENT_TYPES) {
            if (type.isAssignableTo(httpClientType)) {
                return true;
            }
        }
        return false;
    }

    static boolean isKnownTransportOwner(String typeName) {
        return HTTP_CLIENT_TYPES.contains(typeName);
    }
}
