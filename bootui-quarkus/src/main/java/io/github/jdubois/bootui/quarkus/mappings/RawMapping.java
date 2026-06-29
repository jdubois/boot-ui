package io.github.jdubois.bootui.quarkus.mappings;

import io.quarkus.runtime.annotations.RecordableConstructor;

/**
 * One HTTP route mapping for a single JAX-RS resource method, captured at <em>build time</em> by the
 * deployment processor from the build-time Jandex index (the application's {@code @Path} resources) and
 * replayed into the
 * runtime via a {@code @Recorder} (see {@code MappingsRecorder}).
 *
 * <p>The values are computed at build time (the class- and method-level {@code @Path} are combined and
 * normalized, the media-type arrays are joined, the handler is rendered as {@code declaringClass#method})
 * because no clean Quarkus <em>runtime</em> route-enumeration API exists — Vert.x exposes paths but not the
 * per-route method/produces/consumes detail this panel needs. BootUI's own {@code /bootui} routes are
 * already filtered out of the captured list, so this carrier only ever holds host-application mappings.</p>
 *
 * <p>This record is serialized into the Quarkus bytecode recorder, so its canonical constructor is annotated
 * {@link RecordableConstructor}; the module compiles with {@code -parameters} so the constructor parameter
 * names match the record components, which is what the recorder uses to read the captured values back. The
 * components map one-to-one onto the neutral {@code MappingDto} contract that the engine consumes.</p>
 *
 * @param method the HTTP method ({@code "GET"}, {@code "POST"}, …), or {@code "ANY"} for a sub-resource
 *     locator with no declared HTTP method
 * @param pattern the full request path (class {@code @Path} + method {@code @Path}, slash-normalized)
 * @param handler the {@code declaringClassFqn#methodName} identifier of the resource method
 * @param produces the sorted, comma-joined {@code @Produces} media types ({@code null} when unset)
 * @param consumes the sorted, comma-joined {@code @Consumes} media types ({@code null} when unset)
 */
public record RawMapping(String method, String pattern, String handler, String produces, String consumes) {

    @RecordableConstructor
    public RawMapping(String method, String pattern, String handler, String produces, String consumes) {
        this.method = method;
        this.pattern = pattern;
        this.handler = handler;
        this.produces = produces;
        this.consumes = consumes;
    }
}
