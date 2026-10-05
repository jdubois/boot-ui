package io.github.jdubois.bootui.autoconfigure.crac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.CracRuntimeStatusDto;
import io.github.jdubois.bootui.engine.crac.CracRuntimeInventory;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class CracRuntimeStatusCollectorTests {

    @Test
    void reportsMissingApiWhenOrgCracIsAbsent() {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(), List::of, className -> false, CracRuntimeInventory::empty, () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.cracApiPresent()).isFalse();
        assertThat(status.cracCapableJvm()).isFalse();
        assertThat(status.checkpointOnRefresh()).isFalse();
        assertThat(status.checkpointTo()).isNull();
        assertThat(status.summary()).contains("not on the classpath").contains("managed by the Spring Boot BOM");
    }

    @Test
    void reportsUnsupportedApiProviderWithoutClaimingNoOpCheckpoint() {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(), List::of, "org.crac.Core"::equals, CracRuntimeInventory::empty, () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.cracApiPresent()).isTrue();
        assertThat(status.cracCapableJvm()).isFalse();
        assertThat(status.summary())
                .contains(
                        "no CRaC implementation marker",
                        "UnsupportedOperationException",
                        "without resource notifications",
                        "not a harmless no-op");
    }

    @Test
    void detectsCracCapableJvmAndCheckpointArguments() {
        Set<String> present = Set.of("org.crac.Core", "jdk.crac.Core");
        MockEnvironment environment = new MockEnvironment().withProperty("spring.context.checkpoint", "onRefresh");
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                environment,
                () -> List.of("-XX:CRaCCheckpointTo=/tmp/cr", "-Dfoo=bar", "-XX:+UseG1GC"),
                present::contains,
                CracRuntimeInventory::empty,
                () -> "onRefresh");

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.cracApiPresent()).isTrue();
        assertThat(status.cracCapableJvm()).isTrue();
        assertThat(status.checkpointOnRefresh()).isTrue();
        assertThat(status.checkpointTo()).isEqualTo("/tmp/cr");
        assertThat(status.cracJvmArgs()).containsExactly("-XX:CRaCCheckpointTo=/tmp/cr");
        assertThat(status.summary()).contains("onRefresh");
    }

    @Test
    void parsesRestoreFromArgument() {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(),
                () -> List.of("-XX:CRaCRestoreFrom=/snapshots/app"),
                className -> true,
                CracRuntimeInventory::empty,
                () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.restoreFrom()).isEqualTo("/snapshots/app");
        assertThat(status.cracJvmArgs()).containsExactly("-XX:CRaCRestoreFrom=/snapshots/app");
    }

    @Test
    void addsCachedConfigurationCaveatWhenCheckpointOnRefresh() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.context.checkpoint", "onRefresh");
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                environment, List::of, className -> false, CracRuntimeInventory::empty, () -> "onRefresh");

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.checkpointOnRefresh()).isTrue();
        assertThat(status.restoreCaveats())
                .anyMatch(caveat ->
                        caveat.contains("Already-cached configuration") && caveat.contains("runtime updates"));
    }

    @Test
    void hasNoFrozenConfigurationCaveatWithoutCheckpointOnRefresh() {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(), List::of, className -> false, CracRuntimeInventory::empty, () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.restoreCaveats()).noneMatch(caveat -> caveat.contains("frozen into the checkpoint"));
    }

    @Test
    void surfacesConnectionPoolsAsRestoreCaveat() {
        CracRuntimeInventory inventory =
                new CracRuntimeInventory(List.of("dataSource : com.zaxxer.hikari.HikariDataSource"));
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(), List::of, className -> false, () -> inventory, () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.restoreCaveats())
                .anyMatch(caveat -> caveat.contains("connection pool") && caveat.contains("CRAC-POOL-001"));
    }

    @Test
    void surfacesHikariLifecycleIssuesAsRestoreCaveat() {
        CracRuntimeInventory inventory = new CracRuntimeInventory(
                List.of(),
                List.of(),
                List.of("dataSource : com.zaxxer.hikari.HikariDataSource - allowPoolSuspension=false"),
                List.of(),
                true,
                false,
                false);
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(), List::of, className -> false, () -> inventory, () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.restoreCaveats())
                .anyMatch(caveat -> caveat.contains("allowPoolSuspension=false") && caveat.contains("CRAC-POOL-004"));
    }

    @Test
    void warnsWhenEnvironmentClaimsCheckpointOnRefreshButSpringPropertyIsUnset() {
        // Spring Boot's Environment can see spring.context.checkpoint from application.yml or an OS
        // environment variable, but Spring Framework's DefaultLifecycleProcessor only ever honors the
        // property through org.springframework.core.SpringProperties (a JVM system property or a
        // classpath spring.properties file). This models the mismatch: the Environment claims onRefresh
        // is set, but the actual SpringProperties-backed source has no value, so no automatic checkpoint
        // will really be taken.
        MockEnvironment environment = new MockEnvironment().withProperty("spring.context.checkpoint", "onRefresh");
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                environment, List::of, className -> false, CracRuntimeInventory::empty, () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.checkpointOnRefresh()).isFalse();
        assertThat(status.restoreCaveats())
                .anyMatch(caveat -> caveat.contains("SpringProperties")
                        && caveat.contains("does not request an automatic checkpoint"));
    }

    @Test
    void hasNoMismatchCaveatWhenSpringPropertyAgreesWithEnvironment() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.context.checkpoint", "onRefresh");
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                environment, List::of, className -> false, CracRuntimeInventory::empty, () -> "onRefresh");

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.restoreCaveats())
                .noneMatch(caveat -> caveat.contains("appears only in the Spring Environment"));
    }

    @Test
    void hasNoMismatchCaveatWhenNeitherSourceClaimsCheckpointOnRefresh() {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(), List::of, className -> false, CracRuntimeInventory::empty, () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.restoreCaveats()).noneMatch(caveat -> caveat.contains("SpringProperties"));
    }

    @Test
    void surfacesExitOnRefreshAsHaltWithoutCleanup() {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(),
                List::of,
                className -> false,
                CracRuntimeInventory::empty,
                () -> null,
                () -> "onRefresh");

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.checkpointOnRefresh()).isFalse();
        assertThat(status.summary()).contains("halts the JVM before lifecycle startup", "not a cleanup dry run");
        assertThat(status.restoreCaveats())
                .anyMatch(caveat -> caveat.contains("Runtime.halt")
                        && caveat.contains("does not exercise checkpoint cleanup or resource callbacks"));
    }

    @Test
    void recognizesLegacyJavaxCracImplementationMarker() {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(), List::of, "javax.crac.Core"::equals, CracRuntimeInventory::empty, () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.cracApiPresent()).isFalse();
        assertThat(status.cracCapableJvm()).isTrue();
    }

    @Test
    void markerOnlyRuntimeDoesNotCertifyRealSupportOrRequireNewerContextApi() {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(),
                List::of,
                name -> {
                    assertThat(name).isIn("org.crac.Core", "jdk.crac.Core", "javax.crac.Core");
                    return !"javax.crac.Core".equals(name);
                },
                CracRuntimeInventory::empty,
                () -> null,
                () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.cracCapableJvm()).isTrue();
        assertThat(status.summary()).contains("operational checkpoint support is unverified");
        assertThat(status.restoreCaveats()).anyMatch(value -> value.contains("marker classes do not verify"));
        assertThat(status.restoreCaveats())
                .anyMatch(value -> value.contains("engine") && value.contains("CRIU") && value.contains("Warp"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"simengine", "pauseengine", "simengine,option=value"})
    void simulationEngineOptionIsNotRealImageEvidence(String engine) {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(),
                () -> List.of("-XX:CRaCEngine=" + engine),
                name -> true,
                CracRuntimeInventory::empty,
                () -> null,
                () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.cracCapableJvm()).isTrue();
        assertThat(status.summary()).contains("does not establish real checkpoint images");
        assertThat(status.restoreCaveats())
                .anyMatch(value -> value.contains("does not create a real checkpoint image"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "warp", "criu", "simengine-custom"})
    void namedEngineStillDoesNotCertifySupport(String engine) {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(),
                () -> List.of("-XX:CRaCEngine=" + engine),
                name -> true,
                CracRuntimeInventory::empty,
                () -> null,
                () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.summary()).contains("support is unverified").doesNotContain("simulation/pause engine");
        assertThat(status.restoreCaveats()).anyMatch(value -> value.contains("engine") && value.contains("unverified"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"onrefresh", "ONREFRESH", " onRefresh", "onRefresh ", "", "true"})
    void onRefreshPropertiesUseExactCaseSensitiveValues(String value) {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment().withProperty("spring.context.checkpoint", value),
                List::of,
                name -> true,
                CracRuntimeInventory::empty,
                () -> value,
                () -> value);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.checkpointOnRefresh()).isFalse();
        assertThat(status.summary()).doesNotContain("halts", "Checkpoint-on-refresh is configured");
        assertThat(status.restoreCaveats()).noneMatch(caveat -> caveat.contains("appears only"));
    }

    @Test
    void bothFlagsMeanCheckpointFirstThenHaltNotAnExitOnlyDryRun() {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(),
                List::of,
                name -> true,
                CracRuntimeInventory::empty,
                () -> "onRefresh",
                () -> "onRefresh");

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.checkpointOnRefresh()).isTrue();
        assertThat(status.summary())
                .contains("Checkpoint is attempted first", "halts the JVM")
                .doesNotContain("safe dry run", "without writing");
        assertThat(status.restoreCaveats())
                .anyMatch(value -> value.contains("checkpoint is attempted first, before exit"));
        assertThat(status.restoreCaveats()).anyMatch(value -> value.contains("request was consumed"));
    }

    @Test
    void environmentOnlyExitOptionDoesNotClaimTheJvmWillHalt() {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment().withProperty("spring.context.exit", "onRefresh"),
                List::of,
                name -> false,
                CracRuntimeInventory::empty,
                () -> null,
                () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.summary()).doesNotContain("halts");
        assertThat(status.restoreCaveats())
                .anyMatch(value -> value.contains("spring.context.exit=onRefresh appears only"));
    }

    @Test
    void maskedExposureCanExplicitlyDisableSecretMasking() {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(),
                () -> List.of("-XX:CRaCRestoreFrom=/images?token=visible"),
                name -> false,
                CracRuntimeInventory::empty,
                () -> null,
                () -> null,
                () -> null,
                exposure(ValueExposure.MASKED, false));

        assertThat(collector.collect().restoreFrom()).isEqualTo("/images?token=visible");
    }

    @Test
    void restoreArgumentIsOnlyAHintButMxBeanTimeIsObservedEvidence() {
        CracRuntimeStatusCollector hintOnly = new CracRuntimeStatusCollector(
                new MockEnvironment(),
                () -> List.of("-XX:CRaCRestoreFrom=/image"),
                name -> true,
                CracRuntimeInventory::empty,
                () -> null,
                () -> null,
                () -> -1L,
                exposure(ValueExposure.MASKED, true));
        CracRuntimeStatusCollector restored = new CracRuntimeStatusCollector(
                new MockEnvironment(),
                List::of,
                name -> true,
                CracRuntimeInventory::empty,
                () -> null,
                () -> null,
                () -> 100L,
                exposure(ValueExposure.MASKED, true));

        assertThat(hintOnly.collect().restoreCaveats()).anyMatch(value -> value.contains("launch hint"));
        assertThat(restored.collect().restoreCaveats())
                .anyMatch(value -> value.contains("MXBean reports a restore time")
                        && value.contains("does not verify application readiness"));
    }

    @Test
    void optionalPublicRestoreApiIsClassloaderSafeAndNeverCallsCheckpoint() {
        ClassLoader absent = new ClassLoader(null) {};
        assertThat(CracRuntimeStatusCollector.restoreTime(absent)).isNull();
        assertThat(CracRuntimeStatusCollector.readRestoreTime(PublicRestoreBean.class))
                .isEqualTo(42L);
        assertThatThrownBy(() -> CracRuntimeStatusCollector.readRestoreTime(FailedRestoreBean.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Public CRaC restore-time observation failed.")
                .hasNoCause();
    }

    @Test
    void failedObservationsAreExplicitAndNeverExposeExceptionMessages() {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(),
                () -> {
                    throw new IllegalStateException("password=args");
                },
                name -> {
                    throw new NoClassDefFoundError("password=class");
                },
                () -> {
                    throw new IllegalStateException("password=inventory");
                },
                () -> {
                    throw new IllegalStateException("password=checkpoint");
                },
                () -> {
                    throw new IllegalStateException("password=exit");
                },
                () -> {
                    throw new IllegalStateException("password=restore");
                },
                exposure(ValueExposure.MASKED, true));

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.cracApiPresent()).isFalse();
        assertThat(status.cracCapableJvm()).isFalse();
        assertThat(status.checkpointOnRefresh()).isFalse();
        assertThat(status.summary())
                .contains("observations failed", "observations are incomplete")
                .doesNotContain("API is not on the classpath", "no CRaC implementation marker was observed");
        assertThat(status.restoreCaveats())
                .anyMatch(value -> value.contains("inventory") && value.contains("unavailable"));
        assertThat(status.restoreCaveats())
                .filteredOn(value -> value.contains("observation failed"))
                .hasSize(8);
        assertThat(status.toString()).doesNotContain("password=");
    }

    @Test
    void nullInventoryDoesNotProduceEmptySuccessfulCaveats() {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(), List::of, name -> false, () -> null, () -> null, () -> null);

        assertThat(collector.collect().restoreCaveats())
                .anyMatch(value -> value.contains("Resource inventory is unavailable"));
    }

    @Test
    void displaysOnlyExactAllowlistedOptionsAndMasksBeforeTruncation() {
        String secretPath = "/images/" + "x".repeat(400) + "?token=must-not-leak";
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(),
                () -> List.of(
                        "-DmyCRaCPassword=must-not-leak",
                        "-XX:CRaCCheckpointToExtra=must-not-leak",
                        "-XX:CRaCCheckpointTo=" + secretPath,
                        "-XX:CRaCRestoreFrom=/clean",
                        "-XX:CRaCEngine=simengine",
                        "-Dspring.context.checkpoint=onRefresh"),
                name -> true,
                CracRuntimeInventory::empty,
                () -> null,
                () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.checkpointTo()).isEqualTo(SecretMasker.MASKED_VALUE);
        assertThat(status.cracJvmArgs())
                .containsExactly(
                        "-XX:CRaCCheckpointTo=******", "-XX:CRaCRestoreFrom=/clean", "-XX:CRaCEngine=simengine");
        assertThat(status.toString()).doesNotContain("must-not-leak", "myCRaCPassword", "CheckpointToExtra");
    }

    @Test
    void honorsLiveExposurePolicyAndBoundsValueAndResourcePreviews() {
        AtomicReference<ValueExposure> mode = new AtomicReference<>(ValueExposure.METADATA_ONLY);
        ExposurePolicy policy = new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return mode.get();
            }

            @Override
            public boolean maskSecrets() {
                return true;
            }
        };
        CracRuntimeInventory inventory = new CracRuntimeInventory(IntStream.range(0, 30)
                .mapToObj(i -> "pool" + i + " : " + "x".repeat(1000))
                .toList());
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(),
                () -> List.of("-XX:CRaCCheckpointTo=/images?token=secret"),
                name -> true,
                () -> inventory,
                () -> null,
                () -> null,
                () -> null,
                policy);

        assertThat(collector.collect().checkpointTo()).isEqualTo("******");
        mode.set(ValueExposure.FULL);
        CracRuntimeStatusDto full = collector.collect();
        assertThat(full.checkpointTo()).isEqualTo("/images?token=secret");
        assertThat(full.restoreCaveats()).anyMatch(value -> value.contains("additional observations omitted"));
        assertThat(full.restoreCaveats().toString()).doesNotContain("pool5 :");
        assertThat(full.restoreCaveats())
                .allSatisfy(value -> assertThat(value.length()).isLessThan(2200));
        mode.set(ValueExposure.MASKED);
        assertThat(collector.collect().checkpointTo()).isEqualTo("******");
    }

    private static ExposurePolicy exposure(ValueExposure mode, boolean maskSecrets) {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return mode;
            }

            @Override
            public boolean maskSecrets() {
                return maskSecrets;
            }
        };
    }

    public static class PublicRestoreBean {
        public static PublicRestoreBean getCRaCMXBean() {
            return new PublicRestoreBean();
        }

        public long getRestoreTime() {
            return 42L;
        }
    }

    public static class FailedRestoreBean {
        public static FailedRestoreBean getCRaCMXBean() {
            throw new IllegalStateException("password=hidden");
        }

        public long getRestoreTime() {
            throw new AssertionError("Not reached");
        }
    }
}
