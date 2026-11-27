package io.github.jdubois.bootui.autoconfigure.security;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.util.ClassUtils;

/** Private native metadata only; no permission to invoke application callbacks. */
public final class SpringWebSocketOriginHints {
    private SpringWebSocketOriginHints() {}

    public static void register(RuntimeHints hints, ClassLoader classLoader) {
        for (String name : SpringWebSocketOriginCollector.FIELD_TYPES) {
            if (ClassUtils.isPresent(name, classLoader)) {
                hints.reflection()
                        .registerType(
                                ClassUtils.resolveClassName(name, classLoader), MemberCategory.ACCESS_DECLARED_FIELDS);
            }
        }
    }
}
