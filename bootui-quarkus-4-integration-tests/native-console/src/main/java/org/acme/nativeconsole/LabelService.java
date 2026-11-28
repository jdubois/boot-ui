package org.acme.nativeconsole;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

@Named("nativeAcceptanceLabel")
@ApplicationScoped
public class LabelService {

    public String label() {
        return "nested";
    }
}
