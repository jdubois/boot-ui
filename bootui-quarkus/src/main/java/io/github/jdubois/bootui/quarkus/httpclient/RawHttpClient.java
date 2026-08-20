package io.github.jdubois.bootui.quarkus.httpclient;

import io.quarkus.runtime.annotations.RecordableConstructor;

/**
 * The raw, verbatim {@code @org.eclipse.microprofile.rest.client.inject.RegisterRestClient} member values
 * for one declared REST client interface, captured at <em>build time</em> by the deployment processor's
 * Jandex scan and replayed into the runtime through a {@code @Recorder}.
 *
 * <p>Only annotation metadata is captured — never a client instance. The strings are stored exactly as
 * written, including Quarkus config expressions, because a {@code ${...}} reference only has a value at
 * runtime; {@code QuarkusHttpClientProvider} resolves them at request time so the panel can honestly report
 * an <em>unresolved</em> placeholder instead of hiding it behind a build-time guess.</p>
 *
 * <p>This record is serialized into the Quarkus bytecode recorder, so its canonical constructor is annotated
 * {@link RecordableConstructor}; the module compiles with {@code -parameters} so the constructor parameter
 * names match the record components.</p>
 *
 * @param interfaceName fully qualified name of the {@code @RegisterRestClient} interface
 * @param configKey the {@code configKey} member (empty when unset)
 * @param baseUri the {@code baseUri} member (empty when unset)
 */
public record RawHttpClient(String interfaceName, String configKey, String baseUri) {

    @RecordableConstructor
    public RawHttpClient(String interfaceName, String configKey, String baseUri) {
        this.interfaceName = interfaceName;
        this.configKey = configKey == null ? "" : configKey;
        this.baseUri = baseUri == null ? "" : baseUri;
    }
}
