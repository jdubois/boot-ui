package io.github.jdubois.bootui.sample.advisor.hibernate;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;

@Entity
@Table(name = "sample_advisor_devices")
// Hibernate Advisor demo: this entity intentionally trips several mapping and identifier checks.
public class SampleDevice {

    // Intentionally uses an assigned identifier without implementing Persistable to trigger HIB-ENTITY-007.
    // Also intentionally uses a primitive type (long) to trigger HIB-ENTITY-006.
    @Id
    private long serialNumber;

    // Intentionally uses an ElementCollection without an index on the collection table to trigger HIB-MAP-019.
    @ElementCollection
    private List<String> macAddresses;

    protected SampleDevice() {}

    public SampleDevice(long serialNumber, List<String> macAddresses) {
        this.serialNumber = serialNumber;
        this.macAddresses = macAddresses;
    }

    public long getSerialNumber() {
        return serialNumber;
    }

    public List<String> getMacAddresses() {
        return macAddresses;
    }
}
