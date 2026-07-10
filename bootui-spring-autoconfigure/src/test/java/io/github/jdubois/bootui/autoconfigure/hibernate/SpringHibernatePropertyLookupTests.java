package io.github.jdubois.bootui.autoconfigure.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.context.support.GenericWebApplicationContext;

class SpringHibernatePropertyLookupTests {

    @Test
    void reportsOpenInViewApplicableOnlyForServletApplications() {
        MockEnvironment environment = new MockEnvironment();

        assertThat(new SpringHibernatePropertyLookup(environment, true)
                        .apply(HibernateScanner.OPEN_IN_VIEW_APPLICABLE_PROPERTY))
                .isEqualTo("true");
        assertThat(new SpringHibernatePropertyLookup(environment, false)
                        .apply(HibernateScanner.OPEN_IN_VIEW_APPLICABLE_PROPERTY))
                .isEqualTo("false");
    }

    @Test
    void delegatesRegularPropertiesToTheEnvironment() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.jpa.open-in-view", "false");

        assertThat(new SpringHibernatePropertyLookup(environment, true).apply("spring.jpa.open-in-view"))
                .isEqualTo("false");
    }

    @Test
    void detectsServletContextWithoutLinkingTheOptionalTypeInMainCode() {
        try (GenericApplicationContext nonWeb = new GenericApplicationContext();
                GenericWebApplicationContext servlet = new GenericWebApplicationContext()) {
            assertThat(SpringHibernatePropertyLookup.isServletWebApplication(nonWeb))
                    .isFalse();
            assertThat(SpringHibernatePropertyLookup.isServletWebApplication(servlet))
                    .isTrue();
        }
    }
}
