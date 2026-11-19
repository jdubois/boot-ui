package io.github.jdubois.bootui.autoconfigure.config;

import java.nio.file.Path;
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

    private final Path file;
    private final int protectedKeys;
    private final boolean unreadable;

    public BootUiOverridesPropertySource(Map<String, Object> source) {
        this(source, null, 0, false);
    }

    BootUiOverridesPropertySource(Map<String, Object> source, Path file, int protectedKeys, boolean unreadable) {
        super(NAME, source);
        this.file = file;
        this.protectedKeys = protectedKeys;
        this.unreadable = unreadable;
    }

    public BootUiOverridesPropertySource() {
        this(new LinkedHashMap<>());
    }

    public Map<String, Object> mutableSource() {
        return getSource();
    }

    Path file() {
        return file;
    }

    int protectedKeys() {
        return protectedKeys;
    }

    boolean unreadable() {
        return unreadable;
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
