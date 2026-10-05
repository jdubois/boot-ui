/**
 * Side Effects ({@code docs/PLAN-v2.md} §5.16, M5-5a): the engine's side of the BootUI agent's side-effect sensors.
 * The agent records each operation as a fixed record of longs on its own ring, already aggregated per thread; here, over
 * JDK types only, records are decoded ({@link SideEffectRecord}), their strings resolved and their targets normalized
 * ({@link SideEffectsNormalizer}), attributed to a route, an execution, startup, or a thread family, and aggregated into
 * bounded rows per sensor with an Other row ({@link SideEffectsStore}). {@link SideEffectsService} wires them to a
 * claim's drainer and serves the panel, {@code get_side_effects}, and {@code bootui side-effects}. M5-5a ships the
 * {@code processes} sensor; the catalog names the others ({@link SideEffectsCatalog}).
 */
package io.github.jdubois.bootui.engine.sideeffects;
