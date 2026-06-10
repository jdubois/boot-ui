package io.github.jdubois.bootui.autoconfigure.crac;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitCallTarget;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaStaticInitializer;
import com.tngtech.archunit.lang.ArchRule;
import io.github.jdubois.bootui.core.dto.CracFindingDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

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
            return CracCheckSupport.error(definition, "Check could not be evaluated: " + ex.getMessage());
        }
    }
}

/**
 * Flags direct construction of network sockets ({@code new Socket}, {@code new ServerSocket},
 * {@code new DatagramSocket}, {@code new MulticastSocket}). Open network endpoints must be closed
 * before a checkpoint and re-opened after restore, otherwise the snapshot captures dead file
 * descriptors and the restored process fails or leaks connections.
 */
final class SocketConstructionCheck extends AbstractArchUnitCracCheck {

    private static final Set<String> SOCKET_TYPES = Set.of(
            "java.net.Socket",
            "java.net.ServerSocket",
            "java.net.DatagramSocket",
            "java.net.MulticastSocket",
            "java.nio.channels.ServerSocketChannel",
            "java.nio.channels.SocketChannel");

    SocketConstructionCheck() {
        super(new CracCheckDefinition(
                "CRAC-NET-001",
                "Direct network socket creation must be released at checkpoint",
                CracCategory.NETWORK,
                "HIGH",
                "Detects code that opens network sockets directly (new Socket/ServerSocket/DatagramSocket/MulticastSocket or NIO channels). Open sockets hold OS file descriptors that CRaC refuses to checkpoint unless they are closed first.",
                "Close the socket in an org.crac.Resource.beforeCheckpoint() callback and re-open it in afterRestore(), or let a managed component (Spring Lifecycle bean, server connector) own the socket so the framework closes it around the checkpoint.",
                "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html"));
    }

    @Override
    ArchRule rule(CracContext context) {
        return noClasses()
                .should()
                .callCodeUnitWhere(new DescribedPredicate<JavaCall<?>>("a network socket is constructed") {
                    @Override
                    public boolean test(JavaCall<?> call) {
                        CodeUnitCallTarget target = call.getTarget();
                        return "<init>".equals(target.getName())
                                && SOCKET_TYPES.contains(target.getOwner().getName());
                    }
                })
                .as("Classes should not open network sockets that survive a checkpoint");
    }
}

/**
 * Flags threads, timers, and executor pools created directly rather than through a Spring-managed
 * lifecycle. CRaC pauses Spring {@code Lifecycle} beans before a checkpoint; threads started outside
 * that lifecycle keep running and are captured mid-flight, which often deadlocks or corrupts state
 * after restore.
 */
final class UnmanagedThreadCheck extends AbstractArchUnitCracCheck {

    private static final Set<String> THREAD_TYPES = Set.of(
            "java.lang.Thread",
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
            "newVirtualThreadPerTaskExecutor");

    UnmanagedThreadCheck() {
        super(new CracCheckDefinition(
                "CRAC-THREAD-001",
                "Threads or executor pools created outside the Spring lifecycle",
                CracCategory.THREADS,
                "MEDIUM",
                "Detects threads, timers, and executor pools created directly (new Thread/Timer/ThreadPoolExecutor or Executors factory methods). Such threads are not paused by CRaC before a checkpoint and are snapshotted while running.",
                "Drive background work through Spring (e.g. @Async, TaskExecutor/TaskScheduler beans, @Scheduled) so the lifecycle stops it before checkpoint, or register an org.crac.Resource that quiesces the pool in beforeCheckpoint() and restarts it in afterRestore().",
                "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html"));
    }

    @Override
    ArchRule rule(CracContext context) {
        return noClasses()
                .should()
                .callCodeUnitWhere(new DescribedPredicate<JavaCall<?>>("an unmanaged thread or pool is created") {
                    @Override
                    public boolean test(JavaCall<?> call) {
                        CodeUnitCallTarget target = call.getTarget();
                        String owner = target.getOwner().getName();
                        String name = target.getName();
                        if ("<init>".equals(name) && THREAD_TYPES.contains(owner)) {
                            return true;
                        }
                        return "java.util.concurrent.Executors".equals(owner) && EXECUTOR_FACTORIES.contains(name);
                    }
                })
                .as("Classes should not create threads or pools outside the Spring lifecycle");
    }
}

/**
 * Flags capture of wall-clock time in static initializers. With CRaC the static initializer runs
 * once when the original JVM starts; the captured value is frozen into the checkpoint image and is
 * stale (sometimes by days) in every restored process.
 */
final class CapturedTimeCheck extends AbstractArchUnitCracCheck {

    private static final Set<String> SYSTEM_TIME = Set.of("currentTimeMillis", "nanoTime");

    CapturedTimeCheck() {
        super(new CracCheckDefinition(
                "CRAC-TIME-001",
                "Static initializer captures wall-clock time",
                CracCategory.TIME,
                "MEDIUM",
                "Detects static initializers that read the current time (System.currentTimeMillis/nanoTime, java.time now(), new Date(), Instant/Clock). The value is frozen into the checkpoint image and becomes stale after every restore.",
                "Read the time when it is needed at runtime instead of caching it in a static field, or refresh the cached value in an org.crac.Resource.afterRestore() callback.",
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
                        return "java.util.Date".equals(owner) && "<init>".equals(name);
                    }
                })
                .as("Static initializers should not capture wall-clock time before a checkpoint");
    }
}

/**
 * Flags concrete application classes that keep an open resource (socket, file, JDBC connection,
 * channel) in a field without participating in a managed lifecycle. CRaC cannot checkpoint live OS
 * resources, so the holder must release them before the checkpoint.
 */
final class OpenResourceFieldCheck implements CracCheck {

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-RES-001",
            "Open resources held in fields must be released at checkpoint",
            CracCategory.RESOURCES,
            "HIGH",
            "Detects fields whose type holds an OS resource (sockets, file streams, RandomAccessFile, NIO channels, JDBC Connection) on classes that do not implement org.crac.Resource or a Spring Lifecycle. CRaC cannot snapshot live file descriptors.",
            "Implement org.crac.Resource and close the resource in beforeCheckpoint(), re-opening it in afterRestore(); or hold the resource in a Spring Lifecycle/SmartLifecycle bean so the framework stops it before the checkpoint.",
            "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html");

    private static final Set<String> RESOURCE_TYPES = Set.of(
            "java.net.Socket",
            "java.net.ServerSocket",
            "java.net.DatagramSocket",
            "java.net.MulticastSocket",
            "java.io.FileInputStream",
            "java.io.FileOutputStream",
            "java.io.RandomAccessFile",
            "java.nio.channels.FileChannel",
            "java.nio.channels.SocketChannel",
            "java.nio.channels.ServerSocketChannel",
            "java.sql.Connection");

    private static final Set<String> MANAGED_TYPES = Set.of(
            "org.crac.Resource", "org.springframework.context.Lifecycle", "org.springframework.context.SmartLifecycle");

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
                if (isManaged(javaClass)) {
                    continue;
                }
                for (JavaField field : javaClass.getFields()) {
                    if (isResourceType(field.getRawType())) {
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
            return CracCheckSupport.error(DEFINITION, "Check could not be evaluated: " + ex.getMessage());
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

    private static boolean isManaged(JavaClass javaClass) {
        for (String managed : MANAGED_TYPES) {
            if (javaClass.isAssignableTo(managed)) {
                return true;
            }
        }
        return false;
    }
}

/**
 * Flags static {@link java.util.Random} / {@link java.security.SecureRandom} fields. Their seed and
 * internal state are frozen into the checkpoint image, so every restored process produces the same
 * "random" sequence — a correctness problem and, for SecureRandom, a security weakness.
 */
final class StaticRandomFieldCheck implements CracCheck {

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-RANDOM-001",
            "Static Random/SecureRandom state is frozen into the checkpoint",
            CracCategory.RANDOMNESS,
            "HIGH",
            "Detects static fields of type java.util.Random or java.security.SecureRandom. Their internal state is captured at checkpoint time, so every restored instance replays the same sequence.",
            "Re-seed or recreate the generator in an org.crac.Resource.afterRestore() callback, or avoid a static instance so a fresh generator is created per restored process.",
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
                    if (field.getModifiers().contains(JavaModifier.STATIC)
                            && field.getRawType().isAssignableTo("java.util.Random")) {
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
            return CracCheckSupport.error(DEFINITION, "Check could not be evaluated: " + ex.getMessage());
        }
    }
}

/**
 * Flags static fields whose name suggests an eagerly captured secret (token, password, API key,
 * credential). A secret loaded into a static field at startup is baked into the checkpoint image and
 * shipped with every restored process, so rotation no longer takes effect and the snapshot leaks the
 * value.
 */
final class CapturedSecretFieldCheck implements CracCheck {

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-SECRET-001",
            "Secrets captured in static fields are frozen into the checkpoint",
            CracCategory.SECRETS,
            "HIGH",
            "Detects static fields whose name looks like a secret (token, password, secret, api key, credential, private key) holding a String, char[], or byte[]. Such values are captured into the checkpoint image and survive in every restored process.",
            "Load secrets lazily at runtime (or refresh them in an org.crac.Resource.afterRestore() callback) instead of caching them in a static field, so a checkpoint never freezes a credential.",
            "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html");

    private static final Pattern SECRET_NAME =
            Pattern.compile(".*(secret|password|passwd|token|apikey|api_key|credential|privatekey|private_key).*");

    private static final Set<String> SECRET_TYPES = Set.of("java.lang.String", "char[]", "byte[]", "[C", "[B");

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
                    if (field.getModifiers().contains(JavaModifier.STATIC)
                            && SECRET_NAME
                                    .matcher(field.getName().toLowerCase())
                                    .matches()
                            && SECRET_TYPES.contains(field.getRawType().getName())) {
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
            return CracCheckSupport.error(DEFINITION, "Check could not be evaluated: " + ex.getMessage());
        }
    }
}

/**
 * Reports whether the application registers any {@code org.crac.Resource} implementations. CRaC
 * relies on these callbacks to release and re-acquire stateful resources around a checkpoint; an
 * application with none typically still has work to do before it can checkpoint cleanly.
 */
final class ResourceRegistrationCheck implements CracCheck {

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-LIFECYCLE-001",
            "No org.crac.Resource implementations were found",
            CracCategory.LIFECYCLE,
            "INFO",
            "Reports whether the application implements org.crac.Resource. These callbacks let components release and re-acquire stateful resources around a checkpoint; an application with none usually relies entirely on Spring lifecycle handling.",
            "If the application owns resources that Spring does not manage (custom sockets, native handles, caches), implement org.crac.Resource and register it with Core.getGlobalContext().register(...) so it participates in checkpoint/restore.",
            "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html");

    @Override
    public CracCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CracFindingDto evaluate(CracContext context) {
        try {
            List<String> implementers = new ArrayList<>();
            for (JavaClass javaClass : context.classes()) {
                if (!javaClass.isInterface() && javaClass.isAssignableTo("org.crac.Resource")) {
                    implementers.add(javaClass.getName());
                }
            }
            if (!implementers.isEmpty()) {
                return CracCheckSupport.ok(DEFINITION);
            }
            return CracCheckSupport.review(
                    DEFINITION,
                    1,
                    List.of(
                            CracCheckSupport.detail(
                                    "No application class implements org.crac.Resource; resource handling relies on Spring lifecycle only.")));
        } catch (RuntimeException | LinkageError ex) {
            return CracCheckSupport.error(DEFINITION, "Check could not be evaluated: " + ex.getMessage());
        }
    }
}
