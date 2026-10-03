package io.github.jdubois.bootui.agent;

/**
 * The inventory sensor's self-test probe: the one class outside the claimed packages its transformer instruments. Named
 * by string in the sensor, so it loads, and is transformed, only when the self-test calls it.
 */
final class InventoryProbe {

    private InventoryProbe() {}

    static int ping(int value) {
        return value + 1;
    }
}
