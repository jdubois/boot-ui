package io.github.jdubois.bootui.engine.sqltrace;

import javax.sql.DataSource;

/**
 * Marker interface mixed into BootUI's traced {@link DataSource} proxies so the
 * {@code SqlTraceDataSourceBeanPostProcessor} (Spring adapter) never double-wraps a data source.
 */
public interface SqlTracedDataSource {}
