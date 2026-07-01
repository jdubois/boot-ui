package io.github.jdubois.bootui.sample.advisor.hibernate;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.util.List;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

@Entity
@Table(name = "sample_advisor_orders")
// Hibernate Advisor demo: this class intentionally triggers several HIB-FETCH, HIB-ID, and HIB-MAP checks.
public class SampleOrder {

    @Id
    // Intentionally uses allocationSize=1 to trigger HIB-ID-003.
    @SequenceGenerator(name = "sample_advisor_order_seq", allocationSize = 1)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sample_advisor_order_seq")
    private Long id;

    // Intentionally uses eager fetch, remove cascade, and @NotFound(IGNORE) to trigger
    // HIB-FETCH-001, HIB-MAP-005, and HIB-MAP-008.
    // Intentionally omits an @Index on the table for this ManyToOne to trigger HIB-MAP-018.
    @ManyToOne(fetch = FetchType.EAGER, cascade = CascadeType.REMOVE)
    @NotFound(action = NotFoundAction.IGNORE)
    private SampleCustomer customer;

    // Intentionally uses a List and remove-capable cascade on @ManyToMany to trigger
    // HIB-MAP-002 and HIB-MAP-004.
    @ManyToMany(cascade = CascadeType.ALL)
    private List<SampleTag> tags;

    // Intentionally adds a second bag collection so SampleOrder triggers HIB-FETCH-004.
    @OneToMany
    @JoinColumn(name = "sample_order_id")
    private List<SampleInvoice> invoices;

    // Intentionally omits @MapsId on the owning side to trigger HIB-MAP-006.
    @OneToOne
    private SampleOrderDetails details;

    // Intentionally omits @Enumerated to trigger HIB-MAP-003.
    private SampleOrderStatus status;

    protected SampleOrder() {}

    public SampleOrder(SampleCustomer customer, SampleOrderStatus status) {
        this.customer = customer;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public SampleCustomer getCustomer() {
        return customer;
    }

    public List<SampleTag> getTags() {
        return tags;
    }

    public List<SampleInvoice> getInvoices() {
        return invoices;
    }

    public SampleOrderDetails getDetails() {
        return details;
    }

    public SampleOrderStatus getStatus() {
        return status;
    }
}
