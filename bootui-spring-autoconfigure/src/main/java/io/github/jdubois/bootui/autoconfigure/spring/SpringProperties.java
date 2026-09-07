package io.github.jdubois.bootui.autoconfigure.spring;

import io.github.jdubois.bootui.autoconfigure.config.BootUiActuatorDefaultsEnvironmentPostProcessor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/** Bounded binding, including a host-only view without mutating the application's environment. */
final class SpringProperties {
    static final int MAX_NAMES = 10_000;
    static final int MAX_SOURCES = 100;
    static final int MAX_TEXT = 4096;

    private SpringProperties() {}

    static <T> T bind(Environment environment, boolean host, String key, Bindable<T> type) {
        try {
            if (!(environment instanceof ConfigurableEnvironment configurable)) {
                throw new IllegalStateException("Unsupported property sources");
            }
            List<PropertySource<?>> sources = sources(configurable, host);
            T value = new Binder(
                            ConfigurationPropertySources.from(sources),
                            new PropertySourcesPlaceholdersResolver(sources))
                    .bind(key, type)
                    .orElse(null);
            if (value instanceof String text && (text.length() > MAX_TEXT || text.contains("${"))) {
                throw new IllegalStateException("Unresolved or oversized property");
            }
            return value;
        } catch (RuntimeException | LinkageError ex) {
            throw new InspectionFailure("Configuration could not be bound within inspection limits.");
        }
    }

    static boolean present(Environment environment, String key) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            throw new InspectionFailure("Property inspection is unavailable.");
        }
        var canonical = ConfigurationPropertyName.of(key);
        for (var source : ConfigurationPropertySources.from(sources(configurable, false))) {
            if (source.getConfigurationProperty(canonical) != null) return true;
        }
        String compact = key.replace("-", "");
        return names(environment).stream()
                .map(name -> name.replace("-", ""))
                .anyMatch(name -> name.startsWith(compact + "[") || name.startsWith(compact + "."));
    }

    static Set<String> names(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            throw new InspectionFailure("Property-name inspection is unavailable.");
        }
        Set<String> names = new LinkedHashSet<>();
        for (PropertySource<?> source : sources(configurable, false)) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    // Relaxed/indexed names are compared canonically, never displayed.
                    names.add(ConfigurationPropertyName.adapt(name.replace('_', '.'), '.')
                            .toString());
                }
            }
        }
        return Set.copyOf(names);
    }

    private static List<PropertySource<?>> sources(ConfigurableEnvironment environment, boolean host) {
        List<PropertySource<?>> result = new ArrayList<>();
        int names = 0;
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)) {
                continue;
            }
            if (result.size() >= MAX_SOURCES) {
                throw new InspectionFailure("Property-source inspection limit reached.");
            }
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                String[] keys = enumerable.getPropertyNames();
                names += keys.length;
                if (names > MAX_NAMES) {
                    throw new InspectionFailure("Property-name inspection limit reached.");
                }
                for (String key : keys) {
                    if (key.length() > MAX_TEXT) {
                        throw new InspectionFailure("Property-name inspection limit reached.");
                    }
                }
                if (source instanceof org.springframework.core.env.SystemEnvironmentPropertySource) {
                    PropertySource<?> delegate = source;
                    MapView values = new MapView(delegate, keys, host);
                    result.add(
                            new org.springframework.core.env.SystemEnvironmentPropertySource(source.getName(), values) {
                                @Override
                                public String[] getPropertyNames() {
                                    return keys.clone();
                                }

                                @Override
                                public Object getProperty(String name) {
                                    return read(delegate, name, host);
                                }
                            });
                } else {
                    result.add(new EnumerablePropertySource<PropertySource<?>>(source.getName(), source) {
                        @Override
                        public String[] getPropertyNames() {
                            return keys.clone();
                        }

                        @Override
                        public Object getProperty(String name) {
                            return read(source, name, host);
                        }
                    });
                }
            } else {
                result.add(new PropertySource<PropertySource<?>>(source.getName(), source) {
                    @Override
                    public Object getProperty(String name) {
                        return read(source, name, host);
                    }
                });
            }
        }
        return result;
    }

    private static Object read(PropertySource<?> source, String key, boolean host) {
        Object value = source.getProperty(key);
        if (value instanceof Enum<?> enumeration) value = enumeration.name();
        checkValue(value, 0, new int[] {0});
        if (value instanceof String text) {
            if (text.length() > MAX_TEXT) {
                throw new InspectionFailure("Property value exceeds inspection limit.");
            }

            if (host
                    && "defaultProperties".equals(source.getName())
                    && BootUiActuatorDefaultsEnvironmentPostProcessor.isBootUiActuatorDefault(key, text.trim())) {
                return null;
            }
        }
        if (value instanceof java.util.Collection<?> collection && collection.size() > 100) {
            throw new InspectionFailure("Property collection exceeds inspection limit.");
        }
        if (value != null && value.getClass().isArray() && java.lang.reflect.Array.getLength(value) > 100)
            throw new InspectionFailure("Property collection exceeds inspection limit.");
        return value;
    }

    private static void checkValue(Object value, int depth, int[] visited) {
        if (++visited[0] > 1000 || depth > 8)
            throw new InspectionFailure("Property structure exceeds inspection limit.");
        if (value == null) return;
        if (value instanceof String text) {
            if (text.length() > MAX_TEXT) throw new InspectionFailure("Property text exceeds inspection limit.");
            return;
        }
        if (value instanceof java.util.Collection<?> collection) {
            if (collection.size() > 100) throw new InspectionFailure("Property collection exceeds inspection limit.");
            for (Object item : collection) checkValue(item, depth + 1, visited);
            return;
        }
        if (value instanceof java.util.Map<?, ?> map) {
            if (map.size() > 100) throw new InspectionFailure("Property map exceeds inspection limit.");
            for (var entry : map.entrySet()) {
                checkValue(entry.getKey(), depth + 1, visited);
                checkValue(entry.getValue(), depth + 1, visited);
            }
            return;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            if (length > 100) throw new InspectionFailure("Property array exceeds inspection limit.");
            for (int i = 0; i < length; i++) checkValue(java.lang.reflect.Array.get(value, i), depth + 1, visited);
            return;
        }
        // Do not ask Binder to turn arbitrary application objects into text via toString().
        if (!Set.of(
                        Boolean.class,
                        Byte.class,
                        Short.class,
                        Integer.class,
                        Long.class,
                        Float.class,
                        Double.class,
                        Character.class,
                        java.time.Duration.class,
                        org.springframework.util.unit.DataSize.class)
                .contains(value.getClass())) throw new InspectionFailure("Unsupported property value type.");
    }

    /** Boot's environment mapper deliberately reads getSource().get(), bypassing getProperty(). */
    private static final class MapView extends java.util.AbstractMap<String, Object> {
        private final PropertySource<?> delegate;
        private final String[] keys;
        private final boolean host;

        MapView(PropertySource<?> delegate, String[] keys, boolean host) {
            this.delegate = delegate;
            this.keys = keys;
            this.host = host;
        }

        @Override
        public Object get(Object key) {
            return key instanceof String text ? read(delegate, text, host) : null;
        }

        @Override
        public int size() {
            return keys.length;
        }

        @Override
        public Set<String> keySet() {
            return Set.of(keys);
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            Set<Entry<String, Object>> entries = new LinkedHashSet<>();
            for (String key : keys) entries.add(new SimpleImmutableEntry<>(key, get(key)));
            return entries;
        }
    }

    static final class InspectionFailure extends RuntimeException {
        InspectionFailure(String safeMessage) {
            super(safeMessage);
        }
    }
}
