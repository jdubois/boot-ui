package io.github.jdubois.bootui.autoconfigure.web;

import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.startup.StartupEndpoint;
import org.springframework.boot.actuate.startup.StartupEndpoint.StartupDescriptor;
import org.springframework.boot.context.metrics.buffering.StartupTimeline;
import org.springframework.boot.context.metrics.buffering.StartupTimeline.TimelineEvent;
import org.springframework.core.metrics.StartupStep;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Controller-level tests for {@link StartupController}.
 *
 * <p>Covers the missing-actuator empty report, a null timeline (no buffered
 * startup data), and the happy path where buffered events are projected to
 * {@link io.github.jdubois.bootui.core.BootUiDtos.StartupStepDto} shape.
 * {@link StartupDescriptor} is {@code final} and uses
 * {@link MockMakers#INLINE}; {@link StartupTimeline} and
 * {@link TimelineEvent} are non-final and use the default mock maker.</p>
 */
class StartupControllerTests {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StartupEndpoint> providerOf(StartupEndpoint endpoint) {
        ObjectProvider<StartupEndpoint> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(endpoint);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StartupEndpoint> emptyProvider() {
        ObjectProvider<StartupEndpoint> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @Test
    void startupReturnsEmptyWhenActuatorUnavailable() throws Exception {
        MockMvc mvc = standaloneSetup(new StartupController(emptyProvider())).build();

        mvc.perform(get("/bootui/api/startup").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.steps").isArray())
            .andExpect(jsonPath("$.steps").isEmpty());
    }

    @Test
    void startupReturnsEmptyWhenTimelineIsNull() throws Exception {
        StartupDescriptor descriptor = mock(StartupDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(descriptor.getTimeline()).thenReturn(null);

        StartupEndpoint endpoint = mock(StartupEndpoint.class);
        when(endpoint.startupSnapshot()).thenReturn(descriptor);

        MockMvc mvc = standaloneSetup(new StartupController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/startup").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.steps").isEmpty());
    }

    @Test
    void startupReturnsStepsFromTimeline() throws Exception {
        StartupStep.Tag tag = new StartupStep.Tag() {
            @Override
            public String getKey() {
                return "bean.name";
            }

            @Override
            public String getValue() {
                return "dataSource";
            }
        };

        StartupStep step = mock(StartupStep.class);
        when(step.getId()).thenReturn(1L);
        when(step.getParentId()).thenReturn(null);
        when(step.getName()).thenReturn("spring.beans.instantiate");
        when(step.getTags()).thenReturn(() -> List.of(tag).iterator());

        TimelineEvent event = mock(TimelineEvent.class);
        when(event.getStartupStep()).thenReturn(step);
        when(event.getDuration()).thenReturn(Duration.ofMillis(42));

        StartupTimeline timeline = mock(StartupTimeline.class);
        when(timeline.getEvents()).thenReturn(List.of(event));

        StartupDescriptor descriptor = mock(StartupDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(descriptor.getTimeline()).thenReturn(timeline);

        StartupEndpoint endpoint = mock(StartupEndpoint.class);
        when(endpoint.startupSnapshot()).thenReturn(descriptor);

        MockMvc mvc = standaloneSetup(new StartupController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/startup").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.steps.length()").value(1))
            .andExpect(jsonPath("$.steps[0].id").value(1))
            .andExpect(jsonPath("$.steps[0].parentId").doesNotExist())
            .andExpect(jsonPath("$.steps[0].name").value("spring.beans.instantiate"))
            .andExpect(jsonPath("$.steps[0].durationMs").value(42))
            .andExpect(jsonPath("$.steps[0].tags[0].key").value("bean.name"))
            .andExpect(jsonPath("$.steps[0].tags[0].value").value("dataSource"));
    }

    @Test
    void startupHandlesZeroDurationWhenDurationIsNull() throws Exception {
        StartupStep step = mock(StartupStep.class);
        when(step.getId()).thenReturn(2L);
        when(step.getParentId()).thenReturn(1L);
        when(step.getName()).thenReturn("spring.context.refresh");
        when(step.getTags()).thenReturn(List.<StartupStep.Tag>of()::iterator);

        TimelineEvent event = mock(TimelineEvent.class);
        when(event.getStartupStep()).thenReturn(step);
        when(event.getDuration()).thenReturn(null);

        StartupTimeline timeline = mock(StartupTimeline.class);
        when(timeline.getEvents()).thenReturn(List.of(event));

        StartupDescriptor descriptor = mock(StartupDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(descriptor.getTimeline()).thenReturn(timeline);

        StartupEndpoint endpoint = mock(StartupEndpoint.class);
        when(endpoint.startupSnapshot()).thenReturn(descriptor);

        MockMvc mvc = standaloneSetup(new StartupController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/startup").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.steps[0].durationMs").value(0))
            .andExpect(jsonPath("$.steps[0].parentId").value(1));
    }
}
