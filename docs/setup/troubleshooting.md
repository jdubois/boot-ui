# Troubleshooting

| Symptom | Check |
| ------- | ----- |
| `/bootui` returns 404 | Activate the `dev` or `local` profile, add DevTools, or set `bootui.enabled=ON`, then open the configured `bootui.path`. In `application.yml`, `bootui.enabled: ON` is valid: YAML parses it as a boolean, which BootUI accepts. |
| BootUI is disabled in `prod` | This is intentional. Only `bootui.enabled=ON` forces activation with a disabled profile. |
| The browser is rejected | BootUI accepts loopback callers and fails closed for everything else. In a container, set `bootui.trust-container-gateway=AUTO`. For a custom proxy, bridge, or LAN access, add the source range to `bootui.trusted-proxies` and the hostname you browse with to `bootui.allowed-hosts`. See [running inside a Docker container](environments.md#running-inside-a-docker-container). |
| Spring Security blocks the UI | BootUI registers a permit-all chain for the configured UI and API paths when Spring Security is active. Look for a custom chain with a higher precedence. |
| `localhost redirected you too many times` | BootUI serves the console at both `/bootui` and `/bootui/` with no redirect, so a trailing-slash-stripping filter or proxy cannot loop on it. On an older BootUI, upgrade or open `/bootui/` directly. |
| A command-line app now stays up | Expected: BootUI starts a servlet server so the console stays reachable. Set `bootui.force-web=false` to keep the application non-web. |
| A panel is empty | Enable the relevant Actuator endpoint or optional Spring module. BootUI degrades to stable empty responses when data is unavailable. |
| Startup Timeline is empty | Keep `bootui.startup.enabled=true` and `bootui.startup.capacity` above zero, or provide your own `BufferingApplicationStartup`. |
| Secrets are hidden | Default exposure is `MASKED`. Use `METADATA_ONLY` to hide all values, or `FULL` only in a trusted local session. |
| Static resources are disabled | BootUI bypasses `spring.web.resources.add-mappings=false` for its own assets at the configured `bootui.path` and logs a WARN line. Your application's other static resources stay disabled. |
