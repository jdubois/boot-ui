package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.BootUiDtos.BeanList;
import io.github.jdubois.bootui.core.BootUiDtos.ConditionsReport;
import io.github.jdubois.bootui.core.BootUiDtos.LoggersReport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint.ConditionsDescriptor;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint.ContextConditionsDescriptor;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint.MessageAndConditionDescriptor;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint.MessageAndConditionsDescriptor;
import org.springframework.boot.actuate.beans.BeansEndpoint;
import org.springframework.boot.actuate.beans.BeansEndpoint.BeanDescriptor;
import org.springframework.boot.actuate.beans.BeansEndpoint.BeansDescriptor;
import org.springframework.boot.actuate.beans.BeansEndpoint.ContextBeansDescriptor;
import org.springframework.boot.actuate.logging.LoggersEndpoint;
import org.springframework.boot.actuate.logging.LoggersEndpoint.LoggerLevelsDescriptor;
import org.springframework.boot.actuate.logging.LoggersEndpoint.LoggersDescriptor;
import org.springframework.boot.actuate.logging.LoggersEndpoint.SingleLoggerLevelsDescriptor;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;

class LargeAppLimitsTests {

    @Test
    void beansLimitKeepsTotalAndMarksTruncated() {
        BeanDescriptor alpha = beanDescriptor(String.class);
        BeanDescriptor beta = beanDescriptor(Integer.class);
        BeanDescriptor gamma = beanDescriptor(Long.class);

        ContextBeansDescriptor context = inlineMock(ContextBeansDescriptor.class);
        when(context.getBeans()).thenReturn(Map.of("gammaBean", gamma, "alphaBean", alpha, "betaBean", beta));

        BeansDescriptor descriptor = inlineMock(BeansDescriptor.class);
        when(descriptor.getContexts()).thenReturn(Map.of("application", context));

        BeansEndpoint endpoint = mock(BeansEndpoint.class);
        when(endpoint.beans()).thenReturn(descriptor);

        BeansController controller = new BeansController(providerOf(endpoint), propertiesWithLimits(2, 500, 1000, 1000, 200));

        BeanList report = controller.beans();

        assertThat(report.total()).isEqualTo(3);
        assertThat(report.truncated()).isTrue();
        assertThat(report.beans()).extracting("name").containsExactly("alphaBean", "betaBean");
    }

    @Test
    void beansBelowLimitAreNotTruncated() {
        BeanDescriptor alpha = beanDescriptor(String.class);
        BeanDescriptor beta = beanDescriptor(Integer.class);

        ContextBeansDescriptor context = inlineMock(ContextBeansDescriptor.class);
        when(context.getBeans()).thenReturn(Map.of("betaBean", beta, "alphaBean", alpha));

        BeansDescriptor descriptor = inlineMock(BeansDescriptor.class);
        when(descriptor.getContexts()).thenReturn(Map.of("application", context));

        BeansEndpoint endpoint = mock(BeansEndpoint.class);
        when(endpoint.beans()).thenReturn(descriptor);

        BeansController controller = new BeansController(providerOf(endpoint), propertiesWithLimits(5, 500, 1000, 1000, 200));

        BeanList report = controller.beans();

        assertThat(report.total()).isEqualTo(2);
        assertThat(report.truncated()).isFalse();
        assertThat(report.beans()).hasSize(2);
    }

    @Test
    void loggersLimitTruncatesSortedResults() {
        Map<String, LoggerLevelsDescriptor> loggers = new LinkedHashMap<>();
        loggers.put("z.logger", loggerDescriptor("z.logger", LogLevel.INFO, LogLevel.INFO));
        loggers.put("a.logger", loggerDescriptor("a.logger", LogLevel.DEBUG, LogLevel.DEBUG));
        loggers.put("m.logger", loggerDescriptor("m.logger", null, LogLevel.WARN));

        LoggersEndpoint endpoint = mock(LoggersEndpoint.class);
        when(endpoint.loggers()).thenReturn(new LoggersDescriptor(new TreeSet<>(Set.of(LogLevel.INFO, LogLevel.DEBUG)), loggers, Map.of()));

        LoggersController controller = new LoggersController(providerOf(endpoint), propertiesWithLimits(500, 500, 2, 1000, 200));

        LoggersReport report = controller.loggers();

        assertThat(report.truncated()).isTrue();
        assertThat(report.loggers()).extracting("name").containsExactly("a.logger", "m.logger");
    }

    @Test
    void loggersBelowLimitAreNotTruncated() {
        Map<String, LoggerLevelsDescriptor> loggers = new LinkedHashMap<>();
        loggers.put("b.logger", loggerDescriptor("b.logger", LogLevel.INFO, LogLevel.INFO));
        loggers.put("a.logger", loggerDescriptor("a.logger", LogLevel.DEBUG, LogLevel.DEBUG));

        LoggersEndpoint endpoint = mock(LoggersEndpoint.class);
        when(endpoint.loggers()).thenReturn(new LoggersDescriptor(new TreeSet<>(Set.of(LogLevel.INFO, LogLevel.DEBUG)), loggers, Map.of()));

        LoggersController controller = new LoggersController(providerOf(endpoint), propertiesWithLimits(500, 500, 5, 1000, 200));

        LoggersReport report = controller.loggers();

        assertThat(report.truncated()).isFalse();
        assertThat(report.loggers()).hasSize(2);
    }

    @Test
    void conditionsLimitFillsPositiveMatchesBeforeNegativeMatches() {
        MessageAndConditionDescriptor positiveOne = condition("PositiveOne", "matched 1");
        MessageAndConditionDescriptor positiveTwo = condition("PositiveTwo", "matched 2");
        MessageAndConditionDescriptor negativeOne = condition("NegativeOne", "not matched 1");
        MessageAndConditionDescriptor negativeTwo = condition("NegativeTwo", "not matched 2");

        MessageAndConditionsDescriptor negativeEntry = mock(MessageAndConditionsDescriptor.class);
        when(negativeEntry.getNotMatched()).thenReturn(List.of(negativeOne, negativeTwo));
        when(negativeEntry.getMatched()).thenReturn(List.of());

        ContextConditionsDescriptor context = inlineMock(ContextConditionsDescriptor.class);
        when(context.getPositiveMatches()).thenReturn(Map.of("org.example.AutoConfig", List.of(positiveOne, positiveTwo)));
        when(context.getNegativeMatches()).thenReturn(Map.of("org.example.OtherConfig", negativeEntry));
        when(context.getUnconditionalClasses()).thenReturn(Set.of());
        when(context.getExclusions()).thenReturn(List.of());

        ConditionsDescriptor descriptor = inlineMock(ConditionsDescriptor.class);
        when(descriptor.getContexts()).thenReturn(Map.of("application", context));

        ConditionsReportEndpoint endpoint = mock(ConditionsReportEndpoint.class);
        when(endpoint.conditions()).thenReturn(descriptor);

        ConditionsController controller = new ConditionsController(providerOf(endpoint), propertiesWithLimits(500, 500, 1000, 3, 200));

        ConditionsReport report = controller.conditions();

        assertThat(report.truncated()).isTrue();
        assertThat(report.positiveMatches()).hasSize(2);
        assertThat(report.positiveMatches()).extracting("condition").containsExactly("PositiveOne", "PositiveTwo");
        assertThat(report.negativeMatches()).hasSize(1);
        assertThat(report.negativeMatches().getFirst().condition()).isEqualTo("NegativeOne");
    }

    private static BeanDescriptor beanDescriptor(Class<?> type) {
        BeanDescriptor descriptor = inlineMock(BeanDescriptor.class);
        doReturn(type).when(descriptor).getType();
        when(descriptor.getDependencies()).thenReturn(new String[0]);
        when(descriptor.getAliases()).thenReturn(new String[0]);
        return descriptor;
    }

    private static SingleLoggerLevelsDescriptor loggerDescriptor(String name, LogLevel configured, LogLevel effective) {
        return new SingleLoggerLevelsDescriptor(new LoggerConfiguration(name, configured, effective));
    }

    private static MessageAndConditionDescriptor condition(String condition, String message) {
        MessageAndConditionDescriptor descriptor = mock(MessageAndConditionDescriptor.class);
        when(descriptor.getCondition()).thenReturn(condition);
        when(descriptor.getMessage()).thenReturn(message);
        return descriptor;
    }

    private static BootUiProperties propertiesWithLimits(int maxBeans, int maxMappings, int maxLoggers, int maxConditions, int maxScheduled) {
        BootUiProperties properties = new BootUiProperties();
        properties.getLimits().setMaxBeans(maxBeans);
        properties.getLimits().setMaxMappings(maxMappings);
        properties.getLimits().setMaxLoggers(maxLoggers);
        properties.getLimits().setMaxConditions(maxConditions);
        properties.getLimits().setMaxScheduled(maxScheduled);
        return properties;
    }

    private static <T> T inlineMock(Class<T> cls) {
        return mock(cls, withSettings().mockMaker(MockMakers.INLINE));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
