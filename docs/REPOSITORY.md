# Repository and documentation

## Modules

- `bootui-spring-boot-starter`: dependency to add to your app.
- `bootui-spring-autoconfigure`: Spring Boot auto-configuration, REST controllers, and safety filter.
- `bootui-ui`: Vue 3 frontend packaged into the starter at `META-INF/resources/bootui/`.
- `bootui-core`: shared DTOs, secret masking, and core helpers.
- `bootui-spring-sample-app`: demo and integration sample app used for screenshots and Playwright coverage.

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
