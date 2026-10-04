package bootuidynamicapp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Function;

/**
 * The application of the dynamic-access tests, loaded by a class loader whose parent is the platform loader, so
 * {@code Class.forName(String)} called here finds its own {@link Hidden}, not the test loader's copy, only if the advice
 * kept this class as the caller. The harness calls it through {@link Function}, never through reflection.
 */
public final class Isolated implements Function<String, Object> {

    private static final String HIDDEN = "bootuidynamicapp.Hidden";
    private static final String[] SPREAD = {
        "java.lang.String", "java.lang.Integer", "java.lang.Long", "java.util.ArrayList", "java.util.HashMap"
    };

    public static volatile int sink;

    private final InvocationHandler handler = (proxy, method, args) -> null;

    @Override
    public Object apply(String op) {
        try {
            if (op.startsWith("call:")) {
                return Hidden.class.getMethod("greet", String.class).invoke(new Hidden(), op.substring(5));
            }
            if (op.startsWith("bench:")) {
                String[] parts = op.split(":");
                return Long.valueOf(bench(parts[1], Integer.parseInt(parts[2])));
            }
            switch (op) {
                case "direct":
                    return Class.forName(HIDDEN);
                case "loader":
                    return Class.forName(HIDDEN, false, Isolated.class.getClassLoader());
                case "module":
                    return Class.forName(Object.class.getModule(), "java.lang.String");
                case "reflective-only":
                    return Class.class
                            .getMethod("forName", String.class)
                            .invoke(null, "bootuidynamicapp.OnlyReflective");
                case "reflective":
                    return Class.class.getMethod("forName", String.class).invoke(null, HIDDEN);
                case "construct":
                    return Hidden.class.getDeclaredConstructor().newInstance();
                case "proxy":
                    return Proxy.newProxyInstance(
                            Isolated.class.getClassLoader(),
                            new Class<?>[] {Runnable.class, Hidden.Marker.class},
                            handler);
                case "missing":
                    try {
                        Class.forName("bootuidynamicapp.secret.NotThere");
                        return "found";
                    } catch (ClassNotFoundException expected) {
                        return "missing";
                    }
                case "library":
                    return bootuidynamiclib.Reflector.instantiate(HIDDEN, Isolated.class.getClassLoader());
                case "marker":
                    return Class.forName("bootuidynamicapp.Hidden$Marker");
                case "spread":
                    for (String name : SPREAD) {
                        Class.forName(name);
                    }
                    return "spread";
                case "pollute":
                    // Several reflective call targets, as any application has: JDK 17 inflates each Method into its own
                    // generated accessor, so the JDK's shared accessor call site sees many types.
                    Hidden target = new Hidden();
                    for (String name : new String[] {"hashCode", "toString", "getClass", "run"}) {
                        Method method = Hidden.class.getMethod(name);
                        for (int i = 0; i < 40; i++) {
                            method.invoke(target);
                        }
                    }
                    for (int i = 0; i < 40; i++) {
                        Hidden.class.getMethod("greet", String.class).invoke(target, "x");
                        Object.class.getConstructor().newInstance();
                        Hidden.class.getConstructor().newInstance();
                    }
                    return "polluted";
                case "deserialize":
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                        out.writeObject(new Hidden());
                    }
                    try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                        return in.readObject();
                    }
                default:
                    throw new IllegalArgumentException(op);
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** {@code n} calls of one operation from this class, returning the elapsed nanoseconds. */
    private long bench(String op, int n) throws Exception {
        Hidden hidden = new Hidden();
        Method run = Hidden.class.getMethod("run");
        Constructor<Hidden> constructor = Hidden.class.getConstructor();
        ClassLoader loader = Isolated.class.getClassLoader();
        Class<?>[] interfaces = {Runnable.class};
        int acc = 0;
        long started = System.nanoTime();
        switch (op) {
            case "forName":
                for (int i = 0; i < n; i++) {
                    acc += Class.forName(HIDDEN).hashCode();
                }
                break;
            case "invoke":
                for (int i = 0; i < n; i++) {
                    run.invoke(hidden);
                    acc++;
                }
                break;
            case "newInstance":
                for (int i = 0; i < n; i++) {
                    acc += constructor.newInstance().hashCode();
                }
                break;
            case "library":
                acc += bootuidynamiclib.Reflector.lookups(HIDDEN, loader, n);
                break;
            case "proxy":
                for (int i = 0; i < n; i++) {
                    acc += Proxy.newProxyInstance(loader, interfaces, handler)
                            .getClass()
                            .hashCode();
                }
                break;
            default:
                throw new IllegalArgumentException(op);
        }
        long elapsed = System.nanoTime() - started;
        sink += acc;
        return elapsed;
    }
}
