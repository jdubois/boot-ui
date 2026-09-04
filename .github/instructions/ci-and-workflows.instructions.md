---
applyTo: ".github/workflows/**,.github/scripts/**,.github/dependabot.yml,Dockerfile*,docker-compose*.yml"
---

# CI, workflows, and container images

- Remote GitHub Actions are pinned to a full 40-character commit SHA with an inline release comment. Only the actions in
  the `is_trusted_action` allow-list of `.github/scripts/check-action-references.sh` may use a mutable major-version tag,
  and extending that list is a deliberate decision. Local actions keep relative paths. Check locally with
  `bash .github/scripts/check-action-references.sh`; `build.yml` runs it on every build.
- `.github/scripts/check-release-integrity.sh` pins literal strings and their ordering inside `release.yml` — the
  publication-only reactor, signed-tag verification, atomic push, and the CLI uber-jar check among them. Changing either
  file without the other fails the build, so update the workflow and its guard in the same change.
- `pages.yml` runs `.github/scripts/check-docs-downloads.sh` against the built site. Every install-script URL referenced
  from `README.md` or `docs/` must exist in `docs/.vuepress/public/` and in the built output, and the installers must
  stay version-free: they resolve the version at run time, and a literal version would not be rewritten by a release.
  `install.sh` must also pass `shellcheck -s sh`.
- `build.yml` is the Java 17 baseline: it is the gate for formatting, the full reactor with coverage, the SBOM, and the
  Spring and Quarkus Playwright suites. `jdk-compatibility.yml` covers Java 21 and 25 with a focused build. Keep new
  checks on the baseline workflow unless they are genuinely JDK-specific.
- Quarkus/Hibernate build-time augmentation is gated to the JDKs the shared Quarkus LTS platform supports. Preserve the
  JDK skip profile and the matrix gating rather than widening a job onto an unsupported JDK.
- Keep workflow permissions least-privilege and never echo secrets into command arguments or logs.
- The `Dockerfile*` variants (JVM, AOT, CRaC, native, WebFlux, Quarkus) and their `docker-compose*.yml` files ship the
  sample apps only. They are demonstration surfaces, not published artifacts; keep them building from the same reactor
  modules and do not let them become a second source of truth for versions.
