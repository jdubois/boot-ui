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
            Set.of("cancel", "close", "destroy", "disconnect", "shutdown", "shutdownNow", "stop");

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
 * Flags {@link java.util.Random} fields and explicit {@link java.security.SecureRandom} seeding.
 */
final class RandomFieldCheck implements CracCheck {

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-RANDOM-001",
            "Random state or explicit SecureRandom seeding needs restore handling",
            CracCategory.RANDOMNESS,
            "HIGH",
            "Detects java.util.Random fields plus SecureRandom(byte[]) construction and SecureRandom.setSeed(...) calls outside restore/start callbacks. Retained generator state may be duplicated in restored processes. This signal does not establish security-sensitive use, actual retention, or the deployed provider's restore behavior.",
            "For security-sensitive uniqueness, use an appropriately initialized SecureRandom and verify the exact deployed JDK/provider. Review explicit seeds and cloned Random state; intentional deterministic simulation may need no change. Refresh state after restore only when independent sequences are required. Exact restore/start seed calls are excluded, but their entropy quality is not assessed.",
            "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html");

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
                    JavaClass type = field.getRawType();
                    if (type.isAssignableTo("java.util.Random") && !type.isAssignableTo("java.security.SecureRandom")) {
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
            return CracCheckSupport.review(DEFINITION, count, samples);
        } catch (RuntimeException | LinkageError ex) {
            return CracCheckSupport.error(DEFINITION, ex);
        }
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
            "Detects String/char[]/byte[] fields whose normalized name ends in secret, password, token, API key, credential, or private key, plus fields typed as SecretKey, PrivateKey, KeyStore, or KeyPair. The signal does not read values and cannot prove a field is populated, but any sensitive value seen before checkpoint must be assumed present in the image.",
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

    private static boolean isCapturedSecret(JavaField field) {
        JavaClass type = field.getRawType();
        boolean secretByName = hasSecretName(field.getName()) && SECRET_TYPES.contains(type.getName());
        return secretByName || isKeyType(type);
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
            "Detects known local, in-heap Spring CacheManager implementations (currently ConcurrentMapCacheManager and CaffeineCacheManager). Cache entries populated before checkpoint are frozen into the image and may be stale after restore. Unknown, no-op, and remote-backed manager types are not classified as local from type evidence alone.",
            "Clear or refresh time-sensitive local caches in an org.crac.Resource.afterRestore() callback, or use restore-aware expiry, so a restored process does not serve data captured at checkpoint time.",
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
 * Flags {@code @Scheduled} methods that explicitly declare {@code fixedRate} or {@code
 * fixedRateString}. Spring Framework's checkpoint/restore reference documentation warns that
 * <em>on-demand</em> checkpoint/restore of an already-running application can produce a catch-up
 * burst: fixed-rate scheduling maintains a periodic schedule rather than delaying from completion.
 * The actual scheduler and deployment determine how the checkpoint gap affects pending executions.
 *
 * <p>This is specific to on-demand checkpoint/restore of an already-running application. Automatic
 * checkpoint/restore at startup ({@code spring.context.checkpoint=onRefresh}) takes the checkpoint
 * before the scheduler has started, so no executions have been missed yet at that point.</p>
 */
final class ScheduledFixedRateTaskCheck implements CracCheck {

    @Override
    public Evidence evidence() {
        return Evidence.BOTH;
    }

    private static final String SCHEDULED_ANNOTATION = "org.springframework.scheduling.annotation.Scheduled";

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-SCHED-001",
            "Fixed-rate scheduled tasks may run a catch-up burst after restore",
            CracCategory.THREADS,
            "MEDIUM",
            "Detects fixed-rate declarations in direct, repeated and composed @Scheduled metadata. Spring documents catch-up executions after an on-demand restore. A declaration does not prove that the task is active; property placeholders, composed attribute overrides and custom scheduler behavior still require verification.",
            "If catch-up is unwanted, consider fixedDelay, an appropriate cron trigger, or explicit rescheduling after restore, accounting for the scheduler's execution model. These are different scheduling semantics, not interchangeable fixes. The original pre-lifecycle onRefresh checkpoint is excluded only when that phase is observed; a running or restored process is checked even if the original property remains set.",
            "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html#_on_demand_checkpointrestore_of_a_running_application");

    @Override
    public CracCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CracFindingDto evaluate(CracContext context) {
        try {
            if (context.runtime().checkpointOnRefresh()
                    && !context.runtime().restoredProcess()
                    && !context.runtime().applicationRunning()) {
                return CracCheckSupport.skipped(
                        DEFINITION,
                        "spring.context.checkpoint=onRefresh checkpoints before scheduled tasks start; this check applies only to on-demand checkpoints of a running application.");
            }
            List<String> samples = new ArrayList<>();
            int count = 0;
            for (JavaClass javaClass : context.classes()) {
                for (JavaMethod method : javaClass.getMethods()) {
                    if (declaresFixedRate(method)) {
                        count++;
                        if (samples.size() < CracCheckSupport.maxSampleOccurrences()) {
                            samples.add(CracCheckSupport.detail(javaClass.getName() + "." + method.getName() + "()"));
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
 * Flags fields that hold a long-lived HTTP/RPC client with its own connection pool or event-loop
 * threads (the JDK's {@code java.net.http.HttpClient}, Apache HttpClient's {@code
 * CloseableHttpClient}, OkHttp's {@code OkHttpClient}, Reactor Netty's {@code HttpClient}/{@code
 * ConnectionProvider}, or gRPC's {@code ManagedChannel}) outside a managed checkpoint/restore
 * lifecycle. These hold sockets and background threads exactly like the raw socket/pool types {@link
 * OpenResourceFieldCheck} already covers, but are easy to miss because the client is typically built
 * once via a builder rather than constructed directly.
 *
 * <p>Compatible cleanup in a CRaC or Spring stop callback is contextual evidence, matching
 * {@link OpenResourceFieldCheck}; it cannot establish the receiver's identity or registration.</p>
 */
final class UnmanagedHttpClientFieldCheck implements CracCheck {

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-POOL-002",
            "HTTP/RPC transport owners need checkpoint lifecycle review",
            CracCategory.POOLS,
            "HIGH",
            "Detects fields typed as known HTTP/RPC transport owners or clients. A field does not prove an active connection. Compatible lifecycle cleanup is contextual evidence, not proof of which instance is closed or of registration. Spring RestClient/WebClient and Reactor HttpClient facades are deliberately excluded.",
            "Verify actual transport ownership before adding lifecycle handling. Preserve existing Spring-managed transports and shared resources. Where explicit shutdown is needed, use the deployed client's lifecycle API and account for in-flight work; JDK HttpClient shutdown APIs require Java 21 or later. Never close a client during a readiness scan.",
            "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html");

    private static final Set<String> HTTP_CLIENT_TYPES = Set.of(
            "java.net.http.HttpClient",
            "org.apache.hc.client5.http.impl.classic.CloseableHttpClient",
            "org.apache.http.impl.client.CloseableHttpClient",
            "okhttp3.OkHttpClient",
            "reactor.netty.resources.ConnectionProvider",
            "io.grpc.ManagedChannel");

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
}
