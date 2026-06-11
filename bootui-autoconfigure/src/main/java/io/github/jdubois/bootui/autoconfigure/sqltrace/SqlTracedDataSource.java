package io.github.jdubois.bootui.autoconfigure.sqltrace;

import javax.sql.DataSource;

/**
 * Marker interface mixed into BootUI's traced {@link DataSource} proxies so the
 * {@link SqlTraceDataSourceBeanPostProcessor} never double-wraps a data source.
 */
public interface SqlTracedDataSource {}
