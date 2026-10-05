package io.github.jdubois.bootui.engine.javaagent;

import java.util.Map;

/**
 * One side-effect sensor's state read from the bridge's status at one moment ({@code docs/PLAN-v2.md} §5.16, M5-7b):
 * whether it records, and its loss counters, which are the JVM's since the agent started. Two samples of one run say
 * whether the sensor recorded the whole window between them and lost nothing.
 *
 * @param recording whether the agent installed the sensor and records it for the armed claim
 * @param dropped the sensor's records dropped because the ring was full or not allocated
 * @param lost the ring's records lost, every sensor's
 * @param sightingsFull the operations whose call site was not walked because the generation's table was full, which
 *     changes the origin of a files or environment record
 * @param internOverflow the strings the generation's table could not intern
 */
public record SideEffectsSample(boolean recording, long dropped, long lost, long sightingsFull, long internOverflow) {

    /** A sensor read when nothing could be: not recording. */
    public static final SideEffectsSample NONE = new SideEffectsSample(false, 0L, 0L, 0L, 0L);

    /** Sensor {@code id}'s sample from the bridge status {@code status}; {@link #NONE} when it says nothing. */
    public static SideEffectsSample read(Map<String, Object> status, String id) {
        try {
            if (status == null || status.isEmpty() || id == null) {
                return NONE;
            }
            Map<String, Object> counters = AgentBridgeAccess.map(status, id);
            Map<String, Object> sensor = JavaAgentService.sensor(AgentBridgeAccess.map(status, "agent"), id);
            String state = sensor == null ? null : AgentBridgeAccess.text(sensor, "state");
            boolean recording = JavaAgentService.INSTALLED.equals(state)
                    && AgentBridgeAccess.flag(counters, "active")
                    && AgentBridgeAccess.text(counters, "disabledReason") == null
                    && !AgentBridgeAccess.flag(counters, "off");
            return new SideEffectsSample(
                    recording,
                    value(counters, "dropped"),
                    value(counters, "lost"),
                    value(counters, "sightingsFull"),
                    value(counters, "internOverflow"));
        } catch (RuntimeException ex) {
            return NONE;
        }
    }

    private static long value(Map<String, Object> counters, String key) {
        Object value = counters == null ? null : counters.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
