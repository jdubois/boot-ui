# Repository and documentation

## Modules

- `bootui-core`: shared DTOs, secret masking, and core helpers.
- `bootui-engine`: framework-neutral services/advisors and SPI ports.
- `bootui-spring-autoconfigure`: Spring MVC/WebFlux adapter (auto-configuration, endpoints, safety).
- `bootui-spring-boot-starter`: Spring MVC starter dependency.
- `bootui-spring-boot-starter-reactive`: Spring WebFlux starter dependency.
- `bootui-ui`: Vue 3 frontend packaged into `META-INF/resources/bootui/`.
- `bootui-conformance`: shared HTTP contract suite and golden panel manifests for all adapters.
- `bootui-spring-sample-app`: Spring MVC sample app + Playwright e2e coverage.
- `bootui-spring-webflux-sample-app`: Spring WebFlux sample app.
- `bootui-quarkus-parent`: shared Quarkus LTS BOM and plugin management.
- `bootui-quarkus`: Quarkus runtime adapter.
- `bootui-quarkus-deployment`: Quarkus build-time wiring module.
- `bootui-quarkus-integration-tests`: Quarkus `@QuarkusTest` suites.
- `bootui-quarkus-sample-app`: Quarkus sample app.

## Compatibility version source of truth

Spring Boot and Quarkus compatibility references for the published adapters should follow the root `pom.xml` properties:

- `spring-boot.version`
- `quarkus.platform.version`

When these are updated, refresh matching documentation references in the same pull request (`README.md`,
`docs/SETUP.md`, `docs/features/`, `.github/copilot-instructions.md`, and
`.github/instructions/{spring-adapter,quarkus-adapter}.instructions.md`). All Quarkus modules inherit
`bootui-quarkus-parent`, which imports the Quarkus BOM closer than the root parent imports Spring Boot's BOM. This keeps
the two frameworks' shared transitive dependencies isolated while giving the extension, tests, and sample app one
Quarkus LTS version.

## Documentation website

The public documentation website is <https://www.julien-dubois.com/boot-ui/>. It is built with VuePress from the
markdown files in `docs/`, so repository documentation stays the source of truth for the published site.

```bash
npm install
npm run docs:dev
```

The local development server runs at <http://127.0.0.1:8090>. Before pushing documentation changes, run:

```bash
npm run docs:build
```

GitHub Pages is deployed by `.github/workflows/pages.yml` from the `main` branch. In the repository settings, set
**Pages > Build and deployment > Source** to **GitHub Actions**. The workflow builds VuePress with the `/boot-ui/` base
path and publishes the site at <https://www.julien-dubois.com/boot-ui/>.
