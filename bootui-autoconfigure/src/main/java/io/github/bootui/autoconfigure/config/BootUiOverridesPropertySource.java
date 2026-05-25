package io.github.bootui.autoconfigure.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.MapPropertySource;

/**
 * Mutable Spring property source that holds BootUI runtime overrides.
 *
 * <p>Registered at the highest precedence in the {@link org.springframework.core.env.Environment}
 * so that runtime overrides win over {@code application.properties}, profile files,
 * system properties, and environment variables.</p>
 */
public class BootUiOverridesPropertySource extends MapPropertySource {

    public static final String NAME = "bootui-overrides";

    public BootUiOverridesPropertySource(Map<String, Object> source) {
        super(NAME, source);
    }

    public BootUiOverridesPropertySource() {
        super(NAME, new LinkedHashMap<>());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> mutableSource() {
        return (Map<String, Object>) getSource();
    }

    public void put(String name, Object value) {
        mutableSource().put(name, value);
    }

    public Object remove(String name) {
        return mutableSource().remove(name);
    }

    public void clear() {
        mutableSource().clear();
    }
}
