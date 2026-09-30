---
applyTo: "pom.xml,bootui-quarkus-parent/**,bootui-quarkus/**,bootui-quarkus-deployment/**,bootui-quarkus-integration-tests/**,bootui-quarkus-sample-app/**,.github/workflows/jdk-compatibility.yml,docs/QUARKUS-SUPPORT.md"
---

# Quarkus adapter

- Use the `quarkus.platform.version` property in the root POM as the compatibility source of truth. A nearer Quarkus BOM
  under `bootui-quarkus-parent` must override the root Spring Boot BOM for Quarkus modules.
- Keep runtime JAX-RS resources and CDI producers thin; put build-time discovery, capability checks, bean registration, and production gating in deployment build steps.
- Production must remain dark. In `LaunchMode.NORMAL`, do not wire data-bearing endpoints or CDI services, and keep the always-registered production shell guard returning 404 for the BootUI surface.
- Runtime resources are discovered from the indexed extension JAR. Add SPI implementation beans that must survive removal to `BootUiQuarkusProcessor.addBeanClasses(...)`.
- `BootUiQuarkusSafetyFilter` and `QuarkusPanelAccessFilter` are Vert.x global filters and thin bindings over shared
  engine policy. Preserve their ordering and canonical JSON errors. Safety must cover unmatched BootUI paths before
  routing; panel gating applies only to API paths and must not gate the static shell.
- Read live policy from MicroProfile Config and fail closed on missing or invalid safety values. Resolve blocking startup state, such as container gateway detection, off the event loop.
- Optional APIs must never be linked in an application without their capability. Gate build steps, compile optional APIs as provided where appropriate, and exclude importing runtime classes by string name when absent.
- Prefer build-time Jandex capture plus recorder/synthetic-bean wiring when Quarkus has no reliable runtime discovery API.
- Update `QuarkusPanelAvailability` and `docs/QUARKUS-SUPPORT.md` whenever panel support changes. Distinguish unavailable, not-yet-supported, and not-applicable states honestly.
- The sample app's Quarkus/Hibernate augmentation is bounded by the class-file versions the platform-managed ByteBuddy
  recognizes (currently JDK 17 to 27; Quarkus 3.33 LTS officially supports 17, 21, and 25). Preserve the JDK 28+ skip
  profile and the Hibernate integration-test gate, and raise both bounds together only after verifying augmentation on
  the new JDK.
- Focused extension validation:
  `./mvnw -B -ntp -pl bootui-quarkus,bootui-quarkus-deployment,bootui-quarkus-integration-tests -am install`.
