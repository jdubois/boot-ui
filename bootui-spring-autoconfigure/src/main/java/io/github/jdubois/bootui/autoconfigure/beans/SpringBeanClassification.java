package io.github.jdubois.bootui.autoconfigure.beans;

/** Shared inventory classification without linking Actuator's optional descriptor API. */
public final class SpringBeanClassification {
    private SpringBeanClassification() {}

    public static String classify(String type, boolean bootUiType) {
        if (type == null) return "OTHER";
        if (bootUiType) return "BOOTUI";
        if (type.startsWith("org.springframework.")) return "FRAMEWORK";
        if (type.startsWith("java.") || type.startsWith("jakarta.")) return "PLATFORM";
        return "APPLICATION";
    }
}
