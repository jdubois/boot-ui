package io.github.jdubois.bootui.quarkus4.hibernate;

import io.github.jdubois.bootui.engine.hibernate.HibernateFactorySettingsReader;
import io.quarkus.hibernate.orm.runtime.spi.HibernateOrmIntegrationStaticInitListener;
import java.util.function.BiConsumer;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.boot.spi.MetadataImplementor;

/** Observes completed unit defaults before Hibernate discards its bootstrap metadata. */
public final class QuarkusHibernateFetchDefaults implements HibernateOrmIntegrationStaticInitListener {
    @Override
    public void contributeBootProperties(BiConsumer<String, Object> propertyCollector) {}

    @Override
    public void onMetadataInitialized(
            Metadata metadata, BootstrapContext bootstrapContext, BiConsumer<String, Object> propertyCollector) {
        boolean lazy = ((MetadataImplementor) metadata)
                .getMetadataBuildingOptions()
                .getMappingDefaults()
                .areEntitiesImplicitlyLazy();
        // ORM 8 uses this legacy-named scalar to resolve Persistence 4's DEFAULT to-one fetch type.
        propertyCollector.accept(HibernateFactorySettingsReader.DEFAULT_TO_ONE_FETCH_TYPE, lazy ? "LAZY" : "EAGER");
    }
}
