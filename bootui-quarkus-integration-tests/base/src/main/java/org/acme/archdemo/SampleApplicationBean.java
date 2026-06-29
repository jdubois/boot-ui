package org.acme.archdemo;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A trivial application bean that exists only to give the {@code @QuarkusTest} integration app a class of
 * its <em>own</em> in a distinct top-level package ({@code org.acme.archdemo}).
 *
 * <p>The base IT module otherwise has only {@code src/test} sources, all under
 * {@code io.github.jdubois.bootui.quarkus.*}. The Architecture advisor discovers its base packages from the
 * application's build-time Jandex index, so without an application-owned class in a separate namespace the
 * antichain reduction would collapse every discovered root into {@code io.github.jdubois.bootui.quarkus}
 * (where the extension's own test classes live) and the test could not prove that a real application
 * package is discovered. This bean makes {@code org.acme.archdemo} appear as a distinct root, exactly as a
 * consumer application's own package would.</p>
 */
@ApplicationScoped
public class SampleApplicationBean {

    public String describe() {
        return "bootui-quarkus architecture advisor integration sample";
    }
}
