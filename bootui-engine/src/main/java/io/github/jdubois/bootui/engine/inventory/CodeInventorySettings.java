package io.github.jdubois.bootui.engine.inventory;

import java.time.Duration;

/**
 * Code Inventory's settings ({@code bootui.code-inventory.*}).
 *
 * @param maxClasses the most application classes the disk scan hashes, {@code bootui.code-inventory.max-classes}
 * @param scanTimeout the disk scan's deadline, {@code bootui.code-inventory.scan-timeout}
 */
public record CodeInventorySettings(int maxClasses, Duration scanTimeout) {

    /** The default {@code bootui.code-inventory.max-classes}. */
    public static final int DEFAULT_MAX_CLASSES = 20_000;

    /** The default {@code bootui.code-inventory.scan-timeout}. */
    public static final Duration DEFAULT_SCAN_TIMEOUT = Duration.ofSeconds(30);

    public CodeInventorySettings {
        maxClasses = maxClasses <= 0 ? DEFAULT_MAX_CLASSES : maxClasses;
        scanTimeout = scanTimeout == null || scanTimeout.isNegative() || scanTimeout.isZero()
                ? DEFAULT_SCAN_TIMEOUT
                : scanTimeout;
    }

    /** The defaults. */
    public static CodeInventorySettings defaults() {
        return new CodeInventorySettings(DEFAULT_MAX_CLASSES, DEFAULT_SCAN_TIMEOUT);
    }
}
