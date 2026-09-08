package io.github.jdubois.bootui.autoconfigure.security;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** Passive, bounded security configuration evidence; opaque higher-priority data blocks fallback. */
final class SecurityEnvironmentSnapshot extends StandardEnvironment {
    private static final Set<String> ENVIRONMENTS = Set.of(
            "org.springframework.core.env.StandardEnvironment",
            "org.springframework.web.context.support.StandardServletEnvironment",
            "org.springframework.mock.env.MockEnvironment",
            "org.springframework.boot.ApplicationEnvironment",
            "org.springframework.boot.web.context.reactive.StandardReactiveWebEnvironment",
            "org.springframework.boot.web.context.servlet.ApplicationServletEnvironment",
            "org.springframework.boot.web.server.reactive.context.ApplicationReactiveWebEnvironment");
    private static final Set<String> SOURCES = Set.of(
            "org.springframework.core.env.MapPropertySource",
            "org.springframework.core.env.PropertiesPropertySource",
            "org.springframework.core.env.SystemEnvironmentPropertySource",
            "org.springframework.mock.env.MockPropertySource",
            "org.springframework.boot.env.OriginTrackedMapPropertySource",
            "org.springframework.boot.env.DefaultPropertiesPropertySource",
            "org.springframework.boot.ApplicationInfoPropertySource",
            "org.springframework.boot.support.SystemEnvironmentPropertySourceEnvironmentPostProcessor$OriginAwareSystemEnvironmentPropertySource",
            "io.github.jdubois.bootui.autoconfigure.config.BootUiOverridesPropertySource");
    private static final Set<String> MAPS = Set.of(
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.Properties",
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.ImmutableCollections$Map1",
            "java.util.ImmutableCollections$MapN",
            "java.util.Collections$EmptyMap",
            "java.util.Collections$SingletonMap");
    private static final Set<String> LISTS = Set.of(
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.Arrays$ArrayList",
            "java.util.ImmutableCollections$List12",
            "java.util.ImmutableCollections$ListN",
            "java.util.Collections$EmptyList",
            "java.util.Collections$SingletonList");
    private static final Object UNKNOWN = new Object();
    private int entries;
    private final Set<String> reservedNames = new java.util.HashSet<>();

    @Override
    protected void customizePropertySources(MutablePropertySources sources) {}

    @Override
    public String getProperty(String key) {
        for (PropertySource<?> source : getPropertySources()) {
            Object value = source.getProperty(key);
            if (value == null) continue;
            if (value instanceof String text) return supportedText(text);
            if (value instanceof String[] array) return supportedText(String.join(",", array));
            if (value instanceof List<?> list) {
                return supportedText(
                        String.join(",", list.stream().map(String.class::cast).toList()));
            }
            throw new SecurityActuatorObservation.ObservationLimitException();
        }
        return null;
    }

    static String supportedText(String text) {
        if (text != null && (text.length() > 16384 || text.contains("${"))) {
            throw new SecurityActuatorObservation.ObservationLimitException();
        }
        return text;
    }

    static Environment capture(Environment environment) {
        if (environment instanceof SecurityEnvironmentSnapshot) return environment;
        SecurityEnvironmentSnapshot snapshot = new SecurityEnvironmentSnapshot();
        if (environment == null || !ENVIRONMENTS.contains(environment.getClass().getName())) {
            snapshot.barrier("unsupported environment", null);
            return snapshot;
        }
        Object sources = field(environment, AbstractEnvironment.class, "propertySources");
        Object list = field(sources, MutablePropertySources.class, "propertySourceList");
        if (!(list instanceof java.util.concurrent.CopyOnWriteArrayList<?> nativeSources)
                || list.getClass() != java.util.concurrent.CopyOnWriteArrayList.class) {
            snapshot.barrier("unsupported source inventory", null);
            return snapshot;
        }
        for (int index = 0; index < Math.min(nativeSources.size(), 128); index++) {
            Object name = field(nativeSources.get(index), PropertySource.class, "name");
            if (name instanceof String text) snapshot.reservedNames.add(text);
        }
        int count = 0;
        for (Object candidate : nativeSources) {
            if (++count > 128) {
                snapshot.barrier("source inventory limit", null);
                break;
            }
            if (!(candidate instanceof PropertySource<?> source)) {
                snapshot.barrier("unsupported source", null);
                break;
            }
            try {
                snapshot.copy(source, count);
            } catch (RuntimeException | LinkageError ex) {
                snapshot.barrier("unreadable source " + count, null);
            }
        }
        Object profiles = field(environment, AbstractEnvironment.class, "activeProfiles");
        if (profiles instanceof java.util.LinkedHashSet<?> names
                && names.getClass() == java.util.LinkedHashSet.class
                && names.size() <= 128
                && names.stream().allMatch(String.class::isInstance)) {
            snapshot.setActiveProfiles(names.toArray(String[]::new));
        }
        return snapshot;
    }

    private void copy(PropertySource<?> source, int index) {
        String type = source.getClass().getName();
        if (type.equals("org.springframework.boot.context.properties.source.ConfigurationPropertySourcesPropertySource")
                || type.equals("org.springframework.core.env.PropertySource$StubPropertySource")) return;
        Object rawName = field(source, PropertySource.class, "name");
        String name = rawName instanceof String text ? text : "source " + index;
        if (type.equals("org.springframework.boot.env.RandomValuePropertySource")) {
            getPropertySources().addLast(new Barrier(name, null, "random."));
            return;
        }
        if (type.equals(
                "org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration$LocalManagementPortPropertySource")) {
            barrier(name, Set.of("local.management.port"));
            return;
        }
        if (type.equals(
                "org.springframework.boot.micrometer.tracing.autoconfigure.LogCorrelationEnvironmentPostProcessor$LogCorrelationPropertySource")) {
            barrier(name, Set.of("logging.expect-correlation-id"));
            return;
        }
        Object raw = field(source, PropertySource.class, "source");
        boolean systemEnvironment = type.equals("org.springframework.core.env.SystemEnvironmentPropertySource")
                || type.equals(
                        "org.springframework.boot.support.SystemEnvironmentPropertySourceEnvironmentPostProcessor$OriginAwareSystemEnvironmentPropertySource");
        if (systemEnvironment
                && !type.equals("org.springframework.core.env.SystemEnvironmentPropertySource")
                && field(source, source.getClass(), "prefix") != null) {
            barrier(name, null);
            return;
        }
        if (type.equals("org.springframework.core.env.SimpleCommandLinePropertySource")) {
            copyCommandLine(source, raw, name);
            return;
        }
        if (type.equals("org.springframework.web.context.support.ServletContextPropertySource")
                || type.equals("org.springframework.web.context.support.ServletConfigPropertySource")) {
            if (raw != null
                    && Set.of(
                                    "org.springframework.mock.web.MockServletContext",
                                    "org.springframework.mock.web.MockServletConfig")
                            .contains(raw.getClass().getName())) {
                raw = field(raw, raw.getClass(), "initParameters");
            } else if (type.equals("org.springframework.web.context.support.ServletContextPropertySource")
                    && raw != null
                    && raw.getClass().getName().equals("org.apache.catalina.core.ApplicationContextFacade")) {
                // Tomcat's standard facade delegates init parameters to this native map. Do not invoke
                // ServletContext callbacks, or trust a subclass/custom backing map.
                Object context = field(raw, raw.getClass(), "context");
                if (context == null
                        || !context.getClass().getName().equals("org.apache.catalina.core.ApplicationContext")) {
                    barrier(name, null);
                    return;
                }
                barrier(
                        name + " container aliases",
                        Set.of("org.apache.jasper.XML_VALIDATE_TLD", "org.apache.jasper.XML_BLOCK_EXTERNAL"));
                raw = field(context, context.getClass(), "parameters");
            } else {
                barrier(name, null);
                return;
            }
        } else if (!SOURCES.contains(type)) {
            barrier(name, null);
            return;
        }
        if (raw != System.getenv()) raw = unwrapMap(raw);
        if (!(raw instanceof Map<?, ?> map)
                || !(MAPS.contains(map.getClass().getName()) || map == System.getenv())
                || map.size() > 5000 - entries) {
            barrier(name, null);
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Set<String> unknown = new java.util.LinkedHashSet<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (++entries > 5000) {
                barrier(name, null);
                return;
            }
            if (!(entry.getKey() instanceof String key) || key.length() > 4096) {
                barrier(name, null);
                return;
            }
            Object value = value(entry.getValue());
            if (value == UNKNOWN) unknown.add(key);
            else if (value != null) values.put(key, value);
        }
        if (!unknown.isEmpty()) barrier(name + " unsupported values", unknown);
        PropertySource<?> copied = type.equals("org.springframework.boot.env.OriginTrackedMapPropertySource")
                ? new OriginTrackedMapPropertySource(name, values)
                : systemEnvironment
                        ? new SystemEnvironmentPropertySource(name, values)
                        : new MapPropertySource(name, values);
        getPropertySources().addLast(copied);
    }

    private void copyCommandLine(PropertySource<?> source, Object raw, String name) {
        if (raw == null || !raw.getClass().getName().equals("org.springframework.core.env.CommandLineArgs")) {
            barrier(name, null);
            return;
        }
        Object options = field(raw, raw.getClass(), "optionArgs");
        Object nonOptions = value(field(raw, raw.getClass(), "nonOptionArgs"));
        Object nonOptionName = field(source, source.getClass().getSuperclass(), "nonOptionArgsPropertyName");
        if (!(options instanceof Map<?, ?> map)
                || !MAPS.contains(map.getClass().getName())
                || map.size() > 5000 - entries
                || !(nonOptions instanceof List<?>)
                || !(nonOptionName instanceof String key)) {
            barrier(name, null);
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String option)
                    || option.length() > 4096
                    || !(value(entry.getValue()) instanceof List<?> list)) {
                barrier(name, null);
                return;
            }
            values.put(
                    option,
                    String.join(",", list.stream().map(String.class::cast).toList()));
        }
        if (!((List<?>) nonOptions).isEmpty()) {
            values.put(
                    key,
                    String.join(
                            ",",
                            ((List<?>) nonOptions)
                                    .stream().map(String.class::cast).toList()));
        }
        entries += map.size();
        getPropertySources().addLast(new MapPropertySource(name, values));
    }

    private static Object unwrapMap(Object raw) {
        for (int depth = 0;
                depth < 8 && raw != null && raw.getClass().getName().equals("java.util.Collections$UnmodifiableMap");
                depth++) {
            raw = JdkMapDelegate.read(raw);
        }
        return raw;
    }

    // Boot's config loader uses this JDK wrapper. Inspect its backing map before invoking any map method:
    // trusting the wrapper alone would execute callbacks from an application-supplied backing map.
    private static final class JdkMapDelegate {
        static Object read(Object wrapper) {
            try {
                Field delegate = wrapper.getClass().getDeclaredField("m");
                if (delegate.getType() != Map.class || Modifier.isStatic(delegate.getModifiers())) return null;
                if (delegate.trySetAccessible()) return delegate.get(wrapper);
                Class<?> unsafeType = Class.forName("sun.misc.Unsafe", false, ClassLoader.getPlatformClassLoader());
                Field singleton = unsafeType.getDeclaredField("theUnsafe");
                if (!singleton.trySetAccessible()) return null;
                Object unsafe = singleton.get(null);
                long offset = (Long)
                        unsafeType.getMethod("objectFieldOffset", Field.class).invoke(unsafe, delegate);
                return unsafeType
                        .getMethod("getObject", Object.class, long.class)
                        .invoke(unsafe, wrapper, offset);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                return null;
            }
        }
    }

    static boolean generatedValues(PropertySource<?> source) {
        return source instanceof Barrier barrier && "random.".equals(barrier.prefix);
    }

    static boolean incompleteInventory(PropertySource<?> source, String prefix) {
        if (!(source instanceof Barrier barrier)) return false;
        if (barrier.prefix != null) return prefix.startsWith(barrier.prefix) || barrier.prefix.startsWith(prefix);
        return barrier.keys == null || barrier.keys.stream().anyMatch(key -> key.startsWith(prefix));
    }

    static Set<String> incompleteKeys(PropertySource<?> source) {
        return source instanceof Barrier barrier ? barrier.keys : Set.of();
    }

    private static Object value(Object raw) {
        if (raw == null) return null;
        String type = raw.getClass().getName();
        if (type.equals("org.springframework.boot.origin.OriginTrackedValue")
                || type.equals("org.springframework.boot.origin.OriginTrackedValue$OriginTrackedCharSequence")) {
            try {
                raw = field(raw, Class.forName("org.springframework.boot.origin.OriginTrackedValue"), "value");
                if (raw == null) return UNKNOWN;
            } catch (ClassNotFoundException ex) {
                return UNKNOWN;
            }
        }
        if (raw == null) return null;
        if (raw instanceof String text) return text.length() <= 16384 ? text : UNKNOWN;
        if (Set.of(Boolean.class, Integer.class, Long.class, Short.class, Byte.class, Double.class, Float.class)
                .contains(raw.getClass())) return raw.toString();
        if (raw instanceof String[] array && array.length <= 256) {
            for (String element : array) if (element == null || element.length() > 16384) return UNKNOWN;
            return array.clone();
        }
        if (raw instanceof List<?> list && LISTS.contains(raw.getClass().getName()) && list.size() <= 256) {
            List<String> copied = new ArrayList<>();
            for (Object element : list) {
                if (!(element instanceof String text) || text.length() > 16384) return UNKNOWN;
                copied.add(text);
            }
            return List.copyOf(copied);
        }
        return UNKNOWN;
    }

    private void barrier(String name, Set<String> keys) {
        while (!reservedNames.add(name)) name += " (incomplete)";
        getPropertySources().addLast(new Barrier(name, keys, null));
    }

    private static Object field(Object target, Class<?> declaringClass, String name) {
        if (target == null || !declaringClass.isInstance(target)) return null;
        try {
            Field field = declaringClass.getDeclaredField(name);
            return field.trySetAccessible() ? field.get(target) : null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    private static final class Barrier extends PropertySource<Object> {
        private final Set<String> keys;
        private final String prefix;

        private Barrier(String name, Set<String> keys, String prefix) {
            super(name, UNKNOWN);
            this.keys = keys == null ? null : Set.copyOf(keys);
            this.prefix = prefix;
        }

        @Override
        public Object getProperty(String name) {
            if (prefix != null && !name.startsWith(prefix)
                    || keys != null
                            && !keys.contains(name)
                            && !keys.contains(
                                    name.replace('.', '_').replace('-', '_').toUpperCase(java.util.Locale.ROOT)))
                return null;
            throw new SecurityActuatorObservation.ObservationLimitException();
        }
    }
}
