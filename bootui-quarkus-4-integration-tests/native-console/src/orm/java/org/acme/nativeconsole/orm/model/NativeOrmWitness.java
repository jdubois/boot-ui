package org.acme.nativeconsole.orm.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "native_orm_witness")
public class NativeOrmWitness {

    @Id
    public Long id;
}
