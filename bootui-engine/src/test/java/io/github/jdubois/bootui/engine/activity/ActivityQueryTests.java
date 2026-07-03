package io.github.jdubois.bootui.engine.activity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ActivityQueryTests {

    @Test
    void firstPageHasNoFiltersAndDefaultPageSize() {
        ActivityQuery query = ActivityQuery.firstPage("app-1");
        assertThat(query.instanceId()).isEqualTo("app-1");
        assertThat(query.type()).isNull();
        assertThat(query.severity()).isNull();
        assertThat(query.text()).isNull();
        assertThat(query.since()).isNull();
        assertThat(query.until()).isNull();
        assertThat(query.cursor()).isNull();
        assertThat(query.pageSize()).isEqualTo(ActivityQuery.DEFAULT_PAGE_SIZE);
    }

    @Test
    void nonPositivePageSizeFallsBackToDefault() {
        assertThat(new ActivityQuery("app-1", null, null, null, null, null, null, 0).pageSize())
                .isEqualTo(ActivityQuery.DEFAULT_PAGE_SIZE);
        assertThat(new ActivityQuery("app-1", null, null, null, null, null, null, -5).pageSize())
                .isEqualTo(ActivityQuery.DEFAULT_PAGE_SIZE);
    }

    @Test
    void withCursorAndWithPageSizePreserveOtherFields() {
        ActivityQuery base = new ActivityQuery("app-1", "SQL", "ERROR", "select", 10L, 20L, null, 50);

        ActivityQuery withCursor = base.withCursor("100_5");
        assertThat(withCursor.cursor()).isEqualTo("100_5");
        assertThat(withCursor.instanceId()).isEqualTo("app-1");
        assertThat(withCursor.type()).isEqualTo("SQL");
        assertThat(withCursor.pageSize()).isEqualTo(50);

        ActivityQuery withPageSize = base.withPageSize(201);
        assertThat(withPageSize.pageSize()).isEqualTo(201);
        assertThat(withPageSize.type()).isEqualTo("SQL");
    }

    @Test
    void normalizedAccessorsTreatBlankAsNull() {
        ActivityQuery query = new ActivityQuery("app-1", "  ", "", null, null, null, null, 10);
        assertThat(query.normalizedType()).isNull();
        assertThat(query.normalizedSeverity()).isNull();
        assertThat(query.normalizedText()).isNull();
    }

    @Test
    void normalizedAccessorsPassThroughNonBlankValues() {
        ActivityQuery query = new ActivityQuery("app-1", "SQL", "ERROR", "needle", null, null, null, 10);
        assertThat(query.normalizedType()).isEqualTo("SQL");
        assertThat(query.normalizedSeverity()).isEqualTo("ERROR");
        assertThat(query.normalizedText()).isEqualTo("needle");
    }
}
