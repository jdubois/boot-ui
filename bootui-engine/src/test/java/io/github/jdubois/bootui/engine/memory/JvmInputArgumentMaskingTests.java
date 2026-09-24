package io.github.jdubois.bootui.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class JvmInputArgumentMaskingTests {

    private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJib290dWkifQ.c2lnbmF0dXJl";

    private static ExposurePolicy policy(ValueExposure exposure, boolean maskSecrets) {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return exposure;
            }

            @Override
            public boolean maskSecrets() {
                return maskSecrets;
            }
        };
    }

    private static List<String> masked(ValueExposure exposure, String... arguments) {
        return JvmInputArgumentMasking.mask(List.of(arguments), policy(exposure, true));
    }

    @Test
    void masksSecretNamedSystemPropertyValuesAndKeepsTheKey() {
        assertThat(masked(
                        ValueExposure.MASKED,
                        "-Dspring.datasource.password=s3cr3t-value",
                        "-Dapp.api-token=abc",
                        "-Dspring.profiles.active=dev,local"))
                .containsExactly(
                        "-Dspring.datasource.password=******",
                        "-Dapp.api-token=******",
                        "-Dspring.profiles.active=dev,local");
    }

    @Test
    void masksSystemPropertyValuesThatLookLikeSecretsUnderAnInnocuousKey() {
        assertThat(masked(
                        ValueExposure.MASKED,
                        "-Dspring.datasource.url=jdbc:postgresql://admin:hunter2@db:5432/app",
                        "-Dapp.header=" + JWT))
                .containsExactly("-Dspring.datasource.url=******", "-Dapp.header=******");
    }

    @Test
    void keepsJvmFlagsAndArgumentsWithoutValues() {
        assertThat(masked(
                        ValueExposure.MASKED,
                        "-Xmx512m",
                        "-XX:+UseG1GC",
                        "-XX:MaxRAMPercentage=75",
                        "-XX:NativeMemoryTracking=summary",
                        "--add-opens=java.base/java.lang=ALL-UNNAMED",
                        "-Dbootui.enabled",
                        "-Dapp.password="))
                .containsExactly(
                        "-Xmx512m",
                        "-XX:+UseG1GC",
                        "-XX:MaxRAMPercentage=75",
                        "-XX:NativeMemoryTracking=summary",
                        "--add-opens=java.base/java.lang=ALL-UNNAMED",
                        "-Dbootui.enabled",
                        "-Dapp.password=");
    }

    @Test
    void masksSecretOptionsInsideAgentArguments() {
        assertThat(masked(
                        ValueExposure.MASKED,
                        "-javaagent:/opt/agent.jar=license_key=abc123,app=demo",
                        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005",
                        "-agentpath:/opt/libprof.so=" + JWT,
                        "-javaagent:/opt/plain.jar"))
                .containsExactly(
                        "-javaagent:/opt/agent.jar=license_key=******,app=demo",
                        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005",
                        "-agentpath:/opt/libprof.so=******",
                        "-javaagent:/opt/plain.jar");
    }

    @Test
    void masksABareArgumentThatLooksLikeASecret() {
        assertThat(masked(ValueExposure.MASKED, JWT, "-Xint")).containsExactly("******", "-Xint");
    }

    @Test
    void metadataOnlyMasksEverySystemPropertyAndAgentOptionButKeepsJvmFlags() {
        assertThat(masked(
                        ValueExposure.METADATA_ONLY,
                        "-Dspring.profiles.active=dev",
                        "-agentlib:jdwp=transport=dt_socket,server=y",
                        "-XX:MaxRAMPercentage=75",
                        "-Xss512k",
                        "-XX:HeapDumpPath=https://user:pass@example.com/dumps"))
                .containsExactly(
                        "-Dspring.profiles.active=******",
                        "-agentlib:jdwp=******",
                        "-XX:MaxRAMPercentage=75",
                        "-Xss512k",
                        "-XX:HeapDumpPath=******");
    }

    @Test
    void metadataOnlyMasksEvenWhenSecretMaskingIsDisabled() {
        List<String> result = JvmInputArgumentMasking.mask(
                List.of("-Dspring.datasource.password=s3cr3t"), policy(ValueExposure.METADATA_ONLY, false));

        assertThat(result).containsExactly("-Dspring.datasource.password=******");
    }

    @Test
    void fullExposureAndDisabledMaskingReturnArgumentsUnchanged() {
        List<String> arguments = List.of("-Dspring.datasource.password=s3cr3t", "-Dapp.header=" + JWT);

        assertThat(JvmInputArgumentMasking.mask(arguments, policy(ValueExposure.FULL, true)))
                .containsExactlyElementsOf(arguments);
        assertThat(JvmInputArgumentMasking.mask(arguments, policy(ValueExposure.MASKED, false)))
                .containsExactlyElementsOf(arguments);
    }

    @Test
    void failsClosedWithoutAPolicyAndToleratesMissingInput() {
        assertThat(JvmInputArgumentMasking.mask(List.of("-Dapp.secret=x"), null))
                .containsExactly("-Dapp.secret=******");
        assertThat(JvmInputArgumentMasking.mask(List.of("-Dapp.secret=x"), policy(null, true)))
                .containsExactly("-Dapp.secret=******");
        assertThat(JvmInputArgumentMasking.mask(null, policy(ValueExposure.MASKED, true)))
                .isEmpty();
        assertThat(JvmInputArgumentMasking.mask(Arrays.asList("-Xint", null), policy(ValueExposure.MASKED, true)))
                .containsExactly("-Xint");
    }
}
