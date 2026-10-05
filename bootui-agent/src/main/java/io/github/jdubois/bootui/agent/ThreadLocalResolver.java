package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import io.github.jdubois.bootui.agent.bridge.ThreadLocals;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * The {@code thread-locals} sensor's holder resolution (PLAN-v2 §5.16, M5-5f), on the engine's drain thread only, for
 * thread locals a scope reported: which static field holds one, by identity, in an already-initialized class, without
 * initializing a class, reading any value but the field's, or calling {@code toString()} on anything.
 *
 * <p><b>Where it looks</b>, within the engine's time budget: the exact framework holder classes the engine names (one
 * level deep, into the instance fields of the object a static field holds, for singletons such as SLF4J's MDC adapter
 * and Spring Security's strategy); the classes the thread local's own class or its {@code withInitial} supplier names;
 * then every initialized class of the claimed packages. Static fields are found by parsing class files through the
 * class's loader with ASM, so no field type is loaded, and read through a private lookup on that class, compared with
 * {@code ==}. A class whose package is not open to the agent is skipped.
 *
 * <p><b>Initialized.</b> {@code jdk.internal.misc.Unsafe.shouldBeInitialized} answers, through the export the sensor
 * granted to the agent's own module; a class still initializing reads as not initialized, so a read never waits. Where
 * the export or the method is missing, Code Inventory's executed methods stand in: a class with a method that ran,
 * other than its static initializer, is initialized, unless that method runs inside its static initializer, when the
 * read waits for it to finish; only the claimed packages, whose methods Code Inventory sees, are then searched.
 *
 * <p>Per class, the static fields' names and types are cached in a {@link WeakHashMap} holding strings only, so a class
 * or its loader is never pinned; the index of loaded classes holds weak references and is rebuilt every {@value
 * #INDEX_MILLIS} ms at most.
 */
final class ThreadLocalResolver extends ThreadLocals.Resolver {

    static final long INDEX_MILLIS = 10_000L;

    /** An index older than this is rebuilt once before a thread local is declared unresolved. */
    static final long STALE_INDEX_MILLIS = 1_000L;

    static final long INVENTORY_MILLIS = 2_000L;

    /** The superclasses walked at most for one-level instance fields. */
    static final int MAX_SUPERS = 6;

    private static final String THREAD_LOCAL = "java.lang.ThreadLocal";
    private static final String INHERITABLE = "java.lang.InheritableThreadLocal";

    private final Instrumentation instrumentation;
    private final InitializationCheck check;
    private final MethodHandle supplier;
    private final MethodHandle findLoadedClass = findLoadedClassHandle();
    private final Map<Class<?>, FieldInfo[]> fields = new WeakHashMap<Class<?>, FieldInfo[]>();
    private final Map<Class<?>, Boolean> initialValues = new WeakHashMap<Class<?>, Boolean>();
    private List<WeakReference<Class<?>>> index = new ArrayList<WeakReference<Class<?>>>();
    private long indexedAt = Long.MIN_VALUE / 2;
    private String[] indexedPackages;
    private Set<String> executed = new HashSet<String>();
    private long executedAt = Long.MIN_VALUE / 2;

    ThreadLocalResolver(Instrumentation instrumentation, InitializationCheck check) {
        this.instrumentation = instrumentation;
        this.check = check;
        this.supplier = supplierHandle();
    }

    @Override
    public String initializationCheck() {
        return check.name();
    }

    @Override
    public synchronized String[] resolve(Object threadLocal, String[] packages, String[] holders, long budgetNanos) {
        long deadline = System.nanoTime() + Math.max(1L, budgetNanos);
        String[] claimed = packages == null ? new String[0] : packages;
        refreshIndex(claimed, holders, false);
        String[] answer = search(threadLocal, claimed, holders, deadline);
        if (answer != null && answer[0] == null && System.currentTimeMillis() - indexedAt >= STALE_INDEX_MILLIS) {
            if (System.nanoTime() >= deadline) {
                // Never "not resolved" from a stale index: asked again with time to rebuild it.
                return null;
            }
            // The holder's class may have loaded since the index was built: rebuilt once, then searched again.
            refreshIndex(claimed, holders, true);
            answer = search(threadLocal, claimed, holders, deadline);
        }
        return answer;
    }

    /** One search of the index; {@code null} when out of time. */
    private String[] search(Object threadLocal, String[] claimed, String[] holders, long deadline) {
        boolean initialValue = initialValue(threadLocal);
        String hint = hint(threadLocal);
        // Framework holders first, by exact name, one level deep.
        if (holders != null) {
            for (String name : holders) {
                for (Class<?> type : loaded(name)) {
                    String found = inClass(type, threadLocal, true, claimed);
                    if (found != null) {
                        return answer(found, initialValue, inPackages(type.getName(), claimed), hint);
                    }
                }
            }
        }
        // The classes the thread local itself names, then every claimed class.
        List<Class<?>> candidates = new ArrayList<Class<?>>();
        Class<?> own = threadLocal.getClass();
        for (Class<?> enclosing = own; enclosing != null; enclosing = enclosingOf(enclosing)) {
            if (inPackages(enclosing.getName(), claimed)) {
                candidates.add(enclosing);
            }
        }
        if (hint != null) {
            for (Class<?> type : loaded(hint)) {
                candidates.add(type);
            }
        }
        for (Class<?> type : candidates) {
            String found = inClass(type, threadLocal, false, claimed);
            if (found != null) {
                return answer(found, initialValue, inPackages(type.getName(), claimed), hint);
            }
        }
        for (WeakReference<Class<?>> reference : index) {
            if (System.nanoTime() > deadline) {
                // Out of time: asked again at the engine's next drain, the classes parsed so far cached.
                return null;
            }
            Class<?> type = reference.get();
            if (type == null || !inPackages(type.getName(), claimed)) {
                continue;
            }
            String found = inClass(type, threadLocal, false, claimed);
            if (found != null) {
                return answer(found, initialValue, true, hint);
            }
        }
        return answer(null, initialValue, false, hint);
    }

    private static String[] answer(String holder, boolean initialValue, boolean claimed, String hint) {
        return new String[] {holder, String.valueOf(initialValue), String.valueOf(claimed), hint};
    }

    /** The static field of {@code type} holding {@code threadLocal}, or, one level deep, one of its fields' fields. */
    private String inClass(Class<?> type, Object threadLocal, boolean deep, String[] claimed) {
        if (!check.initialized(type, claimed)) {
            return null;
        }
        Set<String> hierarchy = hierarchy(threadLocal.getClass());
        for (FieldInfo field : fields(type)) {
            if (!field.isStatic) {
                continue;
            }
            Class<?> declared = declaredType(field.type, threadLocal.getClass());
            if (declared != null) {
                Object value = staticValue(type, field.name, declared);
                if (value == threadLocal) {
                    return type.getName() + "." + field.name;
                }
                continue;
            }
            if (!deep || hierarchy.contains(field.type) || primitiveOrArray(field.type)) {
                continue;
            }
            Class<?> singletonType = loadedType(field.type, type.getClassLoader());
            Object singleton = singletonType == null ? null : staticValue(type, field.name, singletonType);
            if (singleton == null) {
                continue;
            }
            String inner = inInstance(singleton, threadLocal);
            if (inner != null) {
                return inner + " (via " + type.getName() + "." + field.name + ")";
            }
        }
        return null;
    }

    /** The instance field of {@code holder}, or of its superclasses, holding {@code threadLocal}. */
    private String inInstance(Object holder, Object threadLocal) {
        Class<?> type = holder.getClass();
        for (int i = 0; type != null && type != Object.class && i < MAX_SUPERS; i++, type = type.getSuperclass()) {
            for (FieldInfo field : fields(type)) {
                if (field.isStatic) {
                    continue;
                }
                Class<?> declared = declaredType(field.type, threadLocal.getClass());
                if (declared == null) {
                    continue;
                }
                try {
                    MethodHandle getter = MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                            .findGetter(type, field.name, declared);
                    if (getter.invoke(holder) == threadLocal) {
                        return type.getName() + "." + field.name;
                    }
                } catch (Throwable notOpen) {
                    // A package not open to the agent, or a field that changed: not this one.
                }
            }
        }
        return null;
    }

    /** A static field's value, through a private lookup on its initialized class; {@code null} when not readable. */
    private static Object staticValue(Class<?> type, String name, Class<?> declared) {
        try {
            MethodHandle getter =
                    MethodHandles.privateLookupIn(type, MethodHandles.lookup()).findStaticGetter(type, name, declared);
            return getter.invoke();
        } catch (Throwable notOpen) {
            return null;
        }
    }

    /**
     * The class a field declared as {@code typeName} must have for its value to be {@code threadLocal}'s: {@code
     * ThreadLocal}, {@code InheritableThreadLocal}, or one of the thread local's own class's superclasses, all already
     * loaded; {@code null} for any other type.
     */
    static Class<?> declaredType(String typeName, Class<?> runtime) {
        if (THREAD_LOCAL.equals(typeName)) {
            return ThreadLocal.class;
        }
        if (INHERITABLE.equals(typeName)) {
            return InheritableThreadLocal.class;
        }
        for (Class<?> type = runtime; type != null && type != Object.class; type = type.getSuperclass()) {
            if (type.getName().equals(typeName)) {
                return type;
            }
        }
        return null;
    }

    private static Set<String> hierarchy(Class<?> runtime) {
        Set<String> names = new HashSet<String>();
        for (Class<?> type = runtime; type != null; type = type.getSuperclass()) {
            names.add(type.getName());
        }
        return names;
    }

    private static boolean primitiveOrArray(String typeName) {
        return typeName == null || typeName.indexOf('.') < 0 || typeName.startsWith("[") || typeName.endsWith("]");
    }

    /**
     * A framework singleton's declared type, only when its holder's loader or one of its parents already loaded it,
     * through {@code ClassLoader.findLoadedClass} on the opened {@code java.lang}: never loaded, never initialized. A
     * type nothing loaded holds no object this thread local could be in.
     */
    private Class<?> loadedType(String typeName, ClassLoader loader) {
        if (findLoadedClass == null) {
            return null;
        }
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            try {
                Class<?> found = (Class<?>) findLoadedClass.invoke(current, typeName);
                if (found != null) {
                    return found;
                }
            } catch (Throwable unavailable) {
                return null;
            }
        }
        return null;
    }

    /** {@code ClassLoader.findLoadedClass(String)}, through the opened {@code java.lang}; {@code null} if absent. */
    private static MethodHandle findLoadedClassHandle() {
        try {
            return MethodHandles.privateLookupIn(ClassLoader.class, MethodHandles.lookup())
                    .findVirtual(
                            ClassLoader.class,
                            "findLoadedClass",
                            java.lang.invoke.MethodType.methodType(Class.class, String.class));
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** The fields {@code type} declares, from its class file, cached. */
    private FieldInfo[] fields(Class<?> type) {
        FieldInfo[] known = fields.get(type);
        if (known != null) {
            return known;
        }
        FieldInfo[] parsed = parse(type);
        fields.put(type, parsed);
        return parsed;
    }

    private static FieldInfo[] parse(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        if (loader == null) {
            return new FieldInfo[0];
        }
        try {
            byte[] bytes = ClassFileLocator.ForClassLoader.of(loader)
                    .locate(type.getName())
                    .resolve();
            final List<FieldInfo> found = new ArrayList<FieldInfo>();
            OpenedClassReader.of(bytes)
                    .accept(
                            new ClassVisitor(OpenedClassReader.ASM_API) {
                                @Override
                                public FieldVisitor visitField(
                                        int access, String name, String descriptor, String signature, Object value) {
                                    if (descriptor.startsWith("L")) {
                                        found.add(new FieldInfo(
                                                name,
                                                descriptor
                                                        .substring(1, descriptor.length() - 1)
                                                        .replace('/', '.'),
                                                (access & Opcodes.ACC_STATIC) != 0));
                                    }
                                    return null;
                                }
                            },
                            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return found.toArray(new FieldInfo[0]);
        } catch (Throwable unreadable) {
            return new FieldInfo[0];
        }
    }

    /** Whether {@code threadLocal} has an initial value: {@code withInitial}, or a subclass overriding initialValue. */
    private boolean initialValue(Object threadLocal) {
        Class<?> type = threadLocal.getClass();
        if ("java.lang.ThreadLocal$SuppliedThreadLocal".equals(type.getName())) {
            return true;
        }
        Boolean known = initialValues.get(type);
        if (known != null) {
            return known.booleanValue();
        }
        boolean overrides = false;
        for (Class<?> current = type;
                current != null && current != ThreadLocal.class && current != InheritableThreadLocal.class;
                current = current.getSuperclass()) {
            if (declaresInitialValue(current)) {
                overrides = true;
                break;
            }
        }
        initialValues.put(type, Boolean.valueOf(overrides));
        return overrides;
    }

    private static boolean declaresInitialValue(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        if (loader == null) {
            return false;
        }
        try {
            byte[] bytes = ClassFileLocator.ForClassLoader.of(loader)
                    .locate(type.getName())
                    .resolve();
            final boolean[] found = new boolean[1];
            OpenedClassReader.of(bytes)
                    .accept(
                            new ClassVisitor(OpenedClassReader.ASM_API) {
                                @Override
                                public MethodVisitor visitMethod(
                                        int access,
                                        String name,
                                        String descriptor,
                                        String signature,
                                        String[] exceptions) {
                                    if ("initialValue".equals(name) && descriptor.startsWith("()")) {
                                        found[0] = true;
                                    }
                                    return null;
                                }
                            },
                            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return found[0];
        } catch (Throwable unreadable) {
            return false;
        }
    }

    /**
     * A name the thread local itself suggests: a {@code withInitial} supplier's host class (a lambda's or a method
     * reference's), or an anonymous subclass's enclosing class; {@code null} for a plain {@code ThreadLocal}.
     */
    String hint(Object threadLocal) {
        Class<?> type = threadLocal.getClass();
        if (type == ThreadLocal.class || type == InheritableThreadLocal.class) {
            return null;
        }
        if ("java.lang.ThreadLocal$SuppliedThreadLocal".equals(type.getName())) {
            if (supplier == null) {
                return null;
            }
            try {
                Object function = supplier.invoke(threadLocal);
                return function == null ? null : host(function.getClass().getName());
            } catch (Throwable unreadable) {
                return null;
            }
        }
        return host(type.getName());
    }

    /** A class's host: a lambda's or hidden class's defining class, an anonymous or member class's outermost class. */
    static String host(String className) {
        String name = className;
        int slash = name.indexOf('/');
        if (slash > 0) {
            name = name.substring(0, slash);
        }
        int dollar = name.indexOf('$');
        return dollar > 0 ? name.substring(0, dollar) : name;
    }

    /**
     * The class named before the last {@code $} of {@code type}'s name, with the same loader, when the index holds it:
     * never {@code getEnclosingClass()}, which resolves the {@code InnerClasses} attribute and may load classes.
     */
    private Class<?> enclosingOf(Class<?> type) {
        String name = type.getName();
        int dollar = name.lastIndexOf('$');
        if (dollar <= 0) {
            return null;
        }
        for (Class<?> outer : loaded(name.substring(0, dollar))) {
            if (outer.getClassLoader() == type.getClassLoader()) {
                return outer;
            }
        }
        return null;
    }

    // ---- the loaded classes ----------------------------------------------------------------------------------------

    private void refreshIndex(String[] packages, String[] holders, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - indexedAt < INDEX_MILLIS && java.util.Arrays.equals(packages, indexedPackages)) {
            return;
        }
        List<WeakReference<Class<?>>> fresh = new ArrayList<WeakReference<Class<?>>>();
        Map<String, List<WeakReference<Class<?>>>> names = new HashMap<String, List<WeakReference<Class<?>>>>();
        Set<String> wanted = new HashSet<String>();
        if (holders != null) {
            for (String holder : holders) {
                wanted.add(holder);
            }
        }
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            String name = type.getName();
            if (inPackages(name, packages)) {
                fresh.add(new WeakReference<Class<?>>(type));
            }
            if (wanted.contains(name) || inPackages(name, packages)) {
                List<WeakReference<Class<?>>> same = names.get(name);
                if (same == null) {
                    same = new ArrayList<WeakReference<Class<?>>>(1);
                    names.put(name, same);
                }
                same.add(new WeakReference<Class<?>>(type));
            }
        }
        List<WeakReference<ClassLoader>> found = new ArrayList<WeakReference<ClassLoader>>();
        for (WeakReference<Class<?>> reference : fresh) {
            Class<?> type = reference.get();
            ClassLoader loader = type == null ? null : type.getClassLoader();
            boolean known = loader == null;
            for (int i = 0; i < found.size() && !known; i++) {
                known = found.get(i).get() == loader;
            }
            if (!known) {
                found.add(new WeakReference<ClassLoader>(loader));
            }
        }
        index = fresh;
        byName = names;
        loaders = found;
        indexedAt = now;
        indexedPackages = packages.clone();
    }

    private Map<String, List<WeakReference<Class<?>>>> byName = new HashMap<String, List<WeakReference<Class<?>>>>();

    /**
     * The loaded classes named {@code name}: from the index, else, for a class loaded since it was built, as asked of
     * the claimed classes' loaders and the system class loader, never loading it.
     */
    private List<Class<?>> loaded(String name) {
        List<Class<?>> classes = new ArrayList<Class<?>>();
        List<WeakReference<Class<?>>> references = byName.get(name);
        if (references != null) {
            for (WeakReference<Class<?>> reference : references) {
                Class<?> type = reference.get();
                if (type != null) {
                    classes.add(type);
                }
            }
        }
        if (classes.isEmpty()) {
            List<ClassLoader> asked = new ArrayList<ClassLoader>();
            for (WeakReference<ClassLoader> reference : loaders) {
                ClassLoader loader = reference.get();
                if (loader != null) {
                    asked.add(loader);
                }
            }
            asked.add(ClassLoader.getSystemClassLoader());
            for (ClassLoader loader : asked) {
                Class<?> type = loadedType(name, loader);
                if (type != null && !classes.contains(type)) {
                    classes.add(type);
                }
            }
        }
        return classes;
    }

    private List<WeakReference<ClassLoader>> loaders = new ArrayList<WeakReference<ClassLoader>>();

    static boolean inPackages(String className, String[] packages) {
        if (packages == null) {
            return false;
        }
        for (String prefix : packages) {
            if (prefix != null
                    && !prefix.isEmpty()
                    && (className.startsWith(prefix.endsWith(".") ? prefix : prefix + ".")
                            || className.equals(prefix))) {
                return true;
            }
        }
        return false;
    }

    /** The classes Code Inventory saw run a method, other than a static initializer, in the current run. */
    synchronized Set<String> executedClasses() {
        long now = System.currentTimeMillis();
        if (now - executedAt < INVENTORY_MILLIS) {
            return executed;
        }
        Set<String> names = new HashSet<String>();
        byte epoch = CodeInventory.epoch;
        byte[] hits = CodeInventory.HITS;
        int count = CodeInventory.methodCount();
        if (epoch != 0) {
            for (int from = 0; from < count; from += 4_096) {
                String[] keys = CodeInventory.methodKeys(from, 4_096);
                for (int i = 0; i < keys.length; i++) {
                    int id = from + i;
                    String key = keys[i];
                    if (key == null || id >= hits.length || hits[id] != epoch) {
                        continue;
                    }
                    int hash = key.indexOf('#');
                    if (hash > 0 && !key.startsWith("<clinit>", hash + 1)) {
                        names.add(key.substring(0, hash));
                    }
                }
            }
        }
        executed = names;
        executedAt = now;
        return names;
    }

    // ---- initialization checks -------------------------------------------------------------------------------------

    /** How the resolver tells a class is initialized, so it never initializes one. */
    abstract static class InitializationCheck {

        abstract String name();

        abstract boolean initialized(Class<?> type, String[] claimed);
    }

    /** {@code jdk.internal.misc.Unsafe.shouldBeInitialized}, through the export to the agent's own module. */
    static final class UnsafeCheck extends InitializationCheck {

        private final Object unsafe;
        private final Method shouldBeInitialized;

        private UnsafeCheck(Object unsafe, Method shouldBeInitialized) {
            this.unsafe = unsafe;
            this.shouldBeInitialized = shouldBeInitialized;
        }

        /** The check, or {@code null} when the class, the method, or the export is missing on this JDK. */
        static UnsafeCheck locate() {
            try {
                Class<?> type = Class.forName("jdk.internal.misc.Unsafe", false, null);
                Object unsafe = type.getMethod("getUnsafe").invoke(null);
                Method should = type.getMethod("shouldBeInitialized", Class.class);
                UnsafeCheck check = new UnsafeCheck(unsafe, should);
                // Proved on a class surely initialized.
                if (!check.initialized(String.class, null)) {
                    return null;
                }
                return check;
            } catch (Throwable unavailable) {
                return null;
            }
        }

        @Override
        String name() {
            return "unsafe";
        }

        @Override
        boolean initialized(Class<?> type, String[] claimed) {
            try {
                return Boolean.FALSE.equals(shouldBeInitialized.invoke(unsafe, type));
            } catch (Throwable ex) {
                return false;
            }
        }
    }

    /** The fallback: Code Inventory saw a method of the class run, so only the claimed packages are searched. */
    static final class InventoryCheck extends InitializationCheck {

        private ThreadLocalResolver resolver;

        void resolver(ThreadLocalResolver owner) {
            this.resolver = owner;
        }

        @Override
        String name() {
            return "inventory";
        }

        @Override
        boolean initialized(Class<?> type, String[] claimed) {
            return resolver != null
                    && inPackages(type.getName(), claimed)
                    && resolver.executedClasses().contains(type.getName());
        }
    }

    // ---- fields ----------------------------------------------------------------------------------------------------

    /** A field's name and declared type, by binary name, and whether it is static: strings only, never a class. */
    static final class FieldInfo {

        final String name;
        final String type;
        final boolean isStatic;

        FieldInfo(String name, String type, boolean isStatic) {
            this.name = name;
            this.type = type;
            this.isStatic = isStatic;
        }
    }

    /** {@code SuppliedThreadLocal.supplier}'s getter, through the opened {@code java.lang}; {@code null} if absent. */
    private static MethodHandle supplierHandle() {
        try {
            Class<?> supplied = Class.forName("java.lang.ThreadLocal$SuppliedThreadLocal", false, null);
            return MethodHandles.privateLookupIn(supplied, MethodHandles.lookup())
                    .findGetter(supplied, "supplier", java.util.function.Supplier.class);
        } catch (Throwable unavailable) {
            return null;
        }
    }
}
