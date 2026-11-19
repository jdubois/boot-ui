# Troubleshooting

Home guides, compact findings/copy previews, GitHub Connect/Stop and the inactive-install diagnostic below describe
the **unreleased source preview**, not the published dependency. See [setup](../SETUP.md#open-the-console).

| Symptom | Check |
| ------- | ----- |
| `/bootui` returns 404 | For an ordinary inactive Spring AUTO install, look for the startup activation diagnostic. Activate `dev` or `local`, add DevTools, or set `bootui.enabled=ON` for local development, then open the configured `bootui.path`. YAML `bootui.enabled: ON` is valid. Explicit OFF and disabled profiles log only at DEBUG. |
| BootUI is disabled in `prod` | This is intentional. Only `bootui.enabled=ON` forces activation with a disabled profile. |
| The browser is rejected | BootUI accepts loopback callers and fails closed for everything else. In a container, set `bootui.trust-container-gateway=AUTO`. For a custom proxy, bridge, or LAN access, add the source range to `bootui.trusted-proxies` and the hostname you browse with to `bootui.allowed-hosts`. See [running inside a Docker container](environments.md#running-inside-a-docker-container). |
| Spring Security blocks the UI | BootUI registers a permit-all chain for the configured UI and API paths when Spring Security is active. Look for a custom chain with a higher precedence. |
| `localhost redirected you too many times` | BootUI serves the console at both `/bootui` and `/bootui/` with no redirect, so a trailing-slash-stripping filter or proxy cannot loop on it. On an older BootUI, upgrade or open `/bootui/` directly. |
| A command-line app now stays up | Expected: BootUI starts a servlet server so the console stays reachable. Set `bootui.force-web=false` to keep the application non-web. |
| A panel is empty or unavailable | Read its availability and collection status. Check the required integration in [Framework support](../FRAMEWORK-SUPPORT.md); Spring Actuator settings do not apply to Quarkus. Missing data is not a clean assessment. |
| Home shows guides, not findings | This is intentional. Follow **Review the code** to Scorecard or an advisor. Home does not run or aggregate assessments. |
| An advisor has no completed scan | No cached assessment is available. Run its scan explicitly if permitted; an unscanned state is not a clean result. |
| An assessment is partial or truncated | Read its coverage and [retained-detail warnings](../features/advisors.md#reading-every-retained-violation). Counts can exceed visible samples or retained details; loading a page does not rescan or recover details omitted by retention. |
| Search cannot find a violation | Advisor search covers rule metadata and cached samples, not unloaded pages. Open the rule and choose **View violations** to read retained details; retention limits may omit counted occurrences. |
| A rule link no longer resolves | The latest cached report may not contain that rule. A dismissed rule still opens and remains dismissed; following the link never rescans or restores it. |
| A Markdown copy fails | The browser may deny clipboard access. Copy manually from the selectable preview; its omissions section explains filtered, sampled, truncated, or unavailable evidence. |
| GitHub shows old data | Entry reads the local cache only. Choose **Connect** to permit bounded external refresh for this page session. Check credentials, API enablement, read-only policy, quotas, and any refresh error. Reloading clears consent. |
| Startup Timeline is empty | Keep `bootui.startup.enabled=true` and `bootui.startup.capacity` above zero, or provide your own `BufferingApplicationStartup`. |
| Secrets are hidden | Default exposure is `MASKED`. Use `METADATA_ONLY` to hide all values, or `FULL` only in a trusted local session. |
| Static resources are disabled | BootUI bypasses `spring.web.resources.add-mappings=false` for its own assets at the configured `bootui.path` and logs a WARN line. Your application's other static resources stay disabled. |
