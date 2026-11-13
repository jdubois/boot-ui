package io.github.jdubois.bootui.engine.memory;

import java.util.List;
import java.util.Set;

final class MemoryRuleRegistry {

    /**
     * Retired IDs are never reused: MEM-HEAP-007, MEM-HEAP-008, MEM-FOOTPRINT-004, MEM-POOL-006,
     * MEM-POOL-007, MEM-THREAD-003, MEM-THREAD-004, MEM-CONTENT-004, MEM-CLASS-002 (see
     * docs/MEMORY-CHECKS.md). They stay reserved so existing {@code .bootui/boot-ui.yml} dismissals
     * remain valid and never silently apply to a different check.
     */
    static final Set<String> RETIRED_RULE_IDS = Set.of(
            "MEM-HEAP-007",
            "MEM-HEAP-008",
            "MEM-FOOTPRINT-004",
            "MEM-POOL-006",
            "MEM-POOL-007",
            "MEM-THREAD-003",
            "MEM-THREAD-004",
            "MEM-CONTENT-004",
            "MEM-CLASS-002");

    private static final List<MemoryRule> ACTIVE_RULES = List.of(
            // Heap pressure
            new HighHeapUtilizationRule(),
            new OldGenerationNearMaxRule(),
            new SmallMaxHeapUnderPressureRule(),
            new CompressedOopsCliffRule(),
            new PendingFinalizationBacklogRule(),
            // Native memory
            new CommittedFootprintNearContainerLimitRule(),
            new PlatformThreadStackReservationRule(),
            new ContainerMemoryPressureRule(),
            new ContainerCgroupThrottlingAndOomRule(),
            // Memory pools
            new MetaspaceSaturationRule(),
            new CodeCacheSaturationRule(),
            new DirectBufferGrowthRule(),
            new UnboundedMetaspaceInContainerRule(),
            new CompressedClassSpaceRule(),
            // GC configuration
            new MissingHeapSizingInContainerRule(),
            new ContainerSupportDisabledRule(),
            new HighGcOverheadRule(),
            new RecentGcOverheadRule(),
            new UnequalInitialAndMaxHeapRule(),
            new SerialGcOnMultiCoreRule(),
            new G1FullGcFrequencyRule(),
            new GcEventDurationOutlierRule(),
            new NonGenerationalZgcRule(),
            new ExternalExplicitGcRule(),
            // Threads
            new DeadlockDetectedRule(),
            new HighBlockedThreadRatioRule(),
            // Heap content
            new BigObjectsRule(),
            new CollectionBloatRule(),
            new DominantClassRule(),
            new ClassLoaderAndProxyBloatRule(),
            // Class loading
            new ExcessiveLoadedClassesRule());

    private MemoryRuleRegistry() {}

    static List<MemoryRule> activeRules() {
        return ACTIVE_RULES;
    }
}
