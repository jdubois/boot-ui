package io.github.jdubois.bootui.quarkus4.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.builditem.RunTimeConfigurationDefaultBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.runtime.LaunchMode;
import java.util.ArrayList;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BootUiOrmSessionListenerRegistrationTest {

    @Test
    void includedNativeOrmKeepsTheListenerDefaultAndItsConstructorReachable() {
        var defaults = new ArrayList<RunTimeConfigurationDefaultBuildItem>();
        var reflection = new ArrayList<ReflectiveClassBuildItem>();
        new BootUiQuarkusProcessor()
                .registerOrmSessionListener(
                        new BootUiConsoleBuildItem(LaunchMode.NORMAL, true, true),
                        new Capabilities(Set.of(Capability.HIBERNATE_ORM)),
                        defaults::add,
                        reflection::add);

        String listener = "io.github.jdubois.bootui.quarkus4.orm.BootUiHibernateSessionListener";
        assertThat(defaults).anySatisfy(value -> {
            assertThat(value.getKey())
                    .isEqualTo("quarkus.hibernate-orm.unsupported-properties.\"hibernate.session.events.auto\"");
            assertThat(value.getValue()).isEqualTo(listener);
        });
        assertThat(reflection).singleElement().satisfies(value -> {
            assertThat(value.getClassNames()).containsExactly(listener);
            assertThat(value.isConstructors()).isTrue();
            assertThat(value.isMethods()).isFalse();
            assertThat(value.isFields()).isFalse();
        });
    }

    @Test
    void absentOrmExcludedNativeAndPackagedJvmNeverRegisterTheOptionalListener() {
        for (var console : new BootUiConsoleBuildItem[] {
            new BootUiConsoleBuildItem(LaunchMode.NORMAL, true, false),
            new BootUiConsoleBuildItem(LaunchMode.NORMAL, false, true)
        }) {
            var defaults = new ArrayList<RunTimeConfigurationDefaultBuildItem>();
            var reflection = new ArrayList<ReflectiveClassBuildItem>();
            new BootUiQuarkusProcessor()
                    .registerOrmSessionListener(
                            console,
                            new Capabilities(Set.of(Capability.HIBERNATE_ORM)),
                            defaults::add,
                            reflection::add);
            assertThat(defaults).isEmpty();
            assertThat(reflection).isEmpty();
        }
        new BootUiQuarkusProcessor()
                .registerOrmSessionListener(
                        new BootUiConsoleBuildItem(LaunchMode.NORMAL, true, true),
                        new Capabilities(Set.of()),
                        value -> {
                            throw new AssertionError("ORM configuration must remain absent");
                        },
                        value -> {
                            throw new AssertionError("An absent ORM class must not be registered for reflection");
                        });
    }

    @Test
    void developmentJvmKeepsTheDefaultWithoutNativeReflectionRegistration() {
        var defaults = new ArrayList<RunTimeConfigurationDefaultBuildItem>();
        var reflection = new ArrayList<ReflectiveClassBuildItem>();
        new BootUiQuarkusProcessor()
                .registerOrmSessionListener(
                        new BootUiConsoleBuildItem(LaunchMode.DEVELOPMENT, false, false),
                        new Capabilities(Set.of(Capability.HIBERNATE_ORM)),
                        defaults::add,
                        reflection::add);
        assertThat(defaults).isNotEmpty();
        assertThat(reflection).isEmpty();
    }
}
