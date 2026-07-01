package io.github.jdubois.bootui.engine.architecture.fixtures;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Test fixture with medium and high severity Spring architecture violations.
 */
@Service
public class ProblematicSpringBean {

    @Autowired
    private CleanComponent cleanComponent;

    public void invoke() {
        refresh();
        if (cleanComponent != null) {
            cleanComponent.name();
        }
    }

    @Async
    public void refresh() {}
}
