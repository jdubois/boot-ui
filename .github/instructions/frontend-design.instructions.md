---
applyTo: "bootui-ui/**,bootui-spring-sample-app/e2e/**,bootui-quarkus-sample-app/e2e/**,PRODUCT.md,DESIGN.md,.impeccable/**,docs/images/**"
---

# Frontend and design

- Read `PRODUCT.md` and `DESIGN.md` before changing visible UI. The design north star is "The Calm Control Room."
- Use Vue 3 Composition API with `<script setup>`, plain JavaScript, Vue Router, Bootstrap 5.3, and Bootstrap Icons.
- Use Bootstrap primitives without shipping a default Bootstrap/admin-template appearance. The green-to-blue gradient means active or selected only.
- Meet WCAG 2.1 AA in light and dark themes. Check semantic colors and code text on tinted/selected surfaces, provide visible branded focus rings, and honor `prefers-reduced-motion`.
- Machine output is monospace; BootUI explanations are sans serif. Keep backgrounds cool and avoid cream/sand surfaces.
- Never trigger network calls, scans, or mutations on render.
- Use relative API paths such as `fetch('api/overview')`; never hardcode `/bootui/api`. Drive framework-specific copy from the panel manifest or DTO.
- `routes.js` is the sidebar source of truth. Keep route order/groups, `docs/features/`, and the app-shell Playwright
  navigation assertions aligned. When renaming a route path, add a redirect from the previous path.
- Add focused Vitest coverage for component/composable behavior. Run Playwright for browser flows, browser-facing API shapes, visible routes, or sample-app behavior.
- Shared interaction changes must be exercised in every affected browser runtime: Spring MVC, Spring WebFlux, and
  Quarkus. For ARIA, focus, tabs, dialogs, progress, or live-region changes, assert semantic uniqueness and keyboard
  behavior so duplicate announcements or hidden-but-focusable controls cannot pass visually.
- Bootstrap Playwright with `npm ci` and `npx playwright install --with-deps chromium` in the relevant E2E directory.
  The Spring E2E project runs MVC with `npm test` and WebFlux with `npm run test:webflux`; the Quarkus E2E project runs
  with `npm test`.
- For HMR, run the backend separately and open the Vite URL at `http://localhost:5173/bootui/`; Maven-served assets do
  not hot reload. Use `BOOTUI_API_PROXY_TARGET` for a non-default backend, and `BOOTUI_DEV_PATH` plus
  `BOOTUI_DEV_API_PATH` for custom mounts. Spring MVC, WebFlux, and Quarkus compare Origin/Host by host rather than port,
  so state-changing requests from Vite's port remain same-host.
- Before screenshots, reset both window scroll and `.bootui-workspace` scroll. Feature screenshots remain 1600x900 WebP at quality 80 with realistic non-sensitive data.
- Run the Impeccable skill for design, accessibility, or visual-system work.
