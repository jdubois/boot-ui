package io.github.bootui.autoconfigure.database;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Wraps every {@link DataSource} bean in the application context with a
 * recording proxy so BootUI can surface JDBC connection-pool stats and SQL
 * requests in the Database panel.
 *
 * <p>The wrapping is purely additive: each call is transparently delegated to
 * the original {@code DataSource}, with timing and SQL captured side-band into
 * the {@link SqlRecorder}.</p>
 */
public class DataSourceRecordingBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DataSourceRecordingBeanPostProcessor.class);

    private final SqlRecorder recorder;

    public DataSourceRecordingBeanPostProcessor(SqlRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource ds && !(bean instanceof RecordingDataSourceWrapper.RecordingDataSource)) {
            log.debug("BootUI: wrapping DataSource bean '{}' for SQL recording", beanName);
            return RecordingDataSourceWrapper.wrap(ds, beanName, recorder);
        }
        return bean;
    }
}
