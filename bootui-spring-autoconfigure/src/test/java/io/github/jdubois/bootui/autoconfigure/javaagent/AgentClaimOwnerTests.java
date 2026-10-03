package io.github.jdubois.bootui.autoconfigure.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentHandoffs;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.GenericApplicationContext;

class AgentClaimOwnerTests {

    @BeforeEach
    void reset() {
        FakeBridge.reset();
    }

    @Test
    void refinesOnItsOwnRefreshOnlyAndDisarmsOnceWhenItsContextCloses() {
        AgentClaimOwner owner = owner();
        GenericApplicationContext other = new GenericApplicationContext();
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            AutoConfigurationPackages.register(context, "com.example.app");
            owner.initialize(context);
            context.addApplicationListener(owner);

            owner.onApplicationEvent(new ContextRefreshedEvent(other));
            assertThat(FakeBridge.CALLS).as("another context's refresh").containsExactly("claim");

            context.refresh();

            assertThat(context.getBean(AgentClaimOwner.class)).isSameAs(owner);
            assertThat(context.getBean(AgentClaimOwner.BEAN_NAME)).isSameAs(owner);
            assertThat(FakeBridge.CALLS).containsExactly("claim", "refine");
            assertThat(FakeBridge.REQUESTS.get(1)).containsEntry("packages", List.of("com.example.app"));

            owner.onApplicationEvent(new ContextClosedEvent(other));
            assertThat(FakeBridge.CALLS).as("another context's close").containsExactly("claim", "refine");
        }

        assertThat(FakeBridge.CALLS).as("close, then destroy").containsExactly("claim", "refine", "disarm");
        assertThat(owner.claim().armed()).isFalse();
        owner.destroy();
        assertThat(FakeBridge.CALLS).containsExactly("claim", "refine", "disarm");
    }

    @Test
    void attachesTheContextsHandoffsWhenItsContextIsRefreshedAndDetachesThemWhenItCloses() {
        AgentClaimOwner owner = owner();
        AgentHandoffs handoffs = new AgentHandoffs(null, null, null);
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(AgentHandoffs.class, () -> handoffs);
            owner.initialize(context);
            context.addApplicationListener(owner);

            assertThat(owner.claim().handoffs())
                    .as("nothing captured before the engine is ready")
                    .isNull();
            context.refresh();

            assertThat(owner.claim().handoffs()).isSameAs(handoffs);
        }
        assertThat(owner.claim().handoffs()).isNull();
    }

    @Test
    void aFailedStartDisarms() {
        AgentClaimOwner owner = owner();

        owner.onApplicationEvent(new ApplicationFailedEvent(
                new SpringApplication(), new String[0], null, new IllegalStateException("boom")));

        assertThat(FakeBridge.CALLS).containsExactly("claim", "disarm");
        owner.onApplicationEvent(new ApplicationFailedEvent(
                new SpringApplication(), new String[0], null, new IllegalStateException("again")));
        assertThat(FakeBridge.CALLS).containsExactly("claim", "disarm");
    }

    @Test
    void listensOnlyToTheLifecycleEventsSoRequestEventsNeverReachIt() {
        AgentClaimOwner owner = owner();

        assertThat(owner.supportsEventType(ContextRefreshedEvent.class)).isTrue();
        assertThat(owner.supportsEventType(ContextClosedEvent.class)).isTrue();
        assertThat(owner.supportsEventType(ApplicationFailedEvent.class)).isTrue();
        assertThat(owner.supportsEventType(org.springframework.context.PayloadApplicationEvent.class))
                .isFalse();
    }

    @Test
    void anotherContextsFailureDoesNotDisarm() {
        AgentClaimOwner owner = owner();
        try (GenericApplicationContext mine = new GenericApplicationContext();
                GenericApplicationContext child = new GenericApplicationContext()) {
            owner.initialize(mine);

            owner.onApplicationEvent(new ApplicationFailedEvent(
                    new SpringApplication(), new String[0], child, new IllegalStateException("child")));

            assertThat(FakeBridge.CALLS).containsExactly("claim");
        }
    }

    @Test
    void destroyingTheSingletonsDisarmsWhenTheContextFailsToRefresh() {
        AgentClaimOwner owner = owner();
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            owner.initialize(context);
            context.registerBean("broken", Map.class, () -> {
                throw new IllegalStateException("boom");
            });

            try {
                context.refresh();
            } catch (RuntimeException expected) {
                // The refresh fails and destroys the singletons, the owner among them.
            }
        }

        assertThat(FakeBridge.CALLS).containsExactly("claim", "disarm");
    }

    @Test
    void initializingTwiceKeepsTheFirstContext() {
        AgentClaimOwner owner = owner();
        try (GenericApplicationContext first = new GenericApplicationContext();
                GenericApplicationContext second = new GenericApplicationContext()) {
            owner.initialize(first);
            owner.initialize(second);

            assertThat(first.getBeanFactory().containsSingleton(AgentClaimOwner.BEAN_NAME))
                    .isTrue();
            assertThat(second.getBeanFactory().containsSingleton(AgentClaimOwner.BEAN_NAME))
                    .isFalse();
        }
    }

    private static AgentClaimOwner owner() {
        return new AgentClaimOwner(AgentClaim.claim(
                AgentBridgeAccess.bind(FakeBridge.class), "app", "app@1", "dev", List.of("com.example")));
    }
}
