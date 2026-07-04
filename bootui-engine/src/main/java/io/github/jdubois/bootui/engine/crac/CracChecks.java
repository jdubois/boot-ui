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
import com.tngtech.archunit.core.domain.JavaStaticInitializer;
import com.tngtech.archunit.lang.ArchRule;
import io.github.jdubois.bootui.core.dto.CracFindingDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * Shared "this class/call participates in a managed checkpoint/restore lifecycle" logic for the
 * readiness checks. A class is managed if it implements {@code org.crac.Resource} or a Spring
 * {@code Lifecycle}/{@code SmartLifecycle}, because Spring (or the application itself, through the
 * org.crac callbacks) already stops and restarts it around a checkpoint.
 *
 * <p>{@link #isExemptCallSite(JavaCall)} additionally scopes the exemption to a call that
 * <em>originates</em> from one of the managed callback methods themselves ({@code beforeCheckpoint} /
 * {@code afterRestore} for {@code org.crac.Resource}, {@code start} / {@code stop} for Spring
 * {@code Lifecycle}). Without this, doing exactly what the affected checks' own recommendation says -
 * implement the managed interface and re-acquire the resource inside the restore callback - would
 * permanently and unavoidably keep re-triggering the same finding, because the checks had no way to
 * recognize that the call happens inside a properly managed restore path. Scoping the exemption to the
 * callback methods (rather than the whole class) still catches an unrelated leak elsewhere in an
 * otherwise-managed class.</p>
 */
final class ManagedLifecycleCallSites {

    private static final Set<String> MANAGED_TYPES = Set.of(
            "org.crac.Resource", "org.springframework.context.Lifecycle", "org.springframework.context.SmartLifecycle");

    private static final Set<String> MANAGED_CALLBACK_METHODS =
            Set.of("beforeCheckpoint", "afterRestore", "start", "stop");

    private ManagedLifecycleCallSites() {}

    static boolean isExemptCallSite(JavaCall<?> call) {
        JavaCodeUnit origin = call.getOrigin();
        return MANAGED_CALLBACK_METHODS.contains(origin.getName()) && isManagedClass(call.getOriginOwner());
    }

    static boolean isManagedClass(JavaClass javaClass) {
        for (String managedType : MANAGED_TYPES) {
            if (javaClass.isAssignableTo(managedType)) {
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
 * actually uses instead of a constructor. Open network endpoints must be closed before a checkpoint and
 * re-opened after restore, otherwise the snapshot captures dead file descriptors and the restored
 * process fails or leaks connections.
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
                "Direct network socket creation must be released at checkpoint",
                CracCategory.NETWORK,
                "HIGH",
                "Detects code that opens network sockets directly (new Socket/ServerSocket/DatagramSocket/MulticastSocket, or the NIO SocketChannel/ServerSocketChannel/DatagramChannel/AsynchronousSocketChannel/AsynchronousServerSocketChannel.open(...) factory methods) outside a managed checkpoint/restore lifecycle. Open sockets hold OS file descriptors that CRaC refuses to checkpoint unless they are closed first.",
                "Close the socket in an org.crac.Resource.beforeCheckpoint() callback and re-open it in afterRestore(), or let a managed component (Spring Lifecycle bean, server connector) own the socket so the framework closes it around the checkpoint. A call to reopen the socket from inside afterRestore()/start() on a class that implements org.crac.Resource or Spring Lifecycle is the recommended pattern and is not flagged.",
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
 * {@code FileChannel} / {@code AsynchronousFileChannel} open factory methods). An open file holds an
 * OS file descriptor that CRaC refuses to checkpoint, so it must be closed first — otherwise the
 * checkpoint aborts with {@code CheckpointOpenFileException}.
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

    private static final Set<String> FILES_FACTORIES =
            Set.of("newInputStream", "newOutputStream", "newByteChannel", "newBufferedReader", "newBufferedWriter");

    private static final Set<String> CHANNEL_OPENERS =
            Set.of("java.nio.channels.FileChannel", "java.nio.channels.AsynchronousFileChannel");

    FileHandleCheck() {
        super(new CracCheckDefinition(
                "CRAC-FILE-001",
                "Direct file handles must be released at checkpoint",
                CracCategory.RESOURCES,
                "HIGH",
                "Detects code that opens file handles directly (new FileInputStream/FileOutputStream/RandomAccessFile/FileReader/FileWriter/ZipFile/JarFile, or the Files.newInputStream/newOutputStream/newByteChannel and FileChannel/AsynchronousFileChannel.open factory methods) outside a managed checkpoint/restore lifecycle. An open file holds an OS file descriptor that CRaC refuses to checkpoint, so it aborts with CheckpointOpenFileException.",
                "Close the file before the checkpoint (try-with-resources for short-lived handles, or release it in an org.crac.Resource.beforeCheckpoint() callback and reopen it in afterRestore()), or let a managed component own it so the framework closes it around the checkpoint. A call to reopen the file from inside afterRestore()/start() on a class that implements org.crac.Resource or Spring Lifecycle is the recommended pattern and is not flagged.",
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
                "Detects threads, timers, and executor pools created directly (new Thread/Timer/ThreadPoolExecutor or Executors factory methods) outside a managed checkpoint/restore lifecycle. CRIU (which CRaC is built on) freezes every OS thread to take a checkpoint, so an unmanaged thread is not gracefully stopped first the way a Spring-managed SmartLifecycle bean is — it is captured abruptly mid-execution, in whatever state it happens to be in, which risks deadlocks, stale locks, or inconsistent state after restore.",
                "Drive background work through Spring (e.g. @Async, TaskExecutor/TaskScheduler beans, @Scheduled) so the lifecycle stops it gracefully before checkpoint, or register an org.crac.Resource that quiesces the pool in beforeCheckpoint() and restarts it in afterRestore(). A call to restart the pool from inside afterRestore()/start() on a class that implements org.crac.Resource or Spring Lifecycle is the recommended pattern and is not flagged.",
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
            "Detects fields whose type holds an OS resource (sockets, file streams, FileReader/Writer, RandomAccessFile, zip/jar files, NIO channels and selectors, file locks, WatchService, Process, JDBC Connection) on classes that do not implement org.crac.Resource or a Spring Lifecycle. CRaC cannot snapshot live file descriptors. Auto-configured pools (a HikariCP DataSource, a Redis client) are the common case and are covered separately by CRAC-POOL-001.",
            "Implement org.crac.Resource and close the resource in beforeCheckpoint(), re-opening it in afterRestore(); or hold the resource in a Spring Lifecycle/SmartLifecycle bean so the framework stops it before the checkpoint. For auto-configured connection pools, see CRAC-POOL-001.",
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
            for (JavaClass javaClass : context.classes()) {
                if (ManagedLifecycleCallSites.isManagedClass(javaClass)) {
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
}

/**
 * Flags {@link java.util.Random} / {@link java.security.SecureRandom} fields, static or instance.
 * Idiomatic Spring code rarely uses a static PRNG field — the dominant pattern is a singleton-bean
 * instance field (a {@code @Component}-injected {@code SecureRandom}) — but a checkpoint snapshots
 * every object reachable from GC roots, not just static state, so an instance field is captured just as
 * completely as a static one.
 */
final class RandomFieldCheck implements CracCheck {

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-RANDOM-001",
            "Random/SecureRandom state is frozen into the checkpoint",
            CracCategory.RANDOMNESS,
            "HIGH",
            "Detects fields (static or instance) of type java.util.Random or java.security.SecureRandom. A checkpoint freezes whatever internal state the generator holds at that moment into the image, whether the field is static or an ordinary singleton-bean field.",
            "A JDK built for CRaC already auto-reseeds a no-arg, never-explicitly-seeded SecureRandom on restore, so plain `new SecureRandom()` usage is commonly safe there without any code change — but confirm your target JDK/runtime actually implements that behavior before relying on it, since it is not guaranteed on every CRaC-capable platform. An explicitly-seeded SecureRandom, or a plain java.util.Random, keeps its frozen state across restore in every case and needs its own fix: re-seed or recreate it in an org.crac.Resource.afterRestore() callback, or avoid caching a shared instance so a fresh generator is created per restored process.",
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
                    if (field.getRawType().isAssignableTo("java.util.Random")) {
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
 * Flags fields, static or instance, that eagerly capture a secret: either a field whose name suggests a
 * secret (token, password, API key, credential) holding a {@code String}/{@code char[]}/{@code byte[]},
 * or a field whose type is cryptographic key material ({@code SecretKey}, {@code PrivateKey}, {@code
 * KeyStore}, {@code KeyPair}) regardless of name. A secret loaded at startup — whether into a static
 * field or a singleton bean's instance field, such as an {@code @Value}-injected credential — is baked
 * into the checkpoint image and shipped with every restored process, so rotation no longer takes effect
 * and the snapshot leaks the value.
 */
final class CapturedSecretFieldCheck implements CracCheck {

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-SECRET-001",
            "Secrets captured in fields are frozen into the checkpoint",
            CracCategory.SECRETS,
            "HIGH",
            "Detects fields (static or instance) that capture a secret: a field whose name looks like a secret (token, password, secret, api key, credential, private key) holding a String, char[], or byte[], or a field holding cryptographic key material (SecretKey, PrivateKey, KeyStore, KeyPair) regardless of name. A checkpoint snapshots every object reachable from GC roots, so a credential cached in a singleton bean's instance field (e.g. an @Value-injected secret) is captured into the image exactly like a static field would be.",
            "Load secrets lazily at runtime (or refresh them in an org.crac.Resource.afterRestore() callback) instead of caching them in a field, so a checkpoint never freezes a credential.",
            "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html");

    private static final Pattern SECRET_NAME =
            Pattern.compile(".*(secret|password|passwd|token|apikey|api_key|credential|privatekey|private_key).*");

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
            return CracCheckSupport.error(DEFINITION, "Check could not be evaluated: " + ex.getMessage());
        }
    }

    private static boolean isCapturedSecret(JavaField field) {
        JavaClass type = field.getRawType();
        boolean secretByName =
                SECRET_NAME.matcher(field.getName().toLowerCase()).matches() && SECRET_TYPES.contains(type.getName());
        return secretByName || isKeyType(type);
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

/**
 * Flags auto-configured connection pools and pooled clients (JDBC {@code DataSource}s, R2DBC/Redis/
 * RabbitMQ/Kafka/Mongo/Cassandra/JMS connection factories and similar) that are live in the running
 * context. Such pools hold OS sockets that CRaC refuses to checkpoint while open: if a pooled
 * connection is still established when {@code spring.context.checkpoint=onRefresh} fires, CRaC aborts
 * with a {@code CheckpointOpenSocketException}.
 *
 * <p>Unlike the other checks this one reads the live {@link CracRuntimeInventory} rather than the
 * imported application bytecode, because pools are contributed by Spring Boot auto-configuration and
 * never appear in the application's own base package.</p>
 */
final class ConnectionPoolCheck implements CracCheck {

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-POOL-001",
            "Connection pools must hold no open connection at checkpoint",
            CracCategory.POOLS,
            "HIGH",
            "Detects live connection pools and pooled clients (JDBC DataSource, plus R2DBC/Redis/RabbitMQ/Kafka/Mongo/Cassandra/JMS connection factories and similar). A pooled connection that is still open when the checkpoint is taken holds an OS socket that CRaC refuses to snapshot, so the checkpoint aborts with CheckpointOpenSocketException. The backing service must also be reachable both when the checkpoint is taken and when it is restored.",
            "Ensure no pooled connection is open at checkpoint time: let the pool drain to zero idle connections (for example spring.datasource.hikari.minimum-idle=0), and keep the database/cache reachable at both checkpoint and restore. Take the checkpoint after the context refreshes but before traffic opens a connection. Do not assume the pool closes itself for CRaC: check whether your specific pool/client library has adopted org.crac.Resource natively. As of today, most common pools (including HikariCP and Lettuce, Spring Boot's default JDBC pool and Redis client) do not ship this out of the box, so unless you have verified otherwise for your library's current version, plan to register your own org.crac.Resource wrapper around the DataSource/connection factory bean to close and reopen it around the checkpoint.",
            "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html");

    @Override
    public CracCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CracFindingDto evaluate(CracContext context) {
        try {
            List<String> poolBeans = context.runtime().connectionPoolBeans();
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
            return CracCheckSupport.review(DEFINITION, poolBeans.size(), samples);
        } catch (RuntimeException | LinkageError ex) {
            return CracCheckSupport.error(DEFINITION, "Check could not be evaluated: " + ex.getMessage());
        }
    }
}

/**
 * Flags live Spring {@code CacheManager} beans that are backed by local, in-heap storage. Cache
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

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-CACHE-001",
            "In-memory caches may hold stale entries after restore",
            CracCategory.CACHES,
            "LOW",
            "Detects live Spring CacheManager beans backed by local, in-heap storage (for example ConcurrentMapCacheManager or Caffeine). Cache entries populated before the checkpoint are frozen into the image and survive into every restored process, where they may be stale (for example expired tokens or time-sensitive data). Cache managers backed by a remote/external store (for example Redis) are a lower concern and are not reported here, since their entries live outside the JVM heap and are not frozen into the checkpoint.",
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
            return CracCheckSupport.error(DEFINITION, "Check could not be evaluated: " + ex.getMessage());
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
                "Static initializer captures environment or system configuration",
                CracCategory.CONFIG,
                "MEDIUM",
                "Detects static initializers that read environment variables or system properties (System.getenv/getProperty/getProperties). With spring.context.checkpoint=onRefresh the value is read once when the original JVM starts and frozen into the checkpoint image, so changing it for a restore-only start has no effect until a new checkpoint is taken.",
                "Read environment- or property-derived configuration at runtime (or refresh it in an org.crac.Resource.afterRestore() callback) instead of caching it in a static field, so configuration changes take effect for a restored process.",
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
 * burst: fixed-rate scheduling computes each execution from a fixed wall-clock point rather than the
 * end of the previous run, so every execution missed during the idle gap between the checkpoint and a
 * later restore fires back-to-back immediately after restore.
 *
 * <p>This is specific to on-demand checkpoint/restore of an already-running application. Automatic
 * checkpoint/restore at startup ({@code spring.context.checkpoint=onRefresh}) takes the checkpoint
 * before the scheduler has started, so no executions have been missed yet at that point.</p>
 */
final class ScheduledFixedRateTaskCheck implements CracCheck {

    private static final String SCHEDULED_ANNOTATION = "org.springframework.scheduling.annotation.Scheduled";

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-SCHED-001",
            "Fixed-rate scheduled tasks may run a catch-up burst after restore",
            CracCategory.THREADS,
            "MEDIUM",
            "Detects @Scheduled methods that explicitly declare fixedRate or fixedRateString. Fixed-rate scheduling computes each execution from a fixed wall-clock point rather than the end of the previous run, so on-demand checkpoint/restore of a running application can leave a long idle gap between the checkpoint and a later restore; every execution missed during that gap fires back-to-back immediately after restore.",
            "If a catch-up burst after restore is not the behavior you want, switch to fixedDelay (or a cron expression), which Spring schedules relative to the end of the previous execution rather than a fixed wall-clock point, so a checkpoint/restore gap does not queue up missed runs. This risk is specific to on-demand checkpoint/restore of an already-running application; automatic checkpoint/restore at startup (spring.context.checkpoint=onRefresh) takes the checkpoint before the scheduler starts and is not affected.",
            "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html#_on_demand_checkpointrestore_of_a_running_application");

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
            return CracCheckSupport.error(DEFINITION, "Check could not be evaluated: " + ex.getMessage());
        }
    }

    private static boolean declaresFixedRate(JavaMethod method) {
        Optional<JavaAnnotation<JavaMethod>> scheduled = method.tryGetAnnotationOfType(SCHEDULED_ANNOTATION);
        if (scheduled.isEmpty()) {
            return false;
        }
        JavaAnnotation<JavaMethod> annotation = scheduled.get();
        return annotation.hasExplicitlyDeclaredProperty("fixedRate")
                || annotation.hasExplicitlyDeclaredProperty("fixedRateString");
    }
}

/**
 * Reports whether the {@code org.crac:crac} API is present on the application's classpath. Without it
 * an application has no library to implement {@code org.crac.Resource} against, so it cannot hook
 * {@code beforeCheckpoint()}/{@code afterRestore()} to release and reacquire resources, re-seed
 * randomness, or refresh secrets around a checkpoint - the fix every other resource/random/secret
 * finding in this rule set recommends.
 *
 * <p>Like {@link ConnectionPoolCheck} and {@link CacheManagerCheck} this reads the live {@link
 * CracRuntimeInventory} rather than the imported application bytecode, because classpath presence is a
 * runtime/dependency signal, not something visible in any one class's bytecode.</p>
 */
final class CracDependencyCheck implements CracCheck {

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-LIFECYCLE-002",
            "The org.crac:crac API is not on the classpath",
            CracCategory.LIFECYCLE,
            "MEDIUM",
            "Detects whether the org.crac:crac API (org.crac.Core / org.crac.Resource) is present on the application's classpath. A Spring Boot application on a CRaC-enabled JVM does not strictly need this dependency to take an automatic checkpoint, but without it no application class can implement org.crac.Resource, so it cannot hook beforeCheckpoint()/afterRestore() to release and reacquire resources, re-seed randomness, or refresh secrets around a checkpoint.",
            "Add the org.crac:crac dependency (its version is managed by the Spring Boot BOM) so application classes can implement org.crac.Resource and register with Core.getGlobalContext().register(...) to participate in checkpoint/restore.",
            "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html");

    @Override
    public CracCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CracFindingDto evaluate(CracContext context) {
        try {
            if (context.runtime().cracApiPresent()) {
                return CracCheckSupport.ok(DEFINITION);
            }
            return CracCheckSupport.review(
                    DEFINITION,
                    1,
                    List.of(
                            CracCheckSupport.detail(
                                    "org.crac.Core is not on the classpath; no application class can implement org.crac.Resource.")));
        } catch (RuntimeException | LinkageError ex) {
            return CracCheckSupport.error(DEFINITION, "Check could not be evaluated: " + ex.getMessage());
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
 * <p>A field on a class that already implements {@code org.crac.Resource} or a Spring {@code
 * Lifecycle}/{@code SmartLifecycle} is exempt, matching {@link OpenResourceFieldCheck}: the managed
 * class is expected to close and rebuild the client around the checkpoint itself.</p>
 */
final class UnmanagedHttpClientFieldCheck implements CracCheck {

    private static final CracCheckDefinition DEFINITION = new CracCheckDefinition(
            "CRAC-POOL-002",
            "HTTP/RPC clients hold sockets and threads that must be released at checkpoint",
            CracCategory.POOLS,
            "HIGH",
            "Detects fields that hold a long-lived HTTP/RPC client with its own connection pool or event-loop threads: the JDK's java.net.http.HttpClient, Apache HttpClient's CloseableHttpClient (4.x and 5.x), OkHttp's OkHttpClient, Reactor Netty's HttpClient/ConnectionProvider, or gRPC's ManagedChannel. Like a raw socket, these hold open OS file descriptors and background threads that CRaC cannot checkpoint while live, but they are easy to miss because the client comes from a builder rather than a bare constructor call.",
            "Close the client (and its underlying connection pool/event-loop) in an org.crac.Resource.beforeCheckpoint() callback and rebuild it in afterRestore(), or hold it in a Spring Lifecycle/SmartLifecycle bean so the framework stops it before the checkpoint.",
            "https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html");

    private static final Set<String> HTTP_CLIENT_TYPES = Set.of(
            "java.net.http.HttpClient",
            "org.apache.hc.client5.http.impl.classic.CloseableHttpClient",
            "org.apache.http.impl.client.CloseableHttpClient",
            "okhttp3.OkHttpClient",
            "reactor.netty.http.client.HttpClient",
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
            for (JavaClass javaClass : context.classes()) {
                if (ManagedLifecycleCallSites.isManagedClass(javaClass)) {
                    continue;
                }
                for (JavaField field : javaClass.getFields()) {
                    if (isHttpClientType(field.getRawType())) {
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

    private static boolean isHttpClientType(JavaClass type) {
        for (String httpClientType : HTTP_CLIENT_TYPES) {
            if (type.isAssignableTo(httpClientType)) {
                return true;
            }
        }
        return false;
    }
}
