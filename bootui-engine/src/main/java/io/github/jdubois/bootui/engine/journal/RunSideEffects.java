package io.github.jdubois.bootui.engine.journal;

import java.util.List;
import java.util.Objects;

/**
 * What one run did outside the JVM, as the BootUI agent's Side Effects sensors saw it ({@code docs/PLAN-v2.md} §5.8,
 * §5.16, M5-7b, D45): the side-effect keys of its routes, executions, and startup, and whether each sensor recorded the
 * whole run, so a comparison reports a key as new or gone only when both runs could have seen it. Kept in the run
 * summary, so in the run history and the opt-in baseline file: names and normalized, masked patterns only, never a
 * value, an argument, or a file's contents.
 *
 * @param unavailableReason why the run kept no keys, such as the Side Effects panel being hidden when it ended, or
 *     {@code null}
 * @param routesHidden whether HTTP Exchanges was hidden when the run ended, so its route owners are one hidden label
 * @param sensors the compared sensors, in catalog order
 * @param keys the keys, at most {@value #MAX_KEYS_PER_SENSOR} per sensor, most frequent first
 * @param pendingOwners owners whose buffered work may still add keys, names only, bounded and masked
 * @param absenceUnknownReason why the pending-owner cut could not qualify absence, or {@code null}
 */
public record RunSideEffects(
        String unavailableReason,
        boolean routesHidden,
        List<Sensor> sensors,
        List<Key> keys,
        List<PendingOwner> pendingOwners,
        String absenceUnknownReason) {

    /** The keys kept per sensor. */
    public static final int MAX_KEYS_PER_SENSOR = 250;

    public static final int MAX_PENDING_OWNERS = 256;
    public static final int MAX_OWNER_LENGTH = 1024;
    public static final String UNKNOWN_OWNERSHIP = "pending ownership completeness was not recorded";

    /** Earlier producers supplied no pending-owner evidence, not a verified empty set. */
    public RunSideEffects(String unavailableReason, boolean routesHidden, List<Sensor> sensors, List<Key> keys) {
        this(unavailableReason, routesHidden, sensors, keys, List.of(), UNKNOWN_OWNERSHIP);
    }

    public RunSideEffects {
        sensors = sensors == null ? List.of() : List.copyOf(sensors);
        keys = keys == null ? List.of() : List.copyOf(keys);
        if (pendingOwners == null) {
            pendingOwners = List.of();
            absenceUnknownReason = UNKNOWN_OWNERSHIP;
        } else if (pendingOwners.size() > MAX_PENDING_OWNERS
                || pendingOwners.stream()
                        .anyMatch(owner -> owner.owner().length() > MAX_OWNER_LENGTH
                                || (!"route".equals(owner.scope())
                                        && !"execution".equals(owner.scope())
                                        && !"startup".equals(owner.scope())))) {
            pendingOwners = List.of();
            absenceUnknownReason = "pending ownership exceeded its metadata bound";
        } else {
            pendingOwners = List.copyOf(pendingOwners);
        }
    }

    /** Why a missing key of this owner cannot establish absence, or {@code null}. */
    public String absenceReason(String scope, String owner) {
        if (absenceUnknownReason != null) {
            return absenceUnknownReason;
        }
        return pendingOwners.contains(new PendingOwner(scope, owner)) ? "its owner's work is still pending" : null;
    }

    /** Keeps positive keys while refusing absence when the read or metadata could not be qualified. */
    public RunSideEffects unknownAbsence(String reason) {
        return new RunSideEffects(unavailableReason, routesHidden, sensors, keys, pendingOwners, reason);
    }

    /** Names only: raw correlation ids never travel in a summary or baseline. */
    public record PendingOwner(String scope, String owner) {
        public PendingOwner {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(owner, "owner");
        }
    }

    /** A run that kept no keys, for {@code reason}. */
    public static RunSideEffects unavailable(String reason) {
        return new RunSideEffects(Objects.requireNonNull(reason, "reason"), false, List.of(), List.of());
    }

    /** The sensor {@code id}, or {@code null} when the run did not keep it. */
    public Sensor sensor(String id) {
        for (Sensor sensor : sensors) {
            if (sensor.id().equals(id)) {
                return sensor;
            }
        }
        return null;
    }

    /**
     * One sensor of a run.
     *
     * @param id its {@code bootui.agent.sensors} id
     * @param reason why it did not record the run after startup whole (off, not claimed, records lost, cleared), or
     *     {@code null} when it did: its route and execution keys are then complete
     * @param startupReason why it did not record the run's startup whole, such as the agent installing it while the
     *     application started, or {@code null}: its startup keys are then complete
     * @param omittedKeys keys left out by a bound (BootUI's own caps, an owner not named yet), so the kept keys are a
     *     subset of what it recorded
     */
    public record Sensor(String id, String reason, String startupReason, long omittedKeys) {

        public Sensor {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * One side-effect key: what (sensor, kind, target) and who (owner), with how often.
     *
     * @param sensor its sensor's id
     * @param kind what it did, as a Side Effects row says: {@code connect}, {@code read}, {@code process}, {@code
     *     environment variable}
     * @param target the normalized, masked target: a host and port, a path pattern, a file name, a variable name
     * @param scope {@code route}, {@code execution}, or {@code startup}
     * @param owner the route, the execution's label, or {@code startup}
     * @param client the network client recognized, or {@code null}
     * @param count how many operations it counts
     */
    public record Key(
            String sensor, String kind, String target, String scope, String owner, String client, long count) {

        public Key {
            Objects.requireNonNull(sensor, "sensor");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(owner, "owner");
        }

        /** What identifies the key across runs: everything but the client and the count. */
        public String identity() {
            return sensor + '\u0000' + kind + '\u0000' + target + '\u0000' + scope + '\u0000' + owner;
        }
    }
}
